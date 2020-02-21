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

import monix.execution.exceptions.DummyException

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskDoOnCancelSuite extends BaseTestSuite {
  test("doOnCancel should normally mirror the source") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .runToFuture

    s.tick(3.seconds)
    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
  }

  test("doOnCancel should mirror failed sources") { implicit s =>
    var effect = 0
    val dummy = "dummy"
    val f = BIO
      .raiseError(dummy)
      .executeAsync
      .doOnCancel(UIO.eval { effect += 1 })
      .runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
    assertEquals(effect, 0)
  }

  test("doOnCancel should mirror terminated sources") { implicit s =>
    var effect = 0
    val dummy = new RuntimeException("dummy")
    val f = BIO
      .terminate(dummy)
      .executeAsync
      .doOnCancel(UIO.eval { effect += 1 })
      .runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
    assertEquals(effect, 0)
  }

  test("doOnCancel should cancel delayResult #1") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .runToFuture

    s.tick(2.seconds)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 1)

    assert(s.state.tasks.isEmpty, "s.state.tasks.isEmpty")
  }

  test("doOnCancel should cancel delayResult #2") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .runToFuture

    s.tick(1.seconds)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 1)
    assertEquals(effect3, 1)

    assert(s.state.tasks.isEmpty, "s.state.tasks.isEmpty")
  }

  test("doOnCancel should cancel delayResult #3") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayResult(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(effect1, 1)
    assertEquals(effect2, 1)
    assertEquals(effect3, 1)

    assert(s.state.tasks.isEmpty, "s.state.tasks.isEmpty")
  }

  test("doOnCancel should cancel delayExecution #1") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .delayExecution(1.second)
      .runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 1)

    assert(s.state.tasks.isEmpty, "s.state.tasks.isEmpty")
  }

  test("doOnCancel should cancel delayExecution #2") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0

    val f = UIO
      .eval(1)
      .doOnCancel(UIO.eval { effect1 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect2 += 1 })
      .delayExecution(1.second)
      .doOnCancel(UIO.eval { effect3 += 1 })
      .delayExecution(1.second)
      .runToFuture

    s.tick(2.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 1)
    assertEquals(effect3, 1)

    assert(s.state.tasks.isEmpty, "s.state.tasks.isEmpty")
  }

  test("doOnCancel is stack safe in flatMap loops") { implicit sc =>
    val onCancel = UIO.evalAsync(throw DummyException("dummy"))

    def loop(n: Int, acc: Long): UIO[Long] =
      UIO.unit.doOnCancel(onCancel).flatMap { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          UIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  testAsync("local.write.doOnCancel works") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation
    val onCancel = UIO.evalAsync(throw DummyException("dummy"))

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).doOnCancel(onCancel)
      _ <- Task.shift
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }
}
