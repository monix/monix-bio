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

import monix.bio.BIO.Context

package object internal {

  /** Handy alias for building `Task.Async` nodes. */
  private[bio] type Start[E, A] = (Context[E], BiCallback[E, A]) => Unit

  /** Internal API: A run-loop frame index is a number representing the current
    * run-loop cycle, being incremented whenever a `flatMap` evaluation happens.
    *
    * It gets used for automatically forcing asynchronous boundaries, according to the
    * [[monix.execution.ExecutionModel ExecutionModel]]
    * injected by the [[monix.execution.Scheduler Scheduler]] when
    * the task gets evaluated with `runAsync`.
    *
    * @see [[FrameIndexRef]]
    */
  private[bio] type FrameIndex = Int
}
