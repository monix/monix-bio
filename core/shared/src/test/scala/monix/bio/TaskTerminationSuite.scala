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
import cats.laws._
import cats.laws.discipline._
import scala.util.{Failure, Success}

object TaskTerminationSuite extends BaseTestSuite {
  test("IO#redeemCause should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): IO[String, Int]).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO#redeemCause should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (IO.raiseError(ex): IO[String, Int]).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("IO#redeemCause should recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.evalTotal[Int](if (1 == 1) throw ex else 1).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("IO#redeemCause should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = (IO.terminate(ex1): IO[String, Int]).redeemCause(_ => throw ex2, _ => throw ex2)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("IO#redeemCauseWith derives flatMap") { implicit s =>
    check2 { (fa: IO[String, Int], f: Int => IO[String, Int]) =>
      fa.redeemCauseWith(_.fold(IO.terminate, IO.raiseError), f) <-> fa.flatMap(f)
    }
  }

  test("IO#redeemCauseWith should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): IO[String, Int]).redeemCauseWith(_ => IO.now(99), IO.now)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO#redeemCauseWith should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (IO.raiseError(ex): IO[String, Int]).redeemCauseWith(_ => IO.now(99), IO.now)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("IO#redeemCauseWith should recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.evalTotal[Int](if (1 == 1) throw ex else 1).redeemCauseWith(_ => IO.now(99), IO.now)

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("IO#redeemCauseWith should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = (IO.terminate(ex1): IO[String, Int]).redeemCauseWith(_ => throw ex2, _ => throw ex2)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }
}
