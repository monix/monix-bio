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
import monix.bio.internal.TaskRunLoop._
import monix.bio.tracing.IOEvent
import monix.bio.internal.TracingPlatform.{enhancedExceptions, isStackTracing}
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.internal.collection.ChunkedArrayStack
import monix.execution.misc.Local
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.Promise
import scala.util.control.NonFatal

private[bio] object IORunToFutureWithLocal {
  /** A run-loop that attempts to complete a `CancelableFuture`
    * synchronously falling back to [[startFull]] and actual
    * asynchronous execution in case of an asynchronous boundary.
    *
    * Function gets invoked by `IO.runToFuture(implicit s: Scheduler)`.
    */
  def startFuture[E, A](source: IO[E, A], scheduler: Scheduler, opts: IO.Options): CancelableFuture[A] = {
    var current = source.asInstanceOf[IO[Any, Any]]
    var bFirst: Bind = null
    var bRest: CallStack = null
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // Keeps track of the current frame, used for forced async boundaries
    val em = scheduler.executionModel
    var frameIndex = frameStart(em)

    // we might not need to initialize full Task.Context
    var tracingCtx: StackTracedContext = null

    val prev = Local.getContext()
    val isolated = prev.isolate()

    while (true) {
      if (frameIndex != 0) {
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
            Local.setContext(isolated)
            try {
              unboxed = thunk().asInstanceOf[AnyRef]
              hasUnboxed = true
              current = null
            } catch {
              case e if NonFatal(e) =>
                current = Error(e)
            }

          case EvalTotal(thunk) =>
            Local.setContext(isolated)
            try {
              unboxed = thunk().asInstanceOf[AnyRef]
              hasUnboxed = true
              current = null
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
            Local.setContext(isolated)
            // Try/catch described as statement to prevent ObjectRef ;-)
            try {
              current = thunk()
            } catch {
              case ex if NonFatal(ex) => current = Error(ex)
            }

          case SuspendTotal(thunk) =>
            Local.setContext(isolated)
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
              case null =>
                return CancelableFuture.failed(UncaughtErrorException.wrap(error))
              case bind =>
                // Try/catch described as statement to prevent ObjectRef ;-)
                try {
                  current = bind.recover(error)
                } catch { case e if NonFatal(e) => current = Error(e) }
                frameIndex = em.nextFrameIndex(frameIndex)
                bFirst = null
            }

          case Termination(error) =>
            if (isStackTracing && enhancedExceptions) {
              if (tracingCtx eq null) tracingCtx = new StackTracedContext
              augmentException(error.asInstanceOf[Throwable], tracingCtx)
            }
            findTerminationHandler[Any](bFirst, bRest) match {
              case null =>
                return CancelableFuture.failed(error)
              case bind =>
                // Try/catch described as statement to prevent ObjectRef ;-)
                try {
                  current = bind.recoverFatal(error)
                } catch { case e if NonFatal(e) => current = Termination(e) }
                frameIndex = em.nextFrameIndex(frameIndex)
                bFirst = null
            }

          case Trace(sourceTask, frame) =>
            if (tracingCtx eq null) tracingCtx = new StackTracedContext
            tracingCtx.pushEvent(frame)
            current = sourceTask

          case async =>
            if (tracingCtx eq null) tracingCtx = new StackTracedContext

            return goAsync4Future(
              async,
              scheduler,
              opts,
              bFirst,
              bRest,
              frameIndex,
              forceFork = false,
              prev,
              isolated,
              tracingCtx = tracingCtx
            )
        }

        if (hasUnboxed) {
          popNextBind(bFirst, bRest) match {
            case null =>
              // Restore Local on the current thread
              Local.setContext(prev)
              return CancelableFuture.successfulWithLocal(unboxed.asInstanceOf[A], isolated)

            case bind =>
              // Try/catch described as statement to prevent ObjectRef ;-)
              try {
                current = bind(unboxed)
              } catch {
                case ex if NonFatal(ex) => current = Error(ex)
              }
              frameIndex = em.nextFrameIndex(frameIndex)
              hasUnboxed = false
              unboxed = null
              bFirst = null
          }
        }
      } else {
        if (tracingCtx eq null) tracingCtx = new StackTracedContext
        // Force async boundary
        return goAsync4Future(
          current,
          scheduler,
          opts,
          bFirst,
          bRest,
          frameIndex,
          forceFork = true,
          prev,
          isolated,
          tracingCtx = tracingCtx
        )
      }
    }
    // $COVERAGE-OFF$
    null
    // $COVERAGE-ON$
  }

  /** Called when we hit the first async boundary in [[startFuture]]. */
  private def goAsync4Future[E, A](
    source: Current,
    scheduler: Scheduler,
    opts: IO.Options,
    bFirst: Bind,
    bRest: CallStack,
    nextFrame: FrameIndex,
    forceFork: Boolean,
    previousCtx: Local.Context,
    isolatedCtx: Local.Context,
    tracingCtx: StackTracedContext
  ): CancelableFuture[A] = {

    Local.setContext(isolatedCtx)

    val p = Promise[A]()
    val cb = BiCallback.fromTry(p.complete).asInstanceOf[BiCallback[Any, Any]]
    val context = Context(scheduler, opts, TaskConnection[Any](), tracingCtx)

    if (!forceFork) source match {
      case async: Async[Any, Any] @unchecked =>
        executeAsyncTask(async, context, cb, null, bFirst, bRest, 1)
      case _ =>
        startFull(source, context, cb, null, bFirst, bRest, nextFrame)
    }
    else {
      restartAsync(source.asInstanceOf[IO[E, A]], context, cb, null, bFirst, bRest)
    }

    Local.setContext(previousCtx)

    CancelableFuture.applyWithLocal(p.future, context.connection.toCancelable(scheduler), isolatedCtx)
  }
}
