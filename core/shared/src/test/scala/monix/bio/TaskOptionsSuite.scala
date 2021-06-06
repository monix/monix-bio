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

import minitest.SimpleTestSuite
import monix.bio.IO.Options
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Promise

object TaskOptionsSuite extends SimpleTestSuite {
  implicit val opts: Options = IO.defaultOptions.enableLocalContextPropagation

  def extractOptions[E]: IO[E, Options] =
    IO.Async[E, Options] { (ctx, cb) =>
      cb.onSuccess(ctx.options)
    }

  testAsync("change options with future") {
    val task = extractOptions.map { r =>
      assertEquals(r, opts)
    }
    task.runToFutureOpt.map(_ => ())
  }

  testAsync("change options with callback") {
    val p = Promise[Either[Int, Options]]()
    extractOptions[Int].runAsyncOpt {
      case Left(value) => value.fold(p.failure, i => p.success(Left(i)))
      case Right(value) => p.success(Right(value))
    }

    for (r <- p.future) yield {
      assertEquals(r, Right(opts))
    }
  }
}
