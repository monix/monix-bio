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

package monix

package object bio {
  /** Type alias that represents `IO` in which all expected errors were handled.
    */
  type UIO[+A] = IO[Nothing, A]

  /** Type alias that represents `IO` which is expected to fail with any `Throwable.`
    * Similar to `monix.eval.Task` and `cats.effect.IO`.
    *
    * WARNING: There are still two error channels (both `Throwable`) so use with care.
    *          If error is thrown from what was expected to be a pure function (map, flatMap, finalizers, etc.)
    *          then it will terminate the Task, instead of a normal failure.
    */
  type Task[+A] = IO[Throwable, A]
}
