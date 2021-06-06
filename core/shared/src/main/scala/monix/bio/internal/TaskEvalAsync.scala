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

import monix.bio.{BiCallback, IO, Task}

import scala.util.control.NonFatal

private[bio] object TaskEvalAsync {

  /** Implementation for `IO.evalAsync`.
    */
  def apply[A](a: () => A): Task[A] =
    IO.Async(
      new EvalAsyncRegister[A](a),
      trampolineAfter = false,
      trampolineBefore = false,
      restoreLocals = false
    )

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation
  private final class EvalAsyncRegister[A](a: () => A) extends ForkedRegister[Throwable, A] {

    def apply(ctx: IO.Context[Throwable], cb: BiCallback[Throwable, A]): Unit =
      ctx.scheduler.execute(() => {
        ctx.frameRef.reset()
        var streamError = true
        try {
          val result = a()
          streamError = false
          cb.onSuccess(result)
        } catch {
          case e if streamError && NonFatal(e) =>
            cb.onError(e)
        }
      })
  }
}
