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

import scala.util.{Failure, Success}

object TaskMaterializeSuite extends BaseTestSuite {
  test("Task.now.materialize") { implicit s =>
    assertEquals(Task.now(10).materialize.runSyncStep, Right(Success(10)))
  }

  test("Task.error.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(Task.raiseError(dummy).materialize.runToFuture.value, Some(Success(Failure(dummy))))
  }

  test("Task.terminate.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(Task.terminate(dummy).materialize.runToFuture.value, Some(Failure(dummy)))
  }

//  test("Task.evalOnce.materialize") { implicit s =>
//    assertEquals(Task.evalOnce(10).materialize.runSyncStep, Right(Success(Right(10))))
//  }

  test("Task.evalOnce.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task.Unsafe[Int] =
      if (n <= 0) Task.evalOnce(n)
      else
        Task.evalOnce(n).materialize.flatMap {
          case Success(_) => loop(n - 1)
          case Failure(ex) => Task.raiseError(ex)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture; s.tick()
    assertEquals(result.value, Some(Success(0)))
  }

  test("Task.eval.materialize") { implicit s =>
    assertEquals(Task.eval(10).materialize.runSyncStep, Right(Success(10)))
  }

  test("Task.defer.materialize") { implicit s =>
    assertEquals(Task.defer(Task.now(10)).materialize.runSyncStep, Right(Success(10)))
  }

  test("Task.defer.flatMap.materialize") { implicit s =>
    assertEquals(Task.defer(Task.now(10)).flatMap(Task.now).materialize.runSyncStep, Right(Success(10)))
  }

  test("Task.flatMap.materialize") { implicit s =>
    assertEquals(Task.eval(10).flatMap(x => Task.now(x)).materialize.runSyncStep, Right(Success(10)))
  }

  test("Task.evalAsync.materialize") { implicit s =>
    val f = UIO.evalAsync(10).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(10))))
  }

  test("Task.evalAsync.flatMap.materialize") { implicit s =>
    val f = Task.evalAsync(10).flatMap(Task.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(10))))
  }

  test("Task.evalAsync(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task[Int](throw dummy).flatMap(Task.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("Task.now.flatMap(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task.now(10).flatMap(_ => throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.defer(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task.defer[Int](throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("Task.deferTotal(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task.deferTotal(throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.defer(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task.defer[Int](throw dummy).flatMap(Task.now).materialize.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("Task.now.dematerialize") { implicit s =>
    val result = Task.now(10).materialize.dematerialize.runToFuture.value
    assertEquals(result, Some(Success(10)))
  }

  test("Task.error.dematerialize") { implicit s =>
    val dummy = DummyException("dummy")
    val result = Task.raiseError(dummy).materialize.dematerialize.attempt.runToFuture.value
    assertEquals(result, Some(Success(Left(dummy))))
  }

  test("Task.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task.Unsafe[Int] =
      if (n <= 0) Task.evalAsync(n)
      else
        Task.evalAsync(n).materialize.flatMap {
          case Success(_) => loop(n - 1)
          case Failure(e) => Task.raiseError(e)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(0)))
  }

  test("Task.eval.materialize should work for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task.eval[Int](throw dummy).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("Task.eval.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task.Unsafe[Int] =
      if (n <= 0) Task.eval(n)
      else
        Task.eval(n).materialize.flatMap {
          case Success(_) => loop(n - 1)
          case Failure(e) => Task.raiseError(e)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture; s.tick()
    assertEquals(result.value, Some(Success(0)))
  }

  test("Task.now.materialize should work") { implicit s =>
    val task = Task.now(1).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Success(1))))
  }

  test("Task.materialize on failing flatMap") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.now(1).flatMap { _ =>
      (throw ex): Task.Unsafe[Int]
    }
    val materialized = task.materialize.runToFuture
    assertEquals(materialized.value, Some(Failure(ex)))
  }

  test("Task.now.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task[Throwable, Int] =
      if (n <= 0) Task.now(n)
      else
        (Task.now(n): Task[Throwable, Int]).materialize.flatMap {
          case Success(v) => loop(v - 1)
          case Failure(ex) => Task.terminate(ex)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).attempt.runToFuture; s.tick()
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("Task.raiseError.dematerialize") { implicit s =>
    val ex = DummyException("dummy")
    val result = Task.raiseError(ex).materialize.dematerialize.attempt.runToFuture
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("Task.terminate.dematerialize") { implicit s =>
    val ex = DummyException("dummy")
    val result = Task.terminate(ex).materialize.dematerialize.runToFuture
    assertEquals(result.value, Some(Failure(ex)))
  }

}
