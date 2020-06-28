/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
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

package monix.bio

import monix.execution.exceptions.{CallbackCalledMultipleTimesException, UncaughtErrorException}
import monix.execution.schedulers.{TrampolineExecutionContext, TrampolinedRunnable}
import monix.execution.UncaughtExceptionReporter

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Callback type which supports two channels of errors.
  *
  * @define safetyIssues Can be called at most once by contract.
  *         Not necessarily thread-safe, depends on implementation.
  *
  *         @throws CallbackCalledMultipleTimesException depending on
  *                 implementation, when signaling via this callback is
  *                 attempted multiple times and the protocol violation
  *                 is detected.
  *
  * @define tryMethodDescription In case the underlying callback
  *         implementation protects against protocol violations, then
  *         this method should return `false` in case the final result
  *         was already signaled once via [[onSuccess]],
  *         [[onError]] or [[onTermination]].
  *
  *         The default implementation relies on catching
  *         [[monix.execution.exceptions.CallbackCalledMultipleTimesException CallbackCalledMultipleTimesException]]
  *         in case of violations, which is what thread-safe implementations
  *         of `onSuccess` or `onError` are usually throwing.
  *
  *         WARNING: this method is only provided as a
  *         convenience. The presence of this method does not
  *         guarantee that the underlying callback is thread-safe or
  *         that it protects against protocol violations.
  *
  *         @return `true` if the invocation completes normally or
  *                 `false` in case another concurrent call succeeded
  *                 first in signaling a result
  */
abstract class BiCallback[-E, -A] extends (Either[Cause[E], A] => Unit) {
  /**
    * Signals a successful value.
    *
    * $safetyIssues
    */
  def onSuccess(value: A): Unit

  /**
    * Signals an error.
    *
    * $safetyIssues
    */
  def onError(e: E): Unit

  /**
    * Signals a a terminal error which will not be reflected in the type signature.
    *
    * $safetyIssues
    */
  def onTermination(e: Throwable): Unit

  /**
    * Signals a value via Scala's `Either` where
    * - `Left`is a typed error
    * - `Right` is a successful value
    *
    * $safetyIssues
    */
  def apply(result: Either[Cause[E], A]): Unit =
    result match {
      case Right(a) => onSuccess(a)
      case Left(Cause.Error(e)) => onError(e)
      case Left(Cause.Termination(e)) => onTermination(e)
    }

  /**
    * Signals a value via Scala's `Try` of `Either` where
    * - `Left` is a typed error
    * - `Right` is a successful value
    * - `Failure` is a terminal error (a defect))
    *
    * $safetyIssues
    */
  def apply(result: Try[Either[E, A]]): Unit =
    result match {
      case Success(Right(a)) => onSuccess(a)
      case Success(Left(e)) => onError(e)
      case Failure(t) => onTermination(t)
    }

  /** Return a new callback that will apply the supplied function
    * before passing the result into this callback.
    */
  def contramap[B](f: B => A): BiCallback[E, B] =
    new BiCallback.Contramap(this, f)

  /**
    * Attempts to call [[BiCallback.onSuccess]].
    *
    * $tryMethodDescription
    */
  def tryOnSuccess(value: A): Boolean =
    try {
      onSuccess(value)
      true
    } catch {
      case _: CallbackCalledMultipleTimesException => false
    }

  /**
    * Attempts to call [[BiCallback.onError]].
    *
    * $tryMethodDescription
    */
  def tryOnError(e: E): Boolean =
    try {
      onError(e)
      true
    } catch {
      case _: CallbackCalledMultipleTimesException => false
    }

  /**
    * Attempts to call [[BiCallback.onTermination]].
    *
    * $tryMethodDescription
    */
  def tryOnTermination(e: Throwable): Boolean =
    try {
      onTermination(e)
      true
    } catch {
      case _: CallbackCalledMultipleTimesException => false
    }

  /**
    * Attempts to call [[BiCallback.apply(result:Either[monix\.bio\.Cause[E],A])* BiCallback.apply]].
    *
    * $tryMethodDescription
    */
  def tryApply(result: Either[Cause[E], A]): Boolean =
    result match {
      case Right(a) => tryOnSuccess(a)
      case Left(Cause.Error(e)) => tryOnError(e)
      case Left(Cause.Termination(t)) => tryOnTermination(t)
    }

