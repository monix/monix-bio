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
import monix.execution.Scheduler

import scala.concurrent.duration.Duration

private[bio] object TaskRunSyncUnsafe {

  /** Implementation of `Task.runSyncUnsafe`, meant to throw an
    * "unsupported exception", since JavaScript cannot support it.
    */
  def apply[E, A](source: BIO[E, A], timeout: Duration, scheduler: Scheduler, opts: BIO.Options): A = {
    // $COVERAGE-OFF$
    throw new UnsupportedOperationException("runSyncUnsafe isn't supported on top of JavaScript")
    // $COVERAGE-ON$
  }
}
