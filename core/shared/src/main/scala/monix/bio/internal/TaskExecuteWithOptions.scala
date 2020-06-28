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

import monix.bio.Task
import monix.bio.Task.{Context, ContextSwitch, Options}

private[bio] object TaskExecuteWithOptions {

  /**
    * Implementation for `Task.executeWithOptions`
    */
  def apply[E, A](self: Task[E, A], f: Options => Options): Task[E, A] =
    ContextSwitch[E, A](self, enable(f), disable.asInstanceOf[(A, E, Context[E], Context[E]) => Context[E]])

  private[this] def enable[E](f: Options => Options): Context[E] => Context[E] =
    ctx => {
      val opts2 = f(ctx.options)
      if (opts2 != ctx.options) ctx.withOptions(opts2)
      else ctx
    }

  private[this] val disable: (Any, Any, Context[Any], Context[Any]) => Context[Any] =
    (_, _, old, current) => current.withOptions(old.options)
}
