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
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.atomic.Atomic

private[bio] object TaskRace {

  /**
    * Implementation for `Task.race`.
    */
  def apply[E, A, B](fa: BIO[E, A], fb: BIO[E, B]): BIO[E, Either[A, B]] =
    BIO.Async(
      new Register(fa, fb),
      trampolineBefore = true,
      trampolineAfter = true
    )

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A, B](fa: BIO[E, A], fb: BIO[E, B]) extends ForkedRegister[E, Either[A, B]] {

    def apply(context: BIO.Context[E], cb: BiCallback[E, Either[A, B]]): Unit = {
      implicit val sc = context.scheduler
      val conn = context.connection

      val isActive = Atomic(true)
      val connA = TaskConnection[E]()
      val connB = TaskConnection[E]()
      conn.pushConnections(connA, connB)

      val contextA = context.withConnection(connA)
      val contextB = context.withConnection(connB)

      // First task: A
      BIO.unsafeStartEnsureAsync(
        fa,
        contextA,
        new BiCallback[E, A] {
          def onSuccess(valueA: A): Unit =
            if (isActive.getAndSet(false)) {
              connB.cancel.runAsyncAndForget
              conn.pop()
              cb.onSuccess(Left(valueA))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connB.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              sc.reportFailure(UncaughtErrorException.wrap(ex))
            }

          override def onTermination(e: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connB.cancel.runAsyncAndForget
              cb.onTermination(e)
            } else {
              sc.reportFailure(e)
            }
        }
      )

      // Second task: B
      BIO.unsafeStartEnsureAsync(
        fb,
        contextB,
        new BiCallback[E, B] {
          def onSuccess(valueB: B): Unit =
            if (isActive.getAndSet(false)) {
              connA.cancel.runAsyncAndForget
              conn.pop()
              cb.onSuccess(Right(valueB))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connA.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              sc.reportFailure(UncaughtErrorException.wrap(ex))
            }

          override def onTermination(e: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connA.cancel.runAsyncAndForget
              cb.onTermination(e)
            } else {
              sc.reportFailure(e)
            }
        }
      )
    }
  }
}
