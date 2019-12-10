/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio.internal

import monix.bio.Cause
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.execution.exceptions.{CallbackCalledMultipleTimesException, UncaughtErrorException}
import monix.execution.schedulers.{TrampolineExecutionContext, TrampolinedRunnable}
import monix.execution.{Callback, UncaughtExceptionReporter}

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Callback type which supports two channels of errors.
  */
abstract class BiCallback[-E, -A] extends Callback[E, A] {
  def onFatalError(e: Throwable): Unit

  def tryOnFatalError(e: Throwable): Boolean =
    try {
      onFatalError(e)
      true
    } catch {
      case _: CallbackCalledMultipleTimesException => false
    }
}

/**
  * @define isThreadSafe '''THREAD-SAFETY''': the returned callback is
  *         thread-safe.
  *
  *         In case `onSuccess` and `onError` get called multiple times,
  *         from multiple threads even, the implementation protects against
  *         access violations and throws a
  *         [[monix.execution.exceptions.CallbackCalledMultipleTimesException CallbackCalledMultipleTimesException]].
  */
object BiCallback {

  /**
    * For building [[Callback]] objects using the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
    * technique.
    *
    * For example these are Equivalent:
    *
    * `Callback[Throwable, Throwable].empty[String] <-> Callback.empty[Throwable, String]`
    */
  def apply[E]: Builders[E] = new Builders[E]

  /** Wraps any [[Callback]] into a safer implementation that
    * protects against protocol violations (e.g. `onSuccess` or `onError`
    * must be called at most once).
    *
    * $isThreadSafe
    */
  def safe[E, A](cb: BiCallback[E, A])(implicit r: UncaughtExceptionReporter): BiCallback[E, A] =
    cb match {
      case ref: Safe[E, A] @unchecked => ref
      case _ => new Safe[E, A](cb)
    }

  /** Creates an empty [[Callback]], a callback that doesn't do
    * anything in `onNext` and that logs errors in `onError` with
    * the provided [[monix.execution.UncaughtExceptionReporter]].
    */
  def empty[E, A](implicit r: UncaughtExceptionReporter): BiCallback[E, A] =
    new Empty(r)

  /** Returns a [[Callback]] instance that will complete the given
    * promise.
    *
    * THREAD-SAFETY: the provided instance is thread-safe by virtue
    * of `Promise` being thread-safe.
    */
  def fromPromise[E, A](p: Promise[Either[E, A]]): BiCallback[E, A] =
    new BiCallback[E, A] {

      override def tryApply(result: Try[A])(implicit ev: Throwable <:< E): Boolean =
        p.tryComplete(result.transform(a => Success(Right(a)), th => Success(Left(th))))

      override def tryApply(result: Either[E, A]): Boolean =
        p.trySuccess(result)

      override def tryOnSuccess(value: A): Boolean =
        p.trySuccess(Right(value))

      override def tryOnError(e: E): Boolean =
        p.trySuccess(Left(e))

      override def onSuccess(value: A): Unit =
        if (!tryOnSuccess(value)) throw new CallbackCalledMultipleTimesException("onSuccess")

      override def onError(e: E): Unit = {
        if (!tryOnError(e)) {
          throw new CallbackCalledMultipleTimesException("onError", WrappedException.wrap(e))
        }
      }

      override def apply(result: Try[A])(implicit ev: Throwable <:< E): Unit =
        if (!tryApply(result)) throw CallbackCalledMultipleTimesException.forResult(result)

      override def onFatalError(e: Throwable): Unit =
        if (!tryOnFatalError(e)) throw new CallbackCalledMultipleTimesException("onFatalError")

      override def tryOnFatalError(e: Throwable): Boolean =
        p.tryFailure(e)
    }

