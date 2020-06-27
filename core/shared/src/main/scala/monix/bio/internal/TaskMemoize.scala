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

import monix.bio.BIO.{Context, Error, Now, Termination}
import monix.bio.internal.TaskRunLoop.startFull
import monix.bio.{BIO, BiCallback}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

private[bio] object TaskMemoize {

  /**
    * Implementation for `.memoize` and `.memoizeOnSuccess`.
    */
  def apply[E, A](source: BIO[E, A], cacheErrors: Boolean): BIO[E, A] =
    source match {
      case Now(_) | Error(_) | Termination(_) =>
        source

//      TODO: implement this optimization once `Coeval` is implemented
//      case BIO.Eval(Coeval.Suspend(f: LazyVal[A @unchecked])) if !cacheErrors || f.cacheErrors =>
//        source

      case BIO.Async(r: Register[E, A] @unchecked, _, _, _) if !cacheErrors || r.cacheErrors =>
        source
      case _ =>
        BIO.Async(
          new Register(source, cacheErrors),
          trampolineBefore = false,
          trampolineAfter = true,
          restoreLocals = true
        )
    }

  /** Registration function, used in `BIO.Async`. */
  private final class Register[E, A](source: BIO[E, A], val cacheErrors: Boolean)
      extends ((BIO.Context[E], BiCallback[E, A]) => Unit) { self =>

    // N.B. keeps state!
    private[this] var thunk = source
    private[this] val state = Atomic(null: AnyRef)

    def apply(ctx: Context[E], cb: BiCallback[E, A]): Unit = {
      implicit val sc = ctx.scheduler
      state.get() match {
        case result: Try[Either[E, A]] @unchecked =>
          cb(result)
        case _ =>
          start(ctx, cb)
      }
    }

    /** Saves the final result on completion and triggers the registered
      * listeners.
      */
    @tailrec def cacheValue(value: Try[Either[E, A]])(implicit s: Scheduler): Unit = {
      // Should we cache everything, error results as well,
      // or only successful results?
      if (self.cacheErrors || isSuccess(value)) {
        state.getAndSet(value) match {
          case p: Promise[Either[E, A]] @unchecked =>
            if (!p.tryComplete(value)) {
              // $COVERAGE-OFF$
              if (value.isFailure)
                s.reportFailure(value.failed.get)
              // $COVERAGE-ON$
            }
          case _ =>
            () // do nothing
        }
        // GC purposes
        self.thunk = null
      } else {
        // Error happened and we are not caching errors!
        state.get() match {
          case p: Promise[Either[E, A]] @unchecked =>
            // Resetting the state to `null` will trigger the
            // execution again on next `runAsync`
            if (state.compareAndSet(p, null)) {
              p.tryComplete(value)
            } else {
              // Race condition, retry
              // $COVERAGE-OFF$
              cacheValue(value)
              // $COVERAGE-ON$
            }
          case _ =>
            // $COVERAGE-OFF$
            () // Do nothing, as value is probably null already
          // $COVERAGE-ON$
        }
      }
    }

    /** Builds a callback that gets used to cache the result. */
    private def complete(implicit s: Scheduler): BiCallback[E, A] =
      new BiCallback[E, A] {

        def onSuccess(value: A): Unit =
          self.cacheValue(Success(Right(value)))

        def onError(e: E): Unit =
          self.cacheValue(Success(Left(e)))

        def onTermination(ex: Throwable): Unit =
          self.cacheValue(Failure(ex))
      }

    /** While the task is pending completion, registers a new listener
      * that will receive the result once the task is complete.
      */
    private def registerListener(p: Promise[Either[E, A]], context: Context[E], cb: BiCallback[E, A])(
      implicit ec: ExecutionContext
    ): Unit = {

      p.future.onComplete { r =>
        // Listener is cancelable: we simply ensure that the result isn't streamed
        if (!context.connection.isCanceled) {
          context.frameRef.reset()
          startFull(BIO.fromTryEither(r), context, cb, null, null, null, 1)
        }
      }
    }

    /**
      * Starts execution, eventually caching the value on completion.
      */
    @tailrec private def start(context: Context[E], cb: BiCallback[E, A]): Unit = {
      implicit val sc: Scheduler = context.scheduler
      self.state.get() match {
        case null =>
          val update = Promise[Either[E, A]]()

          if (!self.state.compareAndSet(null, update)) {
            // $COVERAGE-OFF$
            start(context, cb) // retry
            // $COVERAGE-ON$
          } else {
            // Registering listener callback for when listener is ready
            self.registerListener(update, context, cb)

            // Running main task in `uncancelable` model
            val ctx2 = context
              .withOptions(context.options.disableAutoCancelableRunLoops)
              .withConnection(TaskConnection.uncancelable)

            // Start with light async boundary to prevent stack-overflows!
            BIO.unsafeStartTrampolined(self.thunk, ctx2, self.complete)
          }

        case ref: Promise[Either[E, A]] @unchecked =>
          self.registerListener(ref, context, cb)

        case ref: Try[Either[E, A]] @unchecked =>
          // Race condition happened
          // $COVERAGE-OFF$
          cb(ref)
        // $COVERAGE-ON$
      }
    }
  }

  /**
    * Separates success case from expected and terminal error case.
    */
  private def isSuccess[E, A](result: Try[Either[E, A]]): Boolean = result match {
    case Success(Right(_)) => true
    case _ => false
  }
}
