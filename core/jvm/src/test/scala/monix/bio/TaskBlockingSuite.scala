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
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

import scala.concurrent.duration._
import scala.concurrent.{Await, TimeoutException}
import scala.util.{Failure, Try}

object TaskBlockingSuite extends SimpleTestSuite {
  test("blocking on future should work") {
    val source1 = Task.evalAsync(100)
    val source2 = Task.evalAsync(200).onErrorHandleWith { case e: Exception => Task.raiseError(e) }

    val derived = source1.map { x =>
      val r = Await.result(source2.runToFuture, 10.seconds)
      r + x
    }

    val result = Await.result(derived.runToFuture, 10.seconds)
    assertEquals(result, 300)
  }

  test("blocking on async") {
    for (_ <- 0 until 1000) {
      val task = Task.evalAsync(1)
      assertEquals(task.runSyncUnsafe(Duration.Inf), 1)
    }
  }

  test("blocking on async.flatMap") {
    for (_ <- 0 until 1000) {
      val task = Task.evalAsync(1).flatMap(_ => Task.evalAsync(2))
      assertEquals(task.runSyncUnsafe(Duration.Inf), 2)
    }
  }

  test("blocking with Termination result") {
    val typed: IO[Int, Unit] = IO.terminate(DummyException("dummy"))
    val result = typed
      .redeemCauseWith(
        recover = {
          case Cause.Error(value) => IO.raiseError(value)
          case Cause.Termination(value) => IO.terminate(value)
        },
        bind = _ => IO.unit
      )
      .attempt
      .void
    val throwable = intercept[DummyException](result.runSyncUnsafe())
    assert(throwable.getMessage == "dummy")
  }

  test("IO.eval(throw ex).attempt.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task = IO.eval(throw dummy)
      assertEquals(task.attempt.runSyncUnsafe(Duration.Inf), Left(dummy))
    }
  }

  test("IO.evalTotal(throw ex).onErrorHandle.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task: IO[Int, Int] = IO.evalTotal(throw dummy)
      assertEquals(Try(task.onErrorHandle(_ => 10).runSyncUnsafe(Duration.Inf)), Failure(dummy))
    }
  }

  test("IO.suspend(throw ex).attempt.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task = IO.suspend(throw dummy)
      assertEquals(task.attempt.runSyncUnsafe(Duration.Inf), Left(dummy))
    }
  }

  test("IO.suspendTotal(throw ex).onErrorHandle.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task: IO[Int, Int] = IO.suspendTotal(throw dummy)
      assertEquals(Try(task.onErrorHandle(_ => 10).runSyncUnsafe(Duration.Inf)), Failure(dummy))
    }
  }

  test("blocking on memoize") {
    for (_ <- 0 until 1000) {
      val task = Task.evalAsync(1).flatMap(_ => Task.evalAsync(2)).memoize
      assertEquals(task.runSyncUnsafe(Duration.Inf), 2)
      assertEquals(task.runSyncUnsafe(Duration.Inf), 2)
    }
  }

  test("timeout exception") {
    intercept[TimeoutException] {
      Task.never.runSyncUnsafe(100.millis)
    }
    ()
  }

  test("IO.attempt.runSyncUnsafe works") {
    val dummy = "boom"
    val dummyEx = DummyException(dummy)
    val task: IO[String, Int] = IO.raiseError(dummy)
    val task2: IO[String, Int] = IO.terminate(dummyEx)

    assertEquals(task.attempt.runSyncUnsafe(Duration.Inf), Left(dummy))

    intercept[DummyException] {
      task.hideErrorsWith(DummyException).runSyncUnsafe(Duration.Inf)
    }

    intercept[DummyException] {
      task2.attempt.runSyncUnsafe(Duration.Inf)
    }
    ()

  }
}
