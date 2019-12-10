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

package monix.bio

import cats.effect.CancelToken
import monix.bio.internal.TaskConnection
import monix.execution.schedulers.TrampolineExecutionContext

import scala.concurrent.Promise
import scala.util.{Failure, Success}

/** `Fiber` represents the (pure) result of a [[Task]] being started concurrently
  * and that can be either joined or cancelled.
  *
  * You can think of fibers as being lightweight threads, a fiber being a
  * concurrency primitive for doing cooperative multi-tasking.
  *
  * For example a `Fiber` value is the result of evaluating [[BIO.start]]:
  *
  * {{{
  *   val task = Task.evalAsync(println("Hello!"))
  *
  *   val forked: Task[Fiber[Unit]] = task.start
  * }}}
  *
  * Usage example:
  *
  * {{{
  *   val launchMissiles = Task(println("Missiles launched!"))
  *   val runToBunker = Task(println("Run Lola run!"))
  *
  *   for {
  *     fiber <- launchMissiles.start
  *     _ <- runToBunker.onErrorHandleWith { error =>
  *       // Retreat failed, cancel launch (maybe we should
  *       // have retreated to our bunker before the launch?)
  *       fiber.cancel.flatMap(_ => Task.raiseError(error))
  *     }
  *     aftermath <- fiber.join
  *   } yield {
  *     aftermath
  *   }
  * }}}
  */
trait Fiber[E, A] extends cats.effect.Fiber[BIO[E, ?], A] {

  /**
    * Triggers the cancellation of the fiber.
    *
    * Returns a new task that will complete when the cancellation is
    * sent (but not when it is observed or acted upon).
    *
    * Note that if the background process that's evaluating the result
    * of the underlying fiber is already complete, then there's nothing
    * to cancel.
    */
  def cancel: CancelToken[BIO[E, ?]] // TODO: figure out a way to return CancelToken[UIO]

  /** Returns a new task that will await for the completion of the
    * underlying fiber, (asynchronously) blocking the current run-loop
    * until that result is available.
    */
  def join: BIO[E, A]
}

object Fiber {

  /**
    * Builds a [[Fiber]] value out of a `task` and its cancelation token.
    */
  def apply[E, A](task: BIO[E, A], cancel: CancelToken[BIO[E, ?]]): Fiber[E, A] =
    new Tuple(task, cancel)

  // TODO: test, completely new function, perhaps we can use CancelablePromise?
  // TODO: should we use trampolined scheduler?
  def fromPromise[E, A](p: Promise[Either[E, A]], conn: TaskConnection[E]): Fiber[E, A] = {
    val join = BIO.Async[E, A] { (ctx, cb) =>
      // Short-circuit for already completed `Future`
      p.future.value match {
        case Some(Success(value)) =>
          cb(value)
        case Some(Failure(ex)) =>
          cb.onFatalError(ex)
        case None =>
          // Cancellation needs to be linked to the active task
          ctx.connection.push(conn.cancel)(ctx.scheduler)
          p.future.onComplete {
            case Success(value) =>
              ctx.connection.pop()
              cb(value)
            case Failure(ex) =>
              ctx.connection.pop()
              cb.onFatalError(ex)
          }(TrampolineExecutionContext.immediate)
      }
    }
    new Tuple(join, conn.cancel)
  }

  private final case class Tuple[E, A](join: BIO[E, A], cancel: CancelToken[BIO[E, ?]]) extends Fiber[E, A]
}
