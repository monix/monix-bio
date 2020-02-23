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

import monix.execution.schedulers.TestScheduler

import scala.util.Success

object TaskExecuteWithOptionsSuite extends BaseTestSuite {
  test("executeWithOptions works") { implicit s =>
    val task = BIO
      .eval(1)
      .flatMap(_ => BIO.eval(2))
      .flatMap(_ => BIO.readOptions)
      .executeWithOptions(_.enableLocalContextPropagation)
      .flatMap(opt1 => BIO.readOptions.map(opt2 => (opt1, opt2)))

    val f = task.runToFuture
    s.tick()

    val Some(Success(Right((opt1, opt2)))) = f.value
    assert(opt1.localContextPropagation, "opt1.localContextPropagation")
    assert(!opt2.localContextPropagation, "!opt2.localContextPropagation")
  }

  testAsync("local.write.executeWithOptions") { _ =>
    import monix.execution.Scheduler.Implicits.global

    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).executeWithOptions(_.enableAutoCancelableRunLoops)
      _ <- BIO.shift
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }

  test("executeWithOptions is stack safe in flatMap loops") { implicit sc =>
    def loop(n: Int, acc: Long): UIO[Long] =
      BIO.unit.executeWithOptions(_.enableAutoCancelableRunLoops).flatMap { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

}
