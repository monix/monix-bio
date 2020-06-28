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
import concurrent.duration._
import scala.util.Failure

import scala.util.Success

object BIOMapNSuite extends BaseTestSuite {
  test("Task#map2 should yield value successfully") { implicit s =>
    val bio1 = Task.now(1).delayExecution(1.millisecond)
    val bio2 = Task.now(2).delayExecution(2.millisecond)
    val map2BIO = Task.map2(bio1, bio2)(_ + _)

    val bioExec = map2BIO.runToFuture

    s.tick(1.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(2.millisecond)
    assertEquals(bioExec.value, Some(Success(3)))
  }

  test("Task#map2 should propagate error when one argument failed") { implicit s =>
    val bio1 = Task.fromEither[String, Int](Left("1st Step Failure")).delayExecution(1.millisecond)
    val bio2 = Task((throw DummyException("2nd Step Failure")): Int)
    val map2BIO = Task.map2(bio1, bio2)(_ + _)

    val bioExec = map2BIO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("1st Step Failure"))))
  }

  test("Task#map2 should protect against user code") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(20).delayExecution(2.millisecond)
    val dummy = DummyException("dummy")
    val map2BIO = Task.map2(bio1, bio2)((_, _) => (throw dummy): Int)

    val bioExec = map2BIO.runToFuture

    s.tick(10.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("Task#map2 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = Task.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = Task.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    Task.map2(bio1, bio2)((_, _) => ()).runToFuture
    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
  }

  test("Task#map3 should yield value successfully") { implicit s =>
    val bio1 = Task.now(1).delayExecution(1.millisecond)
    val bio2 = Task.now(2).delayExecution(2.millisecond)
    val bio3 = Task.now(3).delayExecution(3.millisecond)
    val map3BIO = Task.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3BIO.runToFuture

    s.tick(3.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(6)))
  }

  test("Task#map3 should propagate error when one argument failed") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = Task((throw DummyException("3rd Step Failure")): Int)
    val map3BIO = Task.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3BIO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("Task#map3 should protect against user code") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(20).delayExecution(2.millisecond)
    val bio3 = Task.now(5).delayExecution(3.millisecond)
    val dummy = DummyException("dummy")
    val map3BIO = Task.map3(bio1, bio2, bio3)((_, _, _) => (throw dummy): Int)

    val bioExec = map3BIO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("Task#map3 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = Task.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = Task.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = Task.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    Task.map3(bio1, bio2, bio3)((_, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
    s.tick(1.millisecond)
    assertEquals(effect, 111)
  }

  test("Task#map4 should yield value successfully") { implicit s =>
    val bio1 = Task.now(1).delayExecution(1.millisecond)
    val bio2 = Task.now(2).delayExecution(1.millisecond)
    val bio3 = Task.now(3).delayExecution(1.millisecond)
    val bio4 = Task.now(4).delayExecution(2.millisecond)
    val map4BIO = Task.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(10)))
  }

  test("Task#map4 should propagate error when one argument failed") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(5).delayExecution(1.millisecond)
    val bio3 = Task.fromEither[String, Int](Left("3rd Step Failure")).delayExecution(1.millisecond)
    val bio4 = Task((throw DummyException("4th Step Failure")): Int)
    val map4BIO = Task.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4BIO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("3rd Step Failure"))))
  }

  test("Task#map4 should protect against user code") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(20).delayExecution(1.millisecond)
    val bio3 = Task.now(5).delayExecution(1.millisecond)
    val bio4 = Task.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map4BIO = Task.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => (throw dummy): Int)

    val bioExec = map4BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("Task#map4 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = Task.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = Task.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = Task.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = Task.evalAsync { effect += 1000 }.delayExecution(1.millisecond)

    Task.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
    s.tick(1.millisecond)
    assertEquals(effect, 111)
    s.tick(1.millisecond)
    assertEquals(effect, 1111)
  }

  test("Task#map5 should yield value successfully") { implicit s =>
    val bio1 = Task.now(1).delayExecution(1.millisecond)
    val bio2 = Task.now(2).delayExecution(1.millisecond)
    val bio3 = Task.now(3).delayExecution(1.millisecond)
    val bio4 = Task.now(4).delayExecution(1.millisecond)
    val bio5 = Task.now(5).delayExecution(1.millisecond)
    val map5BIO = Task.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(15)))
  }

  test("Task#map5 should propagate error when one argument failed") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = Task.now(5).delayExecution(1.millisecond)
    val bio4 = Task((throw DummyException("4th Step Failure")): Int)
    val bio5 = Task.now(10)
    val map5BIO = Task.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5BIO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("Task#map5 should protect against user code") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(20).delayExecution(1.millisecond)
    val bio3 = Task.now(5).delayExecution(1.millisecond)
    val bio4 = Task.now(15).delayExecution(1.millisecond)
    val bio5 = Task.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map5BIO = Task.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => (throw dummy): Int)

    val bioExec = map5BIO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("Task#map5 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = Task.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = Task.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = Task.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = Task.evalAsync { effect += 1000 }.delayExecution(1.millisecond)
    val bio5 = Task.evalAsync { effect += 10000 }.delayExecution(1.millisecond)

    Task.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
    s.tick(1.millisecond)
    assertEquals(effect, 111)
    s.tick(1.millisecond)
    assertEquals(effect, 1111)
    s.tick(1.millisecond)
    assertEquals(effect, 11111)
  }

  test("Task#map6 should yield value successfully") { implicit s =>
    val bio1 = Task.now(1).delayExecution(1.millisecond)
    val bio2 = Task.now(2).delayExecution(1.millisecond)
    val bio3 = Task.now(3).delayExecution(1.millisecond)
    val bio4 = Task.now(4).delayExecution(1.millisecond)
    val bio5 = Task.now(5).delayExecution(1.millisecond)
    val bio6 = Task.now(6).delayExecution(1.millisecond)

    val map6BIO = Task.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6BIO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(21)))
  }

  test("Task#map6 should propagate error when one argument failed") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = Task.now(5).delayExecution(1.millisecond)
    val bio4 = Task((throw DummyException("4th Step Failure")): Int)
    val bio5 = Task.now(10)
    val bio6 = Task.now(20)

    val map6BIO = Task.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6BIO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("Task#map6 should protect against user code") { implicit s =>
    val bio1 = Task.now(10).delayExecution(1.millisecond)
    val bio2 = Task.now(20).delayExecution(1.millisecond)
    val bio3 = Task.now(5).delayExecution(1.millisecond)
    val bio4 = Task.now(15).delayExecution(1.millisecond)
    val bio5 = Task.now(15).delayExecution(1.millisecond)
    val bio6 = Task.now(25).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map6BIO = Task.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => (throw dummy): Int)

    val bioExec = map6BIO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("Task#map6 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = Task.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = Task.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = Task.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = Task.evalAsync { effect += 1000 }.delayExecution(1.millisecond)
    val bio5 = Task.evalAsync { effect += 10000 }.delayExecution(1.millisecond)
    val bio6 = Task.evalAsync { effect += 100000 }.delayExecution(1.millisecond)

    Task.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
    s.tick(1.millisecond)
    assertEquals(effect, 111)
    s.tick(1.millisecond)
    assertEquals(effect, 1111)
    s.tick(1.millisecond)
    assertEquals(effect, 11111)
    s.tick(1.millisecond)
    assertEquals(effect, 111111)
  }
}
