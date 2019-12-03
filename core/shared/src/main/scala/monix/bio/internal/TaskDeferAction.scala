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

package internal

import monix.bio.WRYYY.Context
import monix.execution.{Callback, Scheduler}

private[bio] object TaskDeferAction {

  /** Implementation for `Task.deferAction`. */
  def apply[E, A](f: Scheduler => WRYYY[E, A]): WRYYY[E, A] = {
    val start = (context: Context[E], callback: Callback[E, A]) => {
      implicit val ec = context.scheduler
//      var streamErrors = true

      // TODO: what if f(ec) fails?
//      try {
      val fa = f(ec)
//        streamErrors = false
      WRYYY.unsafeStartNow(fa, context, callback)
//      } catch {
//        case ex if NonFatal(ex) =>
//          if (streamErrors)
//            callback.onError(ex)
//          else {
//            // $COVERAGE-OFF$
//            ec.reportFailure(ex)
//            // $COVERAGE-ON$
//          }
//      }
    }

    WRYYY.Async(
      start,
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = false
    )
  }
}
