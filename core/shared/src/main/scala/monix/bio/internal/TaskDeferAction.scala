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
import monix.execution.Scheduler

private[bio] object TaskDeferAction {

  /** Implementation for `Task.deferAction`. */
  def apply[E, A](f: Scheduler => BIO[E, A]): BIO[E, A] = {
    val start = (context: Context[E], callback: BiCallback[E, A]) => {
      val fa = f(context.scheduler)
      BIO.unsafeStartNow(fa, context, callback)
    }

    BIO.Async(
      start,
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = false
    )
  }
}
