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

import monix.bio.BIO
import monix.bio.BIO.{Async, Context, ContextSwitch, FlatMap, Map}

import scala.annotation.tailrec
import scala.runtime.AbstractFunction2

/** A marker for detecting asynchronous tasks that will fork execution.
  *
  * We prefer doing this because extraneous asynchronous boundaries
  * are more expensive than doing this check.
  *
  * N.B. the rule for start functions being marked via `ForkedStart`
  * is that the injected `Callback` MUST BE called after a full
  * asynchronous boundary.
  */
private[bio] abstract class ForkedRegister[E, A] extends AbstractFunction2[Context[E], BiCallback[E, A], Unit] {

  def apply(context: Context[E], cb: BiCallback[E, A]): Unit
}

private[bio] object ForkedRegister {

  /**
    * Returns `true` if the given task is known to fork execution,
    * or `false` otherwise.
    */
  @tailrec def detect(task: BIO[_, _], limit: Int = 8): Boolean = {
    if (limit > 0) task match {
      case Async(_: ForkedRegister[_, _], _, _, _) => true
      case FlatMap(other, _) => detect(other, limit - 1)
      case Map(other, _, _) => detect(other, limit - 1)
      case ContextSwitch(other, _, _) => detect(other, limit - 1)
      case _ => false
    } else {
      false
    }
  }
}
