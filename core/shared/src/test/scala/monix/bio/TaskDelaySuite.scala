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
import monix.execution.internal.Platform

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskDelaySuite extends BaseTestSuite {
  test("BIO#delayExecution should work") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = UIO.evalAsync(trigger()).delayExecution(1.second)
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick()
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick(1.second)
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, Some(Success(Right("result"))))
  }

  test("BIO#delayExecution is stack safe, test 1") { implicit s =>
    def loop(n: Int): UIO[Int] =
      if (n <= 0) UIO.now(n)
      else
        UIO
          .now(n)
          .delayExecution(1.second)
          .flatMap(n => loop(n - 1))

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture
    s.tick(count.seconds)
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO#delayExecution is stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = UIO.now(0)
    for (_ <- 0 until count) task = task.delayExecution(1.second)
    val result = task.runToFuture
    s.tick(count.seconds)
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO#delayExecution is cancelable") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = UIO.evalAsync(trigger()).delayExecution(1.second)
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick()
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    f.cancel(); s.tick()
    assert(!wasTriggered, "!wasTriggered")

    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "should cancel the scheduleOnce(delay) as well")
  }

  test("BIO#delayResult should work") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = UIO.evalAsync(trigger()).delayResult(1.second)
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick()
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right("result"))))
  }

  test("BIO#delayResult is stack safe, test 1") { implicit s =>
    def loop(n: Int): UIO[Int] =
      if (n <= 0) UIO.now(n)
      else
        UIO
          .now(n)
          .delayResult(1.second)
          .flatMap(n => loop(n - 1))

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture
    s.tick(count.seconds)
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO#delayResult is stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = UIO.now(0)
    for (_ <- 0 until count) task = task.delayResult(1.second)
    val result = task.runToFuture
    s.tick(count.seconds)
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO#delayResult is cancelable") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = UIO.evalAsync(trigger()).delayResult(1.second)
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick()
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, None)

    f.cancel(); s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "should cancel the scheduleOnce(delay) as well")
  }

  test("BIO#delayResult should not delay in case of error") { implicit s =>
    val ex = "dummy"
    val task = BIO.raiseError(ex).delayResult(1.second)
    val result = task.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("BIO#delayResult should not delay in case of terminal error") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.terminate(ex).delayResult(1.second)
    val result = task.runToFuture

    s.tick()
    assertEquals(result.value, Some(Failure(ex)))
  }
}
