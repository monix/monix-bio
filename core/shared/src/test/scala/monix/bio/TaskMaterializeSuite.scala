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
  test("BIO.now.materialize") { implicit s =>
    assertEquals(BIO.now(10).materialize.runSyncStep, Right(Success(Right(10))))
  }

  test("BIO.error.materialize") { implicit s =>
    val dummy = "dummy"
    assertEquals(BIO.raiseError(dummy).materialize.runToFuture.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.fatalError.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    assertEquals(BIO.raiseFatalError(dummy).materialize.runToFuture.value, Some(Success(Right(Failure(dummy)))))
  }

  //  test("Task.evalOnce.materialize") { implicit s =>
  //    assertEquals(Task.evalOnce(10).materialize.runSyncStep, Right(Success(10)))
  //  }
  //
  //  test("Task.evalOnce.materialize should be stack safe") { implicit s =>
  //    def loop(n: Int): Task[Int] =
  //      if (n <= 0) Task.evalOnce(n)
  //      else
  //        Task.evalOnce(n).materialize.flatMap {
  //          case Success(_) => loop(n - 1)
  //          case Failure(ex) => Task.raiseError(ex)
  //        }
  //
  //    val count = if (Platform.isJVM) 50000 else 5000
  //    val result = loop(count).runToFuture; s.tick()
  //    assertEquals(result.value, Some(Success(0)))
  //  }

  test("BIO.eval.materialize") { implicit s =>
    assertEquals(BIO.eval(10).materialize.runSyncStep, Right(Success(Right(10))))
  }

  test("BIO.defer.materialize") { implicit s =>
    assertEquals(BIO.defer(BIO.now(10)).materialize.runSyncStep, Right(Success(Right(10))))
  }

  test("BIO.defer.flatMap.materialize") { implicit s =>
    assertEquals(BIO.defer(BIO.now(10)).flatMap(BIO.now).materialize.runSyncStep, Right(Success(Right(10))))
  }

  test("BIO.flatMap.materialize") { implicit s =>
    assertEquals(BIO.eval(10).flatMap(x => BIO.now(x)).materialize.runSyncStep, Right(Success(Right(10))))
  }

  test("BIO.evalAsync.materialize") { implicit s =>
    val f = UIO.evalAsync(10).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(Right(10))))))
  }

  test("BIO.evalAsync.flatMap.materialize") { implicit s =>
    val f = BIO.evalAsync(10).flatMap(BIO.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(Right(10))))))
  }

  test("BIO.evalAsync(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO[Int](throw dummy).flatMap(BIO.now).materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.now.flatMap(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.now(10).flatMap(_ => throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Success(Right(Failure(dummy)))))
  }

  test("BIO.defer(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.defer[Int](throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.deferTotal(error).materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferTotal(throw dummy).materialize.runToFuture
    assertEquals(f.value, Some(Success(Right(Failure(dummy)))))
  }

  test("BIO.defer(error).flatMap.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.defer[Int](throw dummy).flatMap(BIO.now).materialize.runToFuture
    assertEquals(f.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.now.dematerialize") { implicit s =>
    val result = BIO.now(10).materialize.dematerialize.runToFuture.value
    assertEquals(result, Some(Success(Right(10))))
  }

  test("BIO.error.dematerialize") { implicit s =>
    val dummy = "dummy"
    val result = BIO.raiseError(dummy).materialize.dematerialize.runToFuture.value
    assertEquals(result, Some(Success(Left(dummy))))
  }

  test("BIO.materialize should be stack safe") { implicit s =>
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
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO.eval.materialize should work for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task.eval[Int](throw dummy).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.eval.materialize should be stack safe") { implicit s =>
    def loop(n: Int): Task[Int] =
      if (n <= 0) Task.eval(n)
      else
        Task.eval(n).materialize.flatMap {
          case Success(_) => loop(n - 1)
          case Failure(e) => Task.raiseError(e)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture; s.tick()
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO.now.materialize should work") { implicit s =>
    val task = BIO.now(1).materialize
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(Success(Right(1))))))
  }

  test("BIO.materialize on failing flatMap") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.now(1).flatMap { _ =>
      (throw ex): Task[Int]
    }
    val materialized = task.materialize.runToFuture
    assertEquals(materialized.value, Some(Success(Right(Failure(ex)))))
  }

  test("BIO.now.materialize should be stack safe") { implicit s =>
    def loop(n: Int): BIO[String, Int] =
      if (n <= 0) BIO.now(n)
      else
        (BIO.now(n): BIO[String, Int]).materialize.flatMap {
          case Success(Right(v)) => loop(v - 1)
          case Success(Left(ex)) => BIO.raiseError(ex)
          case Failure(ex) => BIO.raiseFatalError(ex)
        }

    val count = if (Platform.isJVM) 50000 else 5000
    val result = loop(count).runToFuture; s.tick()
    assertEquals(result.value, Some(Success(Right(0))))
  }

  test("BIO.raiseError.dematerialize") { implicit s =>
    val ex = "dummy"
    val result = BIO.raiseError(ex).materialize.dematerialize.runToFuture
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("BIO.raiseFatalError.dematerialize") { implicit s =>
    val ex = DummyException("dummy")
    val result = BIO.raiseFatalError(ex).materialize.dematerialize.runToFuture
    assertEquals(result.value, Some(Failure(ex)))
  }

}
