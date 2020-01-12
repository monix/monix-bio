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

import monix.bio.BIO
import monix.bio.BIO.{Async, Context}
import monix.execution.ExecutionModel
import monix.execution.ExecutionModel.{AlwaysAsyncExecution, BatchedExecution, SynchronousExecution}

private[bio] object TaskExecuteWithModel {

  /**
    * Implementation for `Task.executeWithModel`
    */
  def apply[E, A](self: BIO[E, A], em: ExecutionModel): BIO[E, A] = {
    val start = (context: Context[E], cb: BiCallback[E, A]) => {
      val context2 = context.withExecutionModel(em)
      val frame = context2.frameRef

      // Increment the frame index because we have a changed
      // execution model, or otherwise we risk not following it
      // for the next step in our evaluation
      val nextIndex = em match {
        case BatchedExecution(_) =>
          em.nextFrameIndex(frame())
        case AlwaysAsyncExecution | SynchronousExecution =>
          em.nextFrameIndex(0)
      }
      TaskRunLoop.startFull[E, A](self, context2, cb, null, null, null, nextIndex)
    }

    Async(
      start,
      trampolineBefore = false,
      trampolineAfter = true,
      restoreLocals = false
    )
  }
}
