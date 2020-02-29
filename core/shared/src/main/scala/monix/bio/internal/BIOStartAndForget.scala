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

import monix.bio.BIO.Context
import monix.execution.Callback

private[bio] object BIOStartAndForget {

  /**
   *  Implementation for `BIO.startAndForget`
   */
  def apply[E, A](fa: BIO[E, A]): UIO[Unit] = {
    val start = (ctx: Context[Nothing], cb: Callback[Nothing, Unit]) => {
      implicit val sc = ctx.scheduler
      // It needs its own context, its own cancelable
      val ctx2 = BIO.Context[E](sc, ctx.options)
      // Starting actual execution of our newly created task forcing new async boundary
      BIO.unsafeStartEnsureAsync(fa, ctx2, BiCallback.empty)
      cb.onSuccess(())
    }
    BIO.Async[Nothing, Unit](start, trampolineBefore = false, trampolineAfter = true)
  }
}
