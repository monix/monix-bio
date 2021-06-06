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

import cats.laws._
import cats.laws.discipline._
import monix.execution.internal.Platform
import scala.concurrent.duration._
import scala.util.Success

object TaskStartSuite extends BaseTestSuite {
  test("task.start.flatMap(_.join) <-> task") { implicit sc =>
    check1 { (task: IO[Long, Int]) =>
      task.start.flatMap(_.join) <-> task
    }
  }

  test("task.start.flatMap(id) is cancelable, but the source is memoized") { implicit sc =>
    var effect = 0
    val task = UIO { effect += 1; effect }.delayExecution(1.second).start.flatMap(_.join)
    val f = task.runToFuture
    sc.tick()
    f.cancel()

    sc.tick(1.second)
    assertEquals(f.value, None)
    assertEquals(effect, 1)
  }

  test("task.start is stack safe") { implicit sc =>
    var task: UIO[Any] = UIO.evalAsync(1)
    for (_ <- 0 until 5000) task = task.start.flatMap(_.join)

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  testAsync("task.start shares Local.Context with fibers") { _ =>
    import monix.execution.Scheduler.Implicits.global
    import cats.syntax.all._
    implicit val opts = IO.defaultOptions.enableLocalContextPropagation

    val task = for {
      local <- IOLocal(0)
      _     <- local.write(100)
      v1    <- local.read
      f     <- (Task.shift *> local.read <* local.write(200)).start
      // Here, before joining, reads are nondeterministic
      v2 <- f.join
      v3 <- local.read
    } yield (v1, v2, v3)

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, (100, 100, 200))
    }
  }

  test("task.start is stack safe") { implicit sc =>
    val count = if (Platform.isJVM) 10000 else 1000
    def loop(n: Int): UIO[Unit] =
      if (n > 0)
        UIO(n - 1).start.flatMap(_.join).flatMap(loop)
      else
        UIO.unit

    val f = loop(count).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("task.start executes asynchronously") { implicit sc =>
    val task = UIO(1 + 1).start.flatMap(_.join)
    val f = task.runToFuture

    assertEquals(f.value, None)
    sc.tick()
    assertEquals(f.value, Some(Success(2)))
  }
}
