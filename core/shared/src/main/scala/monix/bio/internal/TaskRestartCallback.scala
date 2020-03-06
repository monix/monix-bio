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

package monix.bio.internal

import monix.bio.{BIO, BiCallback}
import monix.bio.BIO.{Context, Error, Now, Termination}
import monix.bio.internal.TaskRunLoop.{startFull, Bind, CallStack}
import monix.execution.exceptions.{CallbackCalledMultipleTimesException, UncaughtErrorException}
import monix.execution.misc.Local
import monix.execution.schedulers.TrampolinedRunnable

import scala.util.control.NonFatal

private[internal] abstract class TaskRestartCallback(contextInit: Context[Any], callback: BiCallback[Any, Any])
    extends BiCallback[Any, Any] with TrampolinedRunnable {

  // Modified on prepare()
  private[this] var bFirst: Bind = _
  private[this] var bRest: CallStack = _
  private[this] var register: (Context[Any], BiCallback[Any, Any]) => Unit = _

  // Mutated in onSuccess and onError, just before scheduling
  // onSuccessRun and onErrorRun
  private[this] var value: Any = _
  private[this] var error: Any = _
  private[this] var trampolineAfter: Boolean = true

  // Can change via ContextSwitch
  private[this] var context = contextInit

  final def contextSwitch(other: Context[Any]): Unit = {
    this.context = other
  }

  final def start(task: BIO.Async[Any, Any], bindCurrent: Bind, bindRest: CallStack): Unit = {
    this.bFirst = bindCurrent
    this.bRest = bindRest
    this.trampolineAfter = task.trampolineAfter
    prepareStart(task)

    if (task.trampolineBefore) {
      this.register = task.register
      context.scheduler.execute(this)
    } else {
      try task.register(context, this)
      catch {
        // Due to C-E law: ConcurrentEffect[Task].concurrentEffect.repeated callback ignored
        // but we don't want to lose any errors
        case cbError: CallbackCalledMultipleTimesException => context.scheduler.reportFailure(cbError)
        case NonFatal(e) => onTermination(e)
      }
    }
  }

  final def run(): Unit = {
    val fn = this.register
    this.register = null
    try fn(context, this)
    catch { case NonFatal(e) => onTermination(e) }
  }

  final def onSuccess(value: Any): Unit =
    if (!context.shouldCancel) {
      if (trampolineAfter) {
        this.value = value
        context.scheduler.execute(onSuccessRun)
      } else {
        syncOnSuccess(value)
      }
    }

  final def onError(error: Any): Unit =
    if (!context.shouldCancel) {
      if (trampolineAfter) {
        this.error = error
        context.scheduler.execute(onErrorRun)
      } else {
        syncOnError(error)
      }
    } else {
      // $COVERAGE-OFF$
      context.scheduler.reportFailure(UncaughtErrorException.wrap(error))
      // $COVERAGE-ON$
    }

  final override def onTermination(error: Throwable): Unit = {
    if (!context.shouldCancel) {
      if (trampolineAfter) {
        this.error = error
        context.scheduler.execute(onTerminationRun)
      } else {
        syncOnTermination(error)
      }
    } else {
      // $COVERAGE-OFF$
      context.scheduler.reportFailure(error)
      // $COVERAGE-ON$
    }
  }

  protected def prepareStart(task: BIO.Async[_, _]): Unit = ()
  protected def prepareCallback: BiCallback[Any, Any] = callback
  private[this] val wrappedCallback = prepareCallback

  protected def syncOnSuccess(value: Any): Unit = {
    val bFirst = this.bFirst
    val bRest = this.bRest
    this.bFirst = null
    this.bRest = null
    startFull(Now(value), context, wrappedCallback, this, bFirst, bRest, this.context.frameRef())
  }

  protected def syncOnError(error: Any): Unit = {
    val bFirst = this.bFirst
    val bRest = this.bRest
    this.bFirst = null
    this.bRest = null
    startFull(Error(error), context, this.wrappedCallback, this, bFirst, bRest, this.context.frameRef())
  }

  protected def syncOnTermination(error: Any): Unit = {
    val bFirst = this.bFirst
    val bRest = this.bRest
    this.bFirst = null
    this.bRest = null
    startFull(
      Termination(UncaughtErrorException.wrap(error)),
      context,
      this.wrappedCallback,
      this,
      bFirst,
      bRest,
      this.context.frameRef()
    )
  }

  /** Reusable Runnable reference, to go lighter on memory allocations. */
  private[this] val onSuccessRun: TrampolinedRunnable =
    new TrampolinedRunnable {

      def run(): Unit = {
        val v = value
        value = null
        syncOnSuccess(v)
      }
    }

  /** Reusable Runnable reference, to go lighter on memory allocations. */
  private[this] val onErrorRun: TrampolinedRunnable =
    new TrampolinedRunnable {

      def run(): Unit = {
        val e = error
        error = null
        syncOnError(e)
      }
    }

  /** Reusable Runnable reference, to go lighter on memory allocations. */
  private[this] val onTerminationRun: TrampolinedRunnable =
    new TrampolinedRunnable {

      def run(): Unit = {
        val e = error
        error = null
        syncOnTermination(e)
      }
    }
}

private[internal] object TaskRestartCallback {

  /** Builder for [[TaskRestartCallback]], returning a specific instance
    * optimized for the passed in `Task.Options`.
    */
  def apply(context: Context[Any], callback: BiCallback[Any, Any]): TaskRestartCallback = {
    if (context.options.localContextPropagation)
      new WithLocals(context, callback)
    else
      new NoLocals(context, callback)
  }

  /** `RestartCallback` class meant for `localContextPropagation == false`. */
  private final class NoLocals(context: Context[Any], callback: BiCallback[Any, Any])
      extends TaskRestartCallback(context, callback)

  /** `RestartCallback` class meant for `localContextPropagation == true`. */
  private final class WithLocals(context: Context[Any], callback: BiCallback[Any, Any])
      extends TaskRestartCallback(context, callback) {

    private[this] var preparedLocals: Local.Context = _
    private[this] var previousLocals: Local.Context = _

    override protected def prepareStart(task: BIO.Async[_, _]): Unit = {
      preparedLocals = if (task.restoreLocals) Local.getContext() else null
    }

    override def prepareCallback: BiCallback[Any, Any] =
      new BiCallback[Any, Any] {

        override def onSuccess(value: Any): Unit = {
          val locals = previousLocals
          if (locals ne null) Local.setContext(locals)
          callback.onSuccess(value)
        }

        override def onError(ex: Any): Unit = {
          val locals = previousLocals
          if (locals ne null) Local.setContext(locals)
          callback.onError(ex)
        }

        override def onTermination(e: Throwable): Unit = {
          val locals = previousLocals
          if (locals ne null) Local.setContext(locals)
          callback.onTermination(e)
        }
      }

    override protected def syncOnSuccess(value: Any): Unit = {
      setPreparedLocals()
      super.syncOnSuccess(value)
    }

    override protected def syncOnError(error: Any): Unit = {
      setPreparedLocals()
      super.syncOnError(error)
    }

    override protected def syncOnTermination(error: Any): Unit = {
      setPreparedLocals()
      super.syncOnTermination(error)
    }

    def setPreparedLocals(): Unit = {
      val preparedLocals = this.preparedLocals
      if (preparedLocals ne null) {
        previousLocals = Local.getContext()
        Local.setContext(preparedLocals)
      } else {
        previousLocals = null
      }
    }
  }
}
