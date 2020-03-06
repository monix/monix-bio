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
  test("BIO#redeemCause should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): BIO[String, Int]).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO#redeemCause should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (BIO.raiseError(ex): BIO[String, Int]).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("BIO#redeemCause should recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.evalTotal[Int](if (1 == 1) throw ex else 1).redeemCause(_ => 99, identity)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("BIO#redeemCause should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = (BIO.terminate(ex1): BIO[String, Int]).redeemCause(_ => throw ex2, _ => throw ex2)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("BIO#redeemCauseWith derives flatMap") { implicit s =>
    check2 { (fa: BIO[String, Int], f: Int => BIO[String, Int]) =>
      fa.redeemCauseWith(_.fold(BIO.terminate, BIO.raiseError), f) <-> fa.flatMap(f)
    }
  }

  test("BIO#redeemCauseWith should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): BIO[String, Int]).redeemCauseWith(_ => BIO.now(99), BIO.now)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO#redeemCauseWith should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (BIO.raiseError(ex): BIO[String, Int]).redeemCauseWith(_ => BIO.now(99), BIO.now)

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("BIO#redeemCauseWith should recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.evalTotal[Int](if (1 == 1) throw ex else 1).redeemCauseWith(_ => BIO.now(99), BIO.now)

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("BIO#redeemCauseWith should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = (BIO.terminate(ex1): BIO[String, Int]).redeemCauseWith(_ => throw ex2, _ => throw ex2)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }
}
