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
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success, Try}

object TaskNowSuite extends BaseTestSuite {
  test("Task.now should work synchronously") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = Task.now(trigger())
    assert(wasTriggered, "wasTriggered")
    val f = task.runToFuture
    assertEquals(f.value, Some(Success("result")))
  }

  test("Task.now.runAsync: CancelableFuture should be synchronous for AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = Task.now(trigger())
    assert(wasTriggered, "wasTriggered")

    val f = task.runToFuture
    assertEquals(f.value, Some(Success("result")))
  }

  test("Task.now.runAsync(callback) should work synchronously") { implicit s =>
    var result = Option.empty[Try[String]]
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = Task.now(trigger())
    assert(wasTriggered, "wasTriggered")

    task.runAsync(r => result = Some(r.left.map(_.fold(identity, identity)).toTry))
    assertEquals(result, Some(Success("result")))
  }

  test("Task.now.runAsync(callback) should be asynchronous for AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    var result = Option.empty[Try[String]]
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = Task.now(trigger())
    assert(wasTriggered, "wasTriggered")

    task.runAsync(r => result = Some(r.left.map(_.fold(identity, identity)).toTry))
    assertEquals(result, None)
    s2.tick()
    assertEquals(result, Some(Success("result")))
  }

  test("Task.raiseError should work synchronously") { implicit s =>
    var wasTriggered = false
    val dummy = 1204
    def trigger(): Int = { wasTriggered = true; dummy }

    val task = Task.raiseError(trigger())
    assert(wasTriggered, "wasTriggered")
    val f = task.attempt.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("Task.terminate should work synchronously") { implicit s =>
    var wasTriggered = false
    val dummy = DummyException("dummy")
    def trigger(): Throwable = { wasTriggered = true; dummy }

    val task = Task.terminate(trigger())
    assert(wasTriggered, "wasTriggered")
    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.raiseError.runAsync: CancelableFuture should be synchronous for AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    val dummy = DummyException("1204")
    var wasTriggered = false
    def trigger() = { wasTriggered = true; dummy }

    val task = Task.raiseError(trigger())
    assert(wasTriggered, "wasTriggered")

    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.raiseError.runAsync(callback) should work synchronously") { implicit s =>
    var result = Option.empty[Try[String]]
    val dummy = DummyException("dummy")
    var wasTriggered = false
    def trigger(): Throwable = { wasTriggered = true; dummy }

    val task = Task.raiseError(trigger())
    assert(wasTriggered, "wasTriggered")

    task.runAsync(r => result = Some(r.left.map(_.fold(identity, identity)).toTry))
    assertEquals(result, Some(Failure(dummy)))
  }

  test("Task.raiseError.runAsync(callback) should be asynchronous for AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    val dummy = DummyException("dummy")
    var result = Option.empty[Try[String]]
    var wasTriggered = false
    def trigger(): Throwable = { wasTriggered = true; dummy }

    val task = Task.raiseError(trigger())
    assert(wasTriggered, "wasTriggered")

    task.runAsync(r => result = Some(r.left.map(_.fold(identity, identity)).toTry))
    assertEquals(result, None)
    s2.tick()
    assertEquals(result, Some(Failure(dummy)))
  }

  test("Task.now.map should work") { implicit s =>
    check1 { a: Int =>
      Task.now(a).map(_ + 1) <-> Task.now(a + 1)
    }
  }

  test("Task.raiseError.map should be the same as Task.raiseError") { implicit s =>
    check {
      val dummy = DummyException("dummy")
      (Task.raiseError(dummy): Task[Throwable, Int]).map(_ + 1) <-> Task.raiseError(dummy)
    }
  }

  test("Task.raiseError.flatMap should be the same as Task.flatMap") { implicit s =>
    check {
      val dummy = DummyException("dummy")
      (Task.raiseError(dummy): Task[Throwable, Int]).flatMap(Task.now) <-> Task.raiseError(dummy)
    }
  }

  test("Task.raiseError.flatMap should be protected") { implicit s =>
    check {
      val dummy = DummyException("dummy")
      val err = DummyException("err")
      Task.raiseError(dummy).flatMap[Throwable, Int](_ => throw err) <-> Task.raiseError(dummy)
    }
  }

  test("Task.now.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = Task.now(1).flatMap[String, Int](_ => throw ex)
    check(t <-> Task.terminate(ex))
  }

  test("Task.now.flatMap should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): UIO[Int] =
      Task.now(idx).flatMap { _ =>
        if (idx < n) loop(n, idx + 1).map(_ + 1)
        else
          Task.now(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runToFuture

    s.tickOne()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  test("Task.now should not be cancelable") { implicit s =>
    val t = Task.now(10)
    val f = t.runToFuture
    f.cancel()
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("Task.raiseError should not be cancelable") { implicit s =>
    val dummy = 1453
    val t = Task.raiseError(dummy)
    val f = t.attempt.runToFuture
    f.cancel()
    s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("Task.terminate should not be cancelable") { implicit s =>
    val dummy = DummyException("dummy")
    val t = Task.terminate(dummy)
    val f = t.runToFuture
    f.cancel()
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.now.runSyncStep") { implicit s =>
    val result = Task.now(100).runSyncStep
    assertEquals(result, Right(100))
  }

  test("Task.raiseError.runSyncStep") { implicit s =>
    val dummy = 10000
    val result = Task.raiseError(dummy).attempt.runSyncStep
    assertEquals(result, Right(Left(dummy)))
  }
}
