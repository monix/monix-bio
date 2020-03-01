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

import cats.Eval
import cats.effect.{ContextShift, IO, SyncIO}
import cats.laws._
import cats.laws.discipline._
import cats.syntax.all._
import monix.bio.TaskConversionsSuite.{CIO, CustomConcurrentEffect, CustomEffect}
import monix.catnap.SchedulerEffect
import monix.execution.CancelablePromise
import monix.execution.exceptions.DummyException

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object TaskLikeConversionsSuite extends BaseTestSuite {

  test("BIO.from(task.to[IO]) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.from(task.to[IO]) <-> task
    }
  }

  test("BIO.from converts successful Future") { implicit s =>
    val p = Promise[Int]()
    val f = BIO.from(p.future).runToFuture

    s.tick()
    assertEquals(f.value, None)

    p.success(123)
    s.tick()
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed Future") { implicit s =>
    val p = Promise[Int]()
    val ex = DummyException("dummy")
    val f = BIO.from(p.future).runToFuture

    s.tick()
    assertEquals(f.value, None)

    p.failure(ex)
    s.tick()
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts successful sync IO") { implicit s =>
    val task = BIO.from(IO(123))
    assertEquals(task.runToFuture.value, Some(Success(Right(123))))
  }

  test("BIO.from converts successful async IO") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val io = IO.shift >> IO(123)
    val task = BIO.from(io)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed sync IO") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.from(IO.raiseError(ex))
    assertEquals(task.runToFuture.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts failed async IO") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val ex = DummyException("dummy")
    val io = IO.shift >> IO.raiseError(ex)
    val task = BIO.from(io)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from preserves cancellability of an IO") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val timer = SchedulerEffect.timer[IO](s)
    val io = timer.sleep(10.seconds)
    val f = BIO.from(io).runToFuture

    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    assertEquals(f.value, None)

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.from converts successful Eval") { implicit s =>
    var executed = false
    val source = Eval.always { executed = true; 123 }
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed Eval") { implicit s =>
    var executed = false
    val ex = DummyException("dummy")
    val source = Eval.always[Int] { executed = true; throw ex }
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from preserves Eval laziness") { implicit s =>
    var result = 0
    val task = BIO.from(Eval.always { result += 1; result })

    assertEquals(task.runToFuture.value, Some(Success(Right(1))))
    assertEquals(task.runToFuture.value, Some(Success(Right(2))))
    assertEquals(task.runToFuture.value, Some(Success(Right(3))))
  }

  test("BIO.from converts successful SyncIO") { implicit s =>
    var executed = false
    val source = SyncIO { executed = true; 123 }
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed SyncIO") { implicit s =>
    var executed = false
    val ex = DummyException("dummy")
    val source = SyncIO.suspend[Int] { executed = true; SyncIO.raiseError(ex) }
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts successful Try") { implicit s =>
    val source: Try[Int] = Success(123)
    val task = BIO.from(source)
    assertEquals(task.runToFuture.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed Try") { implicit s =>
    val ex = DummyException("dummy")
    val source: Try[Int] = Failure(ex)
    val task = BIO.from(source)
    assertEquals(task.runToFuture.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts right Either") { implicit s =>
    val source = 123.asRight[Throwable]
    val task = BIO.from(source)
    assertEquals(task.runToFuture.value, Some(Success(Right(123))))
  }

  test("BIO.from converts left Either") { implicit s =>
    val ex = DummyException("dummy")
    val source = ex.asLeft[Int]
    val task = BIO.from(source)
    assertEquals(task.runToFuture.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts successful custom Effect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomEffect = new CustomEffect()

    var executed = false
    val source = CIO(IO { executed = true; 123 })
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed custom Effect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomEffect = new CustomEffect()

    var executed = false
    val ex = DummyException("dummy")
    val source = CIO(IO { executed = true; throw ex })
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts successful custom ConcurrentEffect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomConcurrentEffect = new CustomConcurrentEffect()

    var executed = false
    val source = CIO(IO { executed = true; 123 })
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts failed custom ConcurrentEffect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomConcurrentEffect = new CustomConcurrentEffect()

    var executed = false
    val ex = DummyException("dummy")
    val source = CIO(IO { executed = true; throw ex })
    val task = BIO.from(source)

    assert(!executed)
    val f = task.runToFuture
    assert(executed)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.from converts Function0") { implicit s =>
    val task = BIO.from(() => 123)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(123))))
  }

  test("BIO.from converts CancelablePromise") { implicit s =>
    val p = CancelablePromise[Int]()
    val task = BIO.from(p)

    val token1 = task.runToFuture
    val token2 = task.runToFuture

    assertEquals(token1.value, None)
    assertEquals(token2.value, None)

    token1.cancel()
    p.success(123)
    assertEquals(token1.value, None)
    assertEquals(token2.value, Some(Success(Right(123))))

    val token3 = task.runToFuture
    assertEquals(token3.value, Some(Success(Right(123))))
  }

}
