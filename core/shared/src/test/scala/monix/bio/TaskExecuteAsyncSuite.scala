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

import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler

import scala.util.Success

object TaskExecuteAsyncSuite extends BaseTestSuite {
  test("IO.now.executeAsync should execute async") { implicit s =>
    val t = IO.now(10).executeAsync
    val f = t.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("IO.now.executeOn should execute async if forceAsync = true") { implicit s =>
    val s2 = TestScheduler()
    val t = IO.now(10).executeOn(s2)
    val f = t.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("IO.now.executeOn should not execute async if forceAsync = false") { implicit s =>
    val s2 = TestScheduler()
    val t = IO.now(10).executeOn(s2, forceAsync = false)
    val f = t.runToFuture

    assertEquals(f.value, Some(Success(10)))
  }

  test("IO.create.executeOn should execute async") { implicit s =>
    val s2 = TestScheduler()
    val source = IO.cancelable0[Int, Int] { (_, cb) =>
      cb.onSuccess(10); IO.unit
    }
    val t = source.executeOn(s2)
    val f = t.attempt.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("executeAsync should be stack safe, test 1") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.executeAsync

    val result = task.runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(1)))
  }

  test("IO.executeOn should be stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.executeOn(s)

    val result = task.runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(1)))
  }

  test("executeAsync should be stack safe, test 3") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000

    def loop(n: Int): UIO[Int] =
      if (n <= 0) IO.now(0).executeAsync
      else IO.now(n).executeAsync.flatMap(_ => loop(n - 1))

    val result = loop(count).runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(0)))
  }
}
