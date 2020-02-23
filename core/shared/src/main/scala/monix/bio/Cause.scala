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

/** Represent a complete cause of the failed Task
  * exposing both typed and untyped error channel.
  */
sealed abstract class Cause[+E] extends Product with Serializable {
  def fold[B](fa: Throwable => B, fb: E => B): B =
    this match {
      case Cause.Error(value) => fb(value)
      case Cause.Termination(value) => fa(value)
    }

  def toThrowable(implicit E: E <:< Throwable): Throwable =
    fold(identity, e => E(e))
}

object Cause {
  final case class Error[+E](value: E) extends Cause[E]

  final case class Termination(value: Throwable) extends Cause[Nothing]
}