  /**
    * Signals a value via Scala's `Try`.
    *
    * $safetyIssues
    */
  def apply(result: Try[A])(implicit ev: Throwable <:< E): Unit =
    result match {
      case Success(a) => onSuccess(a)
      case Failure(e) => onError(e)
    }

  /**
    * Attempts to call [[BiCallback.apply BiCallback.apply]].
    *
    * $tryMethodDescription
    */
  def tryApply(result: Try[A])(implicit ev: Throwable <:< E): Boolean =
    result match {
      case Success(a) => tryOnSuccess(a)
      case Failure(e) => tryOnError(e)
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
    * For building [[BiCallback]] objects using the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
    * technique.
    *
    * For example these are Equivalent:
    *
    * `BiCallback[Throwable].empty[String] <-> BiCallback.empty[Throwable, String]`
    */
  def apply[E]: Builders[E] = new Builders[E]

  /** Wraps any [[BiCallback]] into a safer implementation that
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

  /** Creates an empty [[BiCallback]], a callback that doesn't do
    * anything in `onNext` and that logs errors in `onError` with
    * the provided [[monix.execution.UncaughtExceptionReporter]].
    */
  def empty[E, A](implicit r: UncaughtExceptionReporter): BiCallback[E, A] =
    new Empty(r)

  /** Returns a [[BiCallback]] instance that will complete the given
    * promise.
    *
    * THREAD-SAFETY: the provided instance is thread-safe by virtue
    * of `Promise` being thread-safe.
    */
  def fromPromise[E, A](p: Promise[Either[E, A]]): BiCallback[E, A] =
    new BiCallback[E, A] {

      override def tryApply(result: Either[Cause[E], A]): Boolean = {
        result match {
          case Left(Cause.Error(value)) => p.trySuccess(Left(value))
          case Right(value) => p.trySuccess(Right(value))
          case Left(Cause.Termination(e)) => p.tryFailure(e)
        }
      }

      override def tryOnSuccess(value: A): Boolean =
        p.trySuccess(Right(value))

      override def tryOnError(e: E): Boolean =
        p.trySuccess(Left(e))

      override def onSuccess(value: A): Unit =
        if (!tryOnSuccess(value)) throw new CallbackCalledMultipleTimesException("onSuccess")

      override def onError(e: E): Unit = {
        if (!tryOnError(e)) {
          throw new CallbackCalledMultipleTimesException("onError", UncaughtErrorException.wrap(e))
        }
      }

      override def onTermination(e: Throwable): Unit =
        if (!tryOnTermination(e)) throw new CallbackCalledMultipleTimesException("onTermination")

      override def tryOnTermination(e: Throwable): Boolean =
        p.tryFailure(e)
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
      override def onTermination(e: Throwable): Unit = apply(Failure(e))

      override def apply(result: Try[A])(implicit ev: Throwable <:< Throwable): Unit =
        if (!tryApply(result)) {
          throw CallbackCalledMultipleTimesException.forResult(result)
        }

      override def tryApply(result: Try[A])(implicit ev: Throwable <:< Throwable): Boolean =
        if (isActive) {
          isActive = false
          cb(result)
          true
        } else {
          false
        }
    }

  /** Given a [[BiCallback]] wraps it into an implementation that
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
    * @see [[BiCallback.trampolined]]
    */
  def forked[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
    new AsyncFork(cb)

  /** Given a [[BiCallback]] wraps it into an implementation that
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
            apply(Left(Cause.Error(e)))
          }

          override def apply(result: Either[Cause[E], A]): Unit =
            if (!tryApply(result)) {
              throw CallbackCalledMultipleTimesException.forResult(result)
            }

          override def tryApply(result: Either[Cause[E], A]): Boolean =
            if (isActive) {
              isActive = false
              cb(result)
              true
            } else {
              false
            }

          override def onTermination(e: Throwable): Unit =
            if (!tryOnTermination(e)) {
              throw new CallbackCalledMultipleTimesException("onTermination", e)
            }

          override def tryOnTermination(e: Throwable): Boolean = {
            if (isActive) {
              isActive = false
              cb.apply(Left(Cause.Termination(e)))
              true
            } else {
              false
            }
          }
        }
    }

  private[monix] def callSuccess[E, A](cb: Either[E, A] => Unit, value: A): Unit =
    cb match {
      case ref: BiCallback[E, A] @unchecked => ref.onSuccess(value)
      case _ => cb(Right(value))
    }

  private[monix] def callError[E, A](cb: Either[Cause[E], A] => Unit, value: E): Unit =
    cb match {
      case ref: BiCallback[Cause[E], A] @unchecked => ref.onError(Cause.Error(value))
      case _ => cb(Left(Cause.Error(value)))
    }

  private[monix] def callTermination[E, A](cb: Either[Cause[E], A] => Unit, value: Throwable): Unit =
    cb match {
      case ref: BiCallback[Cause[E], A] @unchecked => ref.onTermination(value)
      case _ => cb(Left(Cause.Termination(value)))
    }

  private[monix] def signalErrorTrampolined[E, A](cb: BiCallback[E, A], e: E): Unit =
    TrampolineExecutionContext.immediate.execute(new Runnable {

      override def run(): Unit =
        cb.onError(e)
    })

  private[monix] def signalTerminationTrampolined[E, A](cb: BiCallback[E, A], e: Throwable): Unit =
    TrampolineExecutionContext.immediate.execute(new Runnable {

      override def run(): Unit =
        cb.onTermination(e)
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

    /** See [[BiCallback.fromTry]]. */
    def fromTry[A](cb: Try[A] => Unit)(implicit ev: Throwable <:< E): BiCallback[Throwable, A] =
      BiCallback.fromTry(cb)

    /** See [[BiCallback.forked]]. */
    def forked[A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
      BiCallback.forked(cb)

    /** See [[BiCallback.trampolined]]. */
    def trampolined[A](cb: BiCallback[E, A])(implicit ec: ExecutionContext): BiCallback[E, A] =
      BiCallback.trampolined(cb)

    /** See [[BiCallback.fromAttempt]]. */
    def fromAttempt[A](cb: Either[Cause[E], A] => Unit): BiCallback[E, A] =
      BiCallback.fromAttempt(cb)
  }

  private final class AsyncFork[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext) extends Base[E, A](cb)(ec)

  private final class TrampolinedCallback[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext)
      extends Base[E, A](cb)(ec) with TrampolinedRunnable

  /** Base implementation for `trampolined` and `forked`. */
  private class Base[E, A](cb: BiCallback[E, A])(implicit ec: ExecutionContext) extends BiCallback[E, A] with Runnable {
    private[this] val state = monix.execution.atomic.AtomicInt(0)
    private[this] var value: A = _
    private[this] var error: E = _
    private[this] var terminalError: Throwable = _

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

    override final def onTermination(e: Throwable): Unit =
      if (!tryOnTermination(e)) {
        throw new CallbackCalledMultipleTimesException(
          "Callback.onTermination",
          e
        )
      }

    override final def tryOnTermination(e: Throwable): Boolean = {
      if (state.compareAndSet(0, 3)) {
        this.terminalError = e
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
          val e = terminalError
          terminalError = null
          cb.onTermination(e)
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

    override def onTermination(e: Throwable): Unit = {
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
        }
      else {
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

    override def onTermination(e: Throwable): Unit = {
      if (isActive.compareAndSet(true, false)) {
        try {
          underlying.onTermination(e)
        } catch {
          case e: CallbackCalledMultipleTimesException =>
            throw e
          case e2 if NonFatal(e2) =>
            r.reportFailure(UncaughtErrorException.wrap(e))
            r.reportFailure(e2)
        }
      } else {
        val ex = UncaughtErrorException.wrap(e)
        throw new CallbackCalledMultipleTimesException("onTermination", ex)
      }
    }
  }

  private final class Contramap[-E, -A, -B](underlying: BiCallback[E, A], f: B => A) extends BiCallback[E, B] {
    def onSuccess(value: B): Unit =
      underlying.onSuccess(f(value))
    def onError(error: E): Unit =
      underlying.onError(error)
    def onTermination(e: Throwable): Unit =
      underlying.onTermination(e)
  }

  private[bio] def toEither[A](bcb: BiCallback[Throwable, A]): Either[Throwable, A] => Unit = {
    case Left(value) => bcb.onError(value)
    case Right(value) => bcb.onSuccess(value)
  }
}
