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

import java.util.concurrent.RejectedExecutionException

import cats.effect.CancelToken
import monix.bio.BIO.{Async, Context}
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.bio.{Task, BIO}
import monix.execution.atomic.AtomicInt
import monix.execution.exceptions.CallbackCalledMultipleTimesException
import monix.execution.internal.Platform
import monix.execution.schedulers.{StartAsyncBatchRunnable, TrampolinedRunnable}
import monix.execution.{Callback, Cancelable, Scheduler, UncaughtExceptionReporter}

private[bio] object TaskCreate {

  /**
    * Implementation for `cats.effect.Concurrent#cancelable`.
    */
  def cancelableEffect[A](k: (Either[Throwable, A] => Unit) => CancelToken[Task]): Task[A] =
    cancelable0[Throwable, A]((_, cb) => k(cb))

  /**
    * Implementation for `Task.cancelable`
    */ // TODO: should it take CancelToken[UIO]?
  def cancelable0[E, A](fn: (Scheduler, Callback[E, A]) => CancelToken[BIO[E, ?]]): BIO[E, A] = {
    val start = new Cancelable0Start[E, A, CancelToken[BIO[E, ?]]](fn) {
      def setConnection(ref: TaskConnectionRef[E], token: CancelToken[BIO[E, ?]])(implicit s: Scheduler): Unit =
        ref := token
    }
    Async[E, A](
      start,
      trampolineBefore = false,
      trampolineAfter = false
    )
  }

  // TODO: implement TaskCreate.cancelableIO
//  /**
//    * Implementation for `Task.create`, used via `TaskBuilder`.
//    */
//  def cancelableIO[A](start: (Scheduler, Callback[Throwable, A]) => CancelToken[IO]): Task[A] =
//    cancelable0((sc, cb) => Task.from(start(sc, cb)))

  /**
    * Implementation for `Task.create`, used via `TaskBuilder`.
    */
  def cancelableCancelable[E, A](fn: (Scheduler, Callback[E, A]) => Cancelable): BIO[E, A] = {
    val start = new Cancelable0Start[E, A, Cancelable](fn) {
      def setConnection(ref: TaskConnectionRef[E], token: Cancelable)(implicit s: Scheduler): Unit =
        ref := token
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `Task.async0`
    */
  def async0[E, A](fn: (Scheduler, Callback[E, A]) => Any): BIO[E, A] = {
    val start = (ctx: Context[E], cb: BiCallback[E, A]) => {
      implicit val s = ctx.scheduler
      val cbProtected = new CallbackForCreate[E, A](ctx, shouldPop = false, cb)
      fn(s, cbProtected)
      ()
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `cats.effect.Async#async`.
    *
    * It duplicates the implementation of `Task.async0` with the purpose
    * of avoiding extraneous callback allocations.
    */
  def async[E, A](k: Callback[E, A] => Unit): BIO[E, A] = {
    val start = (ctx: Context[E], cb: BiCallback[E, A]) => {
      implicit val s = ctx.scheduler
      val cbProtected = new CallbackForCreate[E, A](ctx, shouldPop = false, cb)
      k(cbProtected)
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `Task.asyncF`.
    */
  def asyncF[E, A](k: Callback[E, A] => BIO[E, Unit]): BIO[E, A] = {
    val start = (ctx: Context[E], cb: BiCallback[E, A]) => {
      implicit val s = ctx.scheduler
      // Creating new connection, because we can have a race condition
      // between the bind continuation and executing the generated task
      val ctx2 = Context[E](ctx.scheduler, ctx.options)
      val conn = ctx.connection
      conn.push(ctx2.connection.cancel)

      val cbProtected = new CallbackForCreate[E, A](ctx, shouldPop = true, cb)
      // Provided callback takes care of `conn.pop()`
      val task = k(cbProtected)
      BIO.unsafeStartNow(task, ctx2, new ForwardErrorCallback(cbProtected))
    }
    Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  private abstract class Cancelable0Start[E, A, Token](fn: (Scheduler, Callback[E, A]) => Token)
      extends ((Context[E], BiCallback[E, A]) => Unit) {

    def setConnection(ref: TaskConnectionRef[E], token: Token)(implicit s: Scheduler): Unit

    final def apply(ctx: Context[E], cb: BiCallback[E, A]): Unit = {
      implicit val s = ctx.scheduler
      val conn = ctx.connection
      val cancelable = TaskConnectionRef[E]()
      conn push cancelable.cancel

      val cbProtected = new CallbackForCreate[E, A](ctx, shouldPop = true, cb)
      val ref = fn(s, cbProtected)
      // Optimization to skip the assignment, as it's expensive
      if (!ref.isInstanceOf[Cancelable.IsDummy])
        setConnection(cancelable, ref)
    }
  }

  private final class ForwardErrorCallback[E](cb: BiCallback[E, _])(implicit r: UncaughtExceptionReporter)
      extends BiCallback[E, Unit] {

    override def onSuccess(value: Unit): Unit = ()

    override def onError(e: E): Unit =
      if (!cb.tryOnError(e)) {
        r.reportFailure(WrappedException.wrap(e))
      }

    override def onFatalError(e: Throwable): Unit =
      if (!cb.tryOnFatalError(e)) {
        r.reportFailure(e)
      }
  }

  private final class CallbackForCreate[E, A](ctx: Context[E], threadId: Long, shouldPop: Boolean, cb: BiCallback[E, A])
      extends BiCallback[E, A] with TrampolinedRunnable {

    private[this] val state = AtomicInt(0)
    private[this] var value: A = _
    private[this] var error: E = _
    private[this] var fatalError: Throwable = _
    private[this] var isSameThread = false

    def this(ctx: Context[E], shouldPop: Boolean, cb: BiCallback[E, A]) =
      this(ctx, Platform.currentThreadId(), shouldPop, cb)

    override def onSuccess(value: A): Unit = {
      if (!tryOnSuccess(value)) {
        throw new CallbackCalledMultipleTimesException("onSuccess")
      }
    }

    override def tryOnSuccess(value: A): Boolean = {
      if (state.compareAndSet(0, 1)) {
        this.value = value
        startExecution()
        true
      } else {
        false
      }
    }

    override def onError(e: E): Unit = {
      if (!tryOnError(e)) {
        throw new CallbackCalledMultipleTimesException("onError", WrappedException.wrap(e))
      }
    }

    override def tryOnError(e: E): Boolean = {
      if (state.compareAndSet(0, 2)) {
        this.error = e
        startExecution()
        true
      } else {
        false
      }
    }

    override def onFatalError(e: Throwable): Unit = {
      if (!tryOnFatalError(e)) {
        throw new CallbackCalledMultipleTimesException("onError", e)
      }
    }

    override def tryOnFatalError(e: Throwable): Boolean = {
      if (state.compareAndSet(0, 3)) {
        this.fatalError = e
        startExecution()
        true
      } else {
        false
      }
    }

    private def startExecution(): Unit = {
      // Cleanup of the current finalizer
      if (shouldPop) ctx.connection.pop()
      // Optimization — if the callback was called on the same thread
      // where it was created, then we are not going to fork
      // This is not safe to do when localContextPropagation enabled
      isSameThread = Platform.currentThreadId() == threadId

      try {
        ctx.scheduler.execute(
          if (isSameThread && !ctx.options.localContextPropagation)
            this
          else
            StartAsyncBatchRunnable(this, ctx.scheduler)
        )
      } catch {
        case e: RejectedExecutionException =>
          forceErrorReport(e)
      }
    }

    override def run(): Unit = {
      if (!isSameThread) {
        ctx.frameRef.reset()
      }
      state.get() match {
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

    // TODO: double check if we don't lose anything about fatal errors
    private def forceErrorReport(e: RejectedExecutionException): Unit = {
      value = null.asInstanceOf[A]
      if (error != null) {
        val e = error
        error = null.asInstanceOf[E]
        ctx.scheduler.reportFailure(WrappedException.wrap(e))
      }

      if (fatalError != null) {
        val e = fatalError
        fatalError = null
        ctx.scheduler.reportFailure(e)
      }

      BiCallback.signalFatalErrorTrampolined(cb, e)
    }
  }
}
