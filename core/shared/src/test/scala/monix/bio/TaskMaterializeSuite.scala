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
  test("IO.now.materialize") { implicit s =>
    assertEquals(IO.now(10).materialize.runSyncStep, Right(Success(10)))
  }

  test("IO.error.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(IO.raiseError(dummy).materialize.runToFuture.value, Some(Success(Failure(dummy))))
  }

  test("IO.terminate.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(IO.terminate(dummy).materialize.runToFuture.value, Some(Failure(dummy)))
  }

//  test("IO.evalOnce.materialize") { implicit s =>
//    assertEquals(IO.evalOnce(10).materialize.runSyncStep, Right(Success(Right(10))))
//  }

  test("IO.evalOnce.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task[Int] =
      if (n <= 0) IO.evalOnce(n)
      else
        IO.evalOnce(n).materialize.flatMap {
          case Success(_) => loop(n - 1)
          case Failure(ex) => IO.raiseError(ex)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture; s.tick()
    assertEquals(result.value, Some(Success(0)))
  }

  test("IO.eval.materialize") { implicit s =>
    assertEquals(IO.eval(10).materialize.runSyncStep, Right(Success(10)))
  }

  test("IO.defer.materialize") { implicit s =>
    assertEquals(IO.defer(IO.now(10)).materialize.runSyncStep, Right(Success(10)))
  }

  test("IO.defer.flatMap.materialize") { implicit s =>
    assertEquals(IO.defer(IO.now(10)).flatMap(IO.now).materialize.runSyncStep, Right(Success(10)))
  }

  test("IO.flatMap.materialize") { implicit s =>
    assertEquals(IO.eval(10).flatMap(x => IO.now(x)).materialize.runSyncStep, Right(Success(10)))
  }

  test("IO.evalAsync.materialize") { implicit s =>
    val f = UIO.evalAsync(10).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(10))))
  }

  test("IO.evalAsync.flatMap.materialize") { implicit s =>
    val f = IO.evalAsync(10).flatMap(IO.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(10))))
  }

  test("IO.evalAsync(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO[Int](throw dummy).flatMap(IO.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("IO.now.flatMap(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.now(10).flatMap(_ => throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.defer(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.defer[Int](throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("IO.deferTotal(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.deferTotal(throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.defer(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.defer[Int](throw dummy).flatMap(IO.now).materialize.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("IO.now.dematerialize") { implicit s =>
    val result = IO.now(10).materialize.dematerialize.runToFuture.value
    assertEquals(result, Some(Success(10)))
  }

  test("IO.error.dematerialize") { implicit s =>
    val dummy = DummyException("dummy")
    val result = IO.raiseError(dummy).materialize.dematerialize.attempt.runToFuture.value
    assertEquals(result, Some(Success(Left(dummy))))
  }

  test("IO.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task[Int] =
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

  test("IO.eval.materialize should work for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task.eval[Int](throw dummy).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("IO.eval.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task[Int] =
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

  test("IO.now.materialize should work") { implicit s =>
    val task = IO.now(1).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Success(1))))
  }

  test("IO.materialize on failing flatMap") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.now(1).flatMap { _ =>
      (throw ex): Task[Int]
    }
    val materialized = task.materialize.runToFuture
    assertEquals(materialized.value, Some(Failure(ex)))
  }

  test("IO.now.materialize should be stack safe") { implicit s =>
    def loop(n: Int): IO[Throwable, Int] =
      if (n <= 0) IO.now(n)
      else
        (IO.now(n): IO[Throwable, Int]).materialize.flatMap {
          case Success(v) => loop(v - 1)
          case Failure(ex) => IO.terminate(ex)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).attempt.runToFuture; s.tick()
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("IO.raiseError.dematerialize") { implicit s =>
    val ex = DummyException("dummy")
    val result = IO.raiseError(ex).materialize.dematerialize.attempt.runToFuture
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("IO.terminate.dematerialize") { implicit s =>
    val ex = DummyException("dummy")
    val result = IO.terminate(ex).materialize.dematerialize.runToFuture
    assertEquals(result.value, Some(Failure(ex)))
  }

}