  /** Given a [[Callback]] wraps it into an implementation that
    * calls `onSuccess` and `onError` asynchronously, using the
    * given [[scala.concurrent.ExecutionContext]].
    *
    * The async boundary created is "light", in the sense that a
    * [[monix.execution.schedulers.TrampolinedRunnable TrampolinedRunnable]]
    * is used and supporting schedulers can execute these using an internal
    * trampoline, thus execution being faster and immediate, but still avoiding
    * growing the call-stack and thus avoiding stack overflows.
    *
    * $isThreadSafe
    *
    * @see [[Callback.trampolined]]
    */
  def forked[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
    new AsyncFork(cb)

  /** Given a [[Callback]] wraps it into an implementation that
    * calls `onSuccess` and `onError` asynchronously, using the
    * given [[scala.concurrent.ExecutionContext]].
    *
    * The async boundary created is "light", in the sense that a
    * [[monix.execution.schedulers.TrampolinedRunnable TrampolinedRunnable]]
    * is used and supporting schedulers can execute these using an internal
    * trampoline, thus execution being faster and immediate, but still avoiding
    * growing the call-stack and thus avoiding stack overflows.
    *
    * $isThreadSafe
    *
    * @see [[forked]]
    */
  def trampolined[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
    new TrampolinedCallback(cb)

  /** Turns `Either[Cause[E], A] => Unit` callbacks into Monix
    * callbacks.
    *
    * These are common within Cats' implementation, used for
    * example in `cats.effect.IO`.
    *
    * WARNING: the returned callback is NOT thread-safe!
    */
  def fromAttempt[E, A](cb: Either[Cause[E], A] => Unit): BiCallback[E, A] =
    cb match {
      case ref: BiCallback[E, A] @unchecked => ref
      case _ =>
        new BiCallback[E, A] {
          private[this] var isActive = true
          override def onSuccess(value: A): Unit = apply(Right(value))

          override def onError(e: E): Unit = {
            apply(Left(e))
          }

          override def apply(result: Either[E, A]): Unit =
            if (!tryApply(result)) {
              throw CallbackCalledMultipleTimesException.forResult(result)
            }

          override def tryApply(result: Either[E, A]): Boolean =
            if (isActive) {
              isActive = false
              cb(result.left.map(Cause.typed))
              true
            } else {
              false
            }

          override def onFatalError(e: Throwable): Unit =
            if (!tryOnFatalError(e)) {
              throw new CallbackCalledMultipleTimesException("onFatalError", e)
            }

          override def tryOnFatalError(e: Throwable): Boolean = {
            if (isActive) {
              isActive = false
              cb.apply(Left(Cause.fatal(e)))
              true
            } else {
              false
            }
          }
        }
    }

  /** Turns `Try[A] => Unit` callbacks into Monix callbacks.
    *
    * These are common within Scala's standard library implementation,
    * due to usage with Scala's `Future`.
    *
    * WARNING: the returned callback is NOT thread-safe!
    */
  def fromTry[A](cb: Try[A] => Unit): BiCallback[Throwable, A] =
    new BiCallback[Throwable, A] {
      private[this] var isActive = true
      override def onSuccess(value: A): Unit = apply(Success(value))
      override def onError(e: Throwable): Unit = apply(Failure(e))
      override def onFatalError(e: Throwable): Unit = apply(Failure(e))

      override def apply(result: Try[A])(implicit ev: Throwable <:< Throwable): Unit =
        if (!tryApply(result)) {
          throw CallbackCalledMultipleTimesException.forResult(result)
        }

      override def tryApply(result: Try[A])(implicit ev: Throwable <:< Throwable): Boolean = {
        if (isActive) {
          isActive = false
          cb(result)
          true
        } else {
          false
        }
      }

    }

  private[monix] def callSuccess[E, A](cb: Either[E, A] => Unit, value: A): Unit =
    cb match {
      case ref: Callback[E, A] @unchecked => ref.onSuccess(value)
      case _ => cb(Right(value))
    }

  private[monix] def callError[E, A](cb: Either[Cause[E], A] => Unit, value: E): Unit =
    cb match {
      case ref: BiCallback[Cause[E], A] @unchecked => ref.onError(Cause.typed(value))
      case _ => cb(Left(Cause.typed(value)))
    }

  private[monix] def callFatalError[E, A](cb: Either[Cause[E], A] => Unit, value: Throwable): Unit =
    cb match {
      case ref: BiCallback[Cause[E], A] @unchecked => ref.onFatalError(value)
      case _ => cb(Left(Cause.fatal(value)))
    }

  private[monix] def signalErrorTrampolined[E, A](cb: BiCallback[E, A], e: E): Unit =
    TrampolineExecutionContext.immediate.execute(new Runnable {

      override def run(): Unit =
        cb.onError(e)
    })

  private[monix] def signalFatalErrorTrampolined[E, A](cb: BiCallback[E, A], e: Throwable): Unit =
    TrampolineExecutionContext.immediate.execute(new Runnable {

      override def run(): Unit =
        cb.onFatalError(e)
    })

  /** Functions exposed via [[apply]]. */
  final class Builders[E](val ev: Boolean = true) extends AnyVal {

    /** See [[BiCallback.safe]]. */
    def safe[A](cb: BiCallback[E, A])(implicit r: UncaughtExceptionReporter): BiCallback[E, A] =
      BiCallback.safe(cb)

    /** See [[BiCallback.empty]]. */
    def empty[A](implicit r: UncaughtExceptionReporter): BiCallback[E, A] =
      BiCallback.empty

    /** See [[BiCallback.fromPromise]]. */
    def fromPromise[A](p: Promise[Either[E, A]]): BiCallback[E, A] =
      BiCallback.fromPromise(p)

    /** See [[BiCallback.forked]]. */
    def forked[A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
      BiCallback.forked(cb)

    /** See [[BiCallback.trampolined]]. */
    def trampolined[A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
      BiCallback.trampolined(cb)

    /** See [[BiCallback.fromAttempt]]. */
    def fromAttempt[A](cb: Either[Cause[E], A] => Unit): BiCallback[E, A] =
      BiCallback.fromAttempt(cb)

    /** See [[BiCallback.fromTry]]. */
    def fromTry[A](cb: Try[A] => Unit)(implicit ev: Throwable <:< E): BiCallback[Throwable, A] =
      BiCallback.fromTry(cb)
  }

  private final class AsyncFork[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext) extends Base[E, A](cb)(ec)

  private final class TrampolinedCallback[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext)
      extends Base[E, A](cb)(ec) with TrampolinedRunnable

  /** Base implementation for `trampolined` and `forked`. */
  private class Base[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext) extends BiCallback[E, A] with Runnable {
    private[this] val state = monix.execution.atomic.AtomicInt(0)
    private[this] var value: A = _
    private[this] var error: E = _
    private[this] var fatalError: Throwable = _

    override final def onSuccess(value: A): Unit =
      if (!tryOnSuccess(value)) {
        throw new CallbackCalledMultipleTimesException("onSuccess")
      }

    override final def tryOnSuccess(value: A): Boolean = {
      if (state.compareAndSet(0, 1)) {
        this.value = value
        ec.execute(this)
        true
      } else {
        false
      }
    }

    override final def onError(e: E): Unit =
      if (!tryOnError(e)) {
        throw new CallbackCalledMultipleTimesException(
          "Callback.onError",
          UncaughtErrorException.wrap(e)
        )
      }

    override final def tryOnError(e: E): Boolean = {
      if (state.compareAndSet(0, 2)) {
        this.error = e
        ec.execute(this)
        true
      } else {
        false
      }
    }

    override final def onFatalError(e: Throwable): Unit =
      if (!tryOnFatalError(e)) {
        throw new CallbackCalledMultipleTimesException(
          "Callback.onError",
          e
        )
      }

    override final def tryOnFatalError(e: Throwable): Boolean = {
      if (state.compareAndSet(0, 3)) {
        this.fatalError = e
        ec.execute(this)
        true
      } else {
        false
      }
    }

    override final def run(): Unit = {
      state.get match {
        case 1 =>
          val v = value
          value = null.asInstanceOf[A]
          cb.onSuccess(v)
        case 2 =>
          val e = error
          error = null.asInstanceOf[E]
          cb.onError(e)
        case 3 =>
          val e = fatalError
          fatalError = null
          cb.onFatalError(e)
      }
    }
  }

  /** An "empty" callback instance doesn't do anything `onSuccess` and
    * only logs exceptions `onError`.
    */
  private final class Empty(r: UncaughtExceptionReporter) extends BiCallback[Any, Any] {
    def onSuccess(value: Any): Unit = ()

    def onError(error: Any): Unit =
      r.reportFailure(UncaughtErrorException.wrap(error))

    override def onFatalError(e: Throwable): Unit = {
      r.reportFailure(e)
    }
  }

  /** A `SafeCallback` is a callback that ensures it can only be called
    * once, with a simple check.
    */
  private final class Safe[-E, -A](underlying: BiCallback[E, A])(implicit r: UncaughtExceptionReporter)
      extends BiCallback[E, A] {

    private[this] val isActive =
      monix.execution.atomic.AtomicBoolean(true)

    override def onSuccess(value: A): Unit = {
      if (isActive.compareAndSet(true, false))
        try {
          underlying.onSuccess(value)
        } catch {
          case e: CallbackCalledMultipleTimesException =>
            throw e
          case e if NonFatal(e) =>
            r.reportFailure(e)
        } else {
        throw new CallbackCalledMultipleTimesException("onSuccess")
      }
    }

    override def onError(e: E): Unit = {
      if (isActive.compareAndSet(true, false)) {
        try {
          underlying.onError(e)
        } catch {
          case e: CallbackCalledMultipleTimesException =>
            throw e
          case e2 if NonFatal(e2) =>
            r.reportFailure(UncaughtErrorException.wrap(e))
            r.reportFailure(e2)
        }
      } else {
        val ex = UncaughtErrorException.wrap(e)
        throw new CallbackCalledMultipleTimesException("onError", ex)
      }
    }

    override def onFatalError(e: Throwable): Unit = {
      if (isActive.compareAndSet(true, false)) {
        try {
          underlying.onFatalError(e)
        } catch {
          case e: CallbackCalledMultipleTimesException =>
            throw e
          case e2 if NonFatal(e2) =>
            r.reportFailure(UncaughtErrorException.wrap(e))
            r.reportFailure(e2)
        }
      } else {
        val ex = UncaughtErrorException.wrap(e)
        throw new CallbackCalledMultipleTimesException("onFatalError", ex)
      }
    }
  }

  private final class Contramap[-E, -A, -B](underlying: Callback[E, A], f: B => A) extends Callback[E, B] {

    def onSuccess(value: B): Unit =
      underlying.onSuccess(f(value))

    def onError(error: E): Unit =
      underlying.onError(error)
  }
}
