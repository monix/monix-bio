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

import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.AbstractQueuedSynchronizer

import monix.bio.{BiCallback, IO}
import monix.bio.IO.{
  Async,
  Context,
  Error,
  Eval,
  EvalTotal,
  FlatMap,
  Map,
  Now,
  Suspend,
  SuspendTotal,
  Termination,
  Trace
}
import monix.bio.internal.TracingPlatform.{enhancedExceptions, isStackTracing}
import monix.bio.tracing.IOEvent
import monix.bio.internal.TaskRunLoop._
import monix.execution.Scheduler
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.internal.collection.ChunkedArrayStack

import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

private[bio] object TaskRunSyncUnsafe {

  /** Run-loop specialization that evaluates the given task and blocks for the result
    * if the given task is asynchronous.
    */
  def apply[E, A](source: IO[E, A], timeout: Duration, scheduler: Scheduler, opts: IO.Options): A = {
    var current = source.asInstanceOf[IO[Any, Any]]
    var bFirst: Bind = null
    var bRest: CallStack = null
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    // we might not need to initialize full Task.Context
    var tracingCtx: StackTracedContext = null

    while (true) {
      current match {
        case bind @ FlatMap(fa, bindNext, _) =>
          if (isStackTracing) {
            val trace = bind.trace
            if (tracingCtx eq null) tracingCtx = new StackTracedContext
            if (trace ne null) tracingCtx.pushEvent(trace.asInstanceOf[IOEvent])
          }

          if (bFirst ne null) {
            if (bRest eq null) bRest = ChunkedArrayStack()
            bRest.push(bFirst)
          }
          /*_*/
          bFirst = bindNext.asInstanceOf[Bind] /*_*/
          current = fa

        case Now(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Eval(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
          } catch {
            case e if NonFatal(e) =>
              current = Error(e)
          }

        case EvalTotal(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
          } catch {
            case e if NonFatal(e) =>
              current = Termination(e)
          }

        case bindNext @ Map(fa, _, _) =>
          if (isStackTracing) {
            val trace = bindNext.trace
            if (tracingCtx eq null) tracingCtx = new StackTracedContext
            if (trace ne null) tracingCtx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          if (bFirst ne null) {
            if (bRest eq null) bRest = ChunkedArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          current = fa

        case Suspend(thunk) =>
          // Try/catch described as statement to prevent ObjectRef ;-)
          try {
            current = thunk()
          } catch {
            case ex if NonFatal(ex) => current = Error(ex)
          }

        case SuspendTotal(thunk) =>
          // Try/catch described as statement to prevent ObjectRef ;-)
          try {
            current = thunk()
          } catch {
            case ex if NonFatal(ex) => current = Termination(ex)
          }

        case Error(error) =>
          if (isStackTracing && enhancedExceptions) {
            if (tracingCtx eq null) tracingCtx = new StackTracedContext
            if (error.isInstanceOf[Throwable]) {
              augmentException(error.asInstanceOf[Throwable], tracingCtx)
            }
          }
          findErrorHandler[Any](bFirst, bRest) match {
            case null => throw UncaughtErrorException.wrap(error)
            case bind =>
              // Try/catch described as statement to prevent ObjectRef ;-)
              try {
                current = bind.recover(error)
              } catch { case e if NonFatal(e) => current = Termination(e) }
              bFirst = null
          }

        case Termination(error) =>
          if (isStackTracing && enhancedExceptions) {
            if (tracingCtx eq null) tracingCtx = new StackTracedContext
            augmentException(error.asInstanceOf[Throwable], tracingCtx)
          }
          findTerminationHandler[Any](bFirst, bRest) match {
            case null => throw error
            case bind =>
              // Try/catch described as statement to prevent ObjectRef ;-)
              try {
                current = bind.recoverFatal(error)
              } catch { case e if NonFatal(e) => current = Termination(e) }
              bFirst = null
          }

        case Trace(sourceTask, frame) =>
          if (tracingCtx eq null) tracingCtx = new StackTracedContext
          tracingCtx.pushEvent(frame)
          current = sourceTask

        case async =>
          if (tracingCtx eq null) tracingCtx = new StackTracedContext
          return blockForResult(async, timeout, scheduler, opts, bFirst, bRest, tracingCtx)
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return unboxed.asInstanceOf[A]
          case bind =>
            // Try/catch described as statement to prevent ObjectRef ;-)
            try {
              current = bind(unboxed)
            } catch {
              case ex if NonFatal(ex) => current = Termination(ex)
            }
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
    }
    // $COVERAGE-OFF$
    throw new IllegalStateException("out of loop")
    // $COVERAGE-ON$
  }

  private def blockForResult[A](
    source: IO[Any, Any],
    limit: Duration,
    scheduler: Scheduler,
    opts: IO.Options,
    bFirst: Bind,
    bRest: CallStack,
    tracingCtx: StackTracedContext
  ): A = {

    val latch = new OneShotLatch
    val cb = new BlockingCallback[Any, Any](latch)
    val context = Context[Any](scheduler, opts, TaskConnection[Any](), tracingCtx)

    // Starting actual execution
    source match {
      case async: Async[Any, Any] @unchecked =>
        executeAsyncTask(async, context, cb, null, bFirst, bRest, 1)
      case _ =>
        startFull(source, context, cb, null, bFirst, bRest, 1)
    }

    val isFinished = limit match {
      case e if e eq Duration.Undefined =>
        throw new IllegalArgumentException("Cannot wait for Undefined period")
      case Duration.Inf =>
        blocking(latch.acquireSharedInterruptibly(1))
        true
      case f: FiniteDuration if f > Duration.Zero =>
        blocking(latch.tryAcquireSharedNanos(1, f.toNanos))
      case _ =>
        false
    }

    if (isFinished)
      cb.value.asInstanceOf[A]
    else
      throw new TimeoutException(s"Task.runSyncUnsafe($limit)")
  }

  private final class BlockingCallback[E, A](latch: OneShotLatch) extends BiCallback[E, A] {

    private[this] var success: A = _
    private[this] var error: E = _
    private[this] var terminalError: Throwable = _

    def value: A = {
      if (terminalError ne null) throw terminalError
      else
        error match {
          case null => success
          case e => throw UncaughtErrorException.wrap(e)
        }
    }

    def onSuccess(value: A): Unit = {
      success = value
      latch.releaseShared(1)
    }

    def onError(ex: E): Unit = {
      error = ex
      latch.releaseShared(1)
    }

    override def onTermination(ex: Throwable): Unit = {
      terminalError = ex
      latch.releaseShared(1)
    }
  }

  private final class OneShotLatch extends AbstractQueuedSynchronizer {

    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }
}
