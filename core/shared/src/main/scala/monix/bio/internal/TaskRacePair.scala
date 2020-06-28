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

package internal

import monix.execution.atomic.Atomic

import scala.concurrent.Promise

private[bio] object TaskRacePair {
  // Type aliasing the result only b/c it's a mouthful
  type RaceEither[E, A, B] = Either[(A, Fiber[E, B]), (Fiber[E, A], B)]

  /**
    * Implementation for `Task.racePair`.
    */
  def apply[E, A, B](fa: Task[E, A], fb: Task[E, B]): Task[E, RaceEither[E, A, B]] =
    Task.Async(
      new Register(fa, fb),
      trampolineBefore = true,
      trampolineAfter = true
    )

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A, B](fa: Task[E, A], fb: Task[E, B]) extends ForkedRegister[E, RaceEither[E, A, B]] {

    def apply(context: Task.Context[E], cb: BiCallback[E, RaceEither[E, A, B]]): Unit = {
      implicit val s = context.scheduler
      val conn = context.connection

      val pa = Promise[Either[E, A]]()
      val pb = Promise[Either[E, B]]()

      val isActive = Atomic(true)
      val connA = TaskConnection[E]()
      val connB = TaskConnection[E]()
      conn.pushConnections(connA, connB)

      val contextA = context.withConnection(connA)
      val contextB = context.withConnection(connB)

      // First task: A
      Task.unsafeStartEnsureAsync(
        fa,
        contextA,
        new BiCallback[E, A] {
          override def onSuccess(valueA: A): Unit =
            if (isActive.getAndSet(false)) {
              val fiberB = Fiber(TaskFromFutureEither.strict(pb.future), connB.cancel)
              conn.pop()
              cb.onSuccess(Left((valueA, fiberB)))
            } else {
              pa.success(Right(valueA))
            }

          override def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              connB.cancel.map { _ =>
                conn.pop()
                cb.onError(ex)
              }.runAsyncAndForget

            } else {
              pa.success(Left(ex))
            }

          override def onTermination(e: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              connB.cancel.map { _ =>
                conn.pop()
                cb.onTermination(e)
              }.runAsyncAndForget
            } else {
              pa.failure(e)
            }
        }
      )

      // Second task: B
      Task.unsafeStartEnsureAsync(
        fb,
        contextB,
        new BiCallback[E, B] {
          override def onSuccess(valueB: B): Unit =
            if (isActive.getAndSet(false)) {
              val fiberA = Fiber(TaskFromFutureEither.strict(pa.future), connA.cancel)
              conn.pop()
              cb.onSuccess(Right((fiberA, valueB)))
            } else {
              pb.success(Right(valueB))
            }

          override def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              connA.cancel.map { _ =>
                conn.pop()
                cb.onError(ex)
              }.runAsyncAndForget
            } else {
              pb.success(Left(ex))
            }

          override def onTermination(e: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              connA.cancel.map { _ =>
                conn.pop()
                cb.onTermination(e)
              }.runAsyncAndForget
            } else {
              pb.failure(e)
            }
        }
      )
    }
  }
}
