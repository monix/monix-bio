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

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskToFutureSuite extends BaseTestSuite {
  test("BIO.fromFuture for already completed references") { implicit s =>
    def sum(list: List[Int]): BIO.Unsafe[Int] =
      BIO.fromFuture(Future.successful(list.sum))

    val f = sum((0 until 100).toList).runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(99 * 50)))
  }

  test("BIO.deferFuture for already completed references") { implicit s =>
    def sum(list: List[Int]): BIO.Unsafe[Int] =
      BIO.deferFuture(Future.successful(list.sum))

    val f = sum((0 until 100).toList).runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(99 * 50)))
  }

  test("BIO.deferFutureAction for already completed references") { implicit s =>
    def sum(list: List[Int]): BIO.Unsafe[Int] =
      BIO.deferFutureAction(implicit s => Future.successful(list.sum))

    val f = sum((0 until 100).toList).runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(99 * 50)))
  }

  test("BIO.fromFuture(error) for already completed references") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.fromFuture(Future.failed(dummy)).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFuture(error) for already completed references") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFuture(Future.failed(dummy)).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFutureAction(typed) for already completed references") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFutureAction(_ => Future.failed(dummy)).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFuture for completed reference is stack safe (flatMap)") { implicit s =>
    def loop(n: Int, acc: Int): BIO.Unsafe[Int] =
      if (n > 0)
        BIO.fromFuture(Future.successful(acc + 1)).flatMap(loop(n - 1, _))
      else
        BIO.fromFuture(Future.successful(acc))

    val f = loop(10000, 0).runToFuture; s.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("BIO.deferFuture for completed reference is stack safe (flatMap)") { implicit s =>
    def loop(n: Int, acc: Int): BIO.Unsafe[Int] =
      if (n > 0)
        BIO.deferFuture(Future.successful(acc + 1)).flatMap(loop(n - 1, _))
      else
        BIO.deferFuture(Future.successful(acc))

    val f = loop(10000, 0).runToFuture; s.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("BIO.deferFutureAction for completed reference is stack safe (flatMap)") { implicit s =>
    def loop(n: Int, acc: Int): BIO.Unsafe[Int] =
      if (n > 0)
        BIO
          .deferFutureAction(implicit s => Future.successful(acc + 1))
          .flatMap(loop(n - 1, _))
      else
        BIO.deferFutureAction(implicit s => Future.successful(acc))

    val f = loop(10000, 0).runToFuture; s.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("BIO.fromFuture async result") { implicit s =>
    val f = BIO.fromFuture(Future(1)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.fromFuture(error) async result") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.fromFuture(Future(throw dummy)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFuture async result") { implicit s =>
    val f = BIO.deferFuture(Future(1)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.deferFuture(error) async result") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFuture(Future(throw dummy)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFuture(throw error) async result") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFuture(throw dummy).runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFutureAction async result") { implicit s =>
    val f = BIO.deferFutureAction(implicit s => Future(1)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.deferFutureAction(error) async result") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFutureAction(implicit s => Future(throw dummy)).runToFuture
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferFutureAction(throw error) async result") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.deferFutureAction(_ => throw dummy).runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFuture(cancelable)") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.fromFuture(f1).runToFuture

    assertEquals(f2.value, None)
    s.tick(1.second)
    assertEquals(f2.value, Some(Success(1)))
  }

  test("BIO.fromFuture(cancelable) is cancelable") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.fromFuture(f1).runToFuture

    assertEquals(f2.value, None)
    f2.cancel()

    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    s.tick(1.second)
    assertEquals(f2.value, None)
  }

  test("BIO.deferFuture(cancelable)") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.deferFuture(f1).runToFuture

    assertEquals(f2.value, None)
    s.tick(1.second)
    assertEquals(f2.value, Some(Success(1)))
  }

  test("BIO.deferFuture(cancelable) is cancelable") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.deferFuture(f1).runToFuture

    assertEquals(f2.value, None)
    f2.cancel()

    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    s.tick(1.second)
    assertEquals(f2.value, None)
  }

  test("BIO.deferFutureAction(cancelable)") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.deferFutureAction(implicit s => f1).runToFuture

    assertEquals(f2.value, None)
    s.tick(1.second)
    assertEquals(f2.value, Some(Success(1)))
  }

  test("BIO.deferFutureAction(cancelable) is cancelable") { implicit s =>
    val f1 = BIO.eval(1).delayExecution(1.second).runToFuture
    val f2 = BIO.deferFutureAction(implicit s => f1).runToFuture

    assertEquals(f2.value, None)
    f2.cancel()

    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    s.tick(1.second)
    assertEquals(f2.value, None)
  }
}
