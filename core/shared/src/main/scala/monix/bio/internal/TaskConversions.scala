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

package monix.bio.internal

import cats.effect._
import monix.bio.{BIO, Task}

private[bio] object TaskConversions {

  /**
    * Implementation for `BIO.toAsync`.
    */
  def toAsync[F[_], A](source: Task[A])(implicit F: Async[F], eff: Effect[Task]): F[A] =
    source match {
      case BIO.Now(value) => F.pure(value)
      case BIO.Error(e) => F.raiseError(e)
      case BIO.FatalError(e) => F.raiseError(e)
      case BIO.Eval(thunk) => F.delay(thunk())
      case _ => F.async(cb => eff.runAsync(source)(r => { cb(r); IO.unit }).unsafeRunSync())
    }

}
