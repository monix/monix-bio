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

  test("BIO.eval(throw ex).attempt.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task = BIO.eval(throw dummy)
      assertEquals(task.attempt.runSyncUnsafe(Duration.Inf), Left(dummy))
    }
  }

  test("BIO.evalTotal(throw ex).onErrorHandle.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task: BIO[Int, Int] = BIO.evalTotal(throw dummy)
      assertEquals(Try(task.onErrorHandle(_ => 10).runSyncUnsafe(Duration.Inf)), Failure(dummy))
    }
  }

  test("BIO.suspend(throw ex).attempt.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task = BIO.suspend(throw dummy)
      assertEquals(task.attempt.runSyncUnsafe(Duration.Inf), Left(dummy))
    }
  }

  test("BIO.suspendTotal(throw ex).onErrorHandle.runSyncUnsafe") {
    for (_ <- 0 until 1000) {
      val dummy = DummyException("dummy")
      val task: BIO[Int, Int] = BIO.suspendTotal(throw dummy)
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
  }
}
