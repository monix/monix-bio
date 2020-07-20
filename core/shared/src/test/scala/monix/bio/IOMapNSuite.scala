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

object IOMapNSuite extends BaseTestSuite {
  test("IO#map2 should yield value successfully") { implicit s =>
    val bio1 = IO.now(1).delayExecution(1.millisecond)
    val bio2 = IO.now(2).delayExecution(2.millisecond)
    val map2IO = IO.map2(bio1, bio2)(_ + _)

    val bioExec = map2IO.runToFuture

    s.tick(1.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(2.millisecond)
    assertEquals(bioExec.value, Some(Success(3)))
  }

  test("IO#map2 should propagate error when one argument failed") { implicit s =>
    val bio1 = IO.fromEither[String, Int](Left("1st Step Failure")).delayExecution(1.millisecond)
    val bio2 = IO((throw DummyException("2nd Step Failure")): Int)
    val map2IO = IO.map2(bio1, bio2)(_ + _)

    val bioExec = map2IO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("1st Step Failure"))))
  }

  test("IO#map2 should protect against user code") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(20).delayExecution(2.millisecond)
    val dummy = DummyException("dummy")
    val map2IO = IO.map2(bio1, bio2)((_, _) => (throw dummy): Int)

    val bioExec = map2IO.runToFuture

    s.tick(10.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("IO#map2 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = IO.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = IO.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    IO.map2(bio1, bio2)((_, _) => ()).runToFuture
    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
  }

  test("IO#map3 should yield value successfully") { implicit s =>
    val bio1 = IO.now(1).delayExecution(1.millisecond)
    val bio2 = IO.now(2).delayExecution(2.millisecond)
    val bio3 = IO.now(3).delayExecution(3.millisecond)
    val map3IO = IO.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3IO.runToFuture

    s.tick(3.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(6)))
  }

  test("IO#map3 should propagate error when one argument failed") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = IO((throw DummyException("3rd Step Failure")): Int)
    val map3IO = IO.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3IO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("IO#map3 should protect against user code") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(20).delayExecution(2.millisecond)
    val bio3 = IO.now(5).delayExecution(3.millisecond)
    val dummy = DummyException("dummy")
    val map3IO = IO.map3(bio1, bio2, bio3)((_, _, _) => (throw dummy): Int)

    val bioExec = map3IO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("IO#map3 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = IO.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = IO.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = IO.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    IO.map3(bio1, bio2, bio3)((_, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect, 0)
    s.tick(1.millisecond)
    assertEquals(effect, 1)
    s.tick(1.millisecond)
    assertEquals(effect, 11)
    s.tick(1.millisecond)
    assertEquals(effect, 111)
  }

  test("IO#map4 should yield value successfully") { implicit s =>
    val bio1 = IO.now(1).delayExecution(1.millisecond)
    val bio2 = IO.now(2).delayExecution(1.millisecond)
    val bio3 = IO.now(3).delayExecution(1.millisecond)
    val bio4 = IO.now(4).delayExecution(2.millisecond)
    val map4IO = IO.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4IO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(10)))
  }

  test("IO#map4 should propagate error when one argument failed") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(5).delayExecution(1.millisecond)
    val bio3 = IO.fromEither[String, Int](Left("3rd Step Failure")).delayExecution(1.millisecond)
    val bio4 = IO((throw DummyException("4th Step Failure")): Int)
    val map4IO = IO.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4IO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("3rd Step Failure"))))
  }

  test("IO#map4 should protect against user code") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(20).delayExecution(1.millisecond)
    val bio3 = IO.now(5).delayExecution(1.millisecond)
    val bio4 = IO.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map4IO = IO.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => (throw dummy): Int)

    val bioExec = map4IO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("IO#map4 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = IO.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = IO.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = IO.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = IO.evalAsync { effect += 1000 }.delayExecution(1.millisecond)

    IO.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => ()).runToFuture

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

  test("IO#map5 should yield value successfully") { implicit s =>
    val bio1 = IO.now(1).delayExecution(1.millisecond)
    val bio2 = IO.now(2).delayExecution(1.millisecond)
    val bio3 = IO.now(3).delayExecution(1.millisecond)
    val bio4 = IO.now(4).delayExecution(1.millisecond)
    val bio5 = IO.now(5).delayExecution(1.millisecond)
    val map5IO = IO.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5IO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(15)))
  }

  test("IO#map5 should propagate error when one argument failed") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = IO.now(5).delayExecution(1.millisecond)
    val bio4 = IO((throw DummyException("4th Step Failure")): Int)
    val bio5 = IO.now(10)
    val map5IO = IO.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5IO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("IO#map5 should protect against user code") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(20).delayExecution(1.millisecond)
    val bio3 = IO.now(5).delayExecution(1.millisecond)
    val bio4 = IO.now(15).delayExecution(1.millisecond)
    val bio5 = IO.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map5IO = IO.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => (throw dummy): Int)

    val bioExec = map5IO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("IO#map5 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = IO.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = IO.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = IO.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = IO.evalAsync { effect += 1000 }.delayExecution(1.millisecond)
    val bio5 = IO.evalAsync { effect += 10000 }.delayExecution(1.millisecond)

    IO.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => ()).runToFuture

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

  test("IO#map6 should yield value successfully") { implicit s =>
    val bio1 = IO.now(1).delayExecution(1.millisecond)
    val bio2 = IO.now(2).delayExecution(1.millisecond)
    val bio3 = IO.now(3).delayExecution(1.millisecond)
    val bio4 = IO.now(4).delayExecution(1.millisecond)
    val bio5 = IO.now(5).delayExecution(1.millisecond)
    val bio6 = IO.now(6).delayExecution(1.millisecond)

    val map6IO = IO.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6IO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(21)))
  }

  test("IO#map6 should propagate error when one argument failed") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = IO.now(5).delayExecution(1.millisecond)
    val bio4 = IO((throw DummyException("4th Step Failure")): Int)
    val bio5 = IO.now(10)
    val bio6 = IO.now(20)

    val map6IO = IO.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6IO.attempt.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("IO#map6 should protect against user code") { implicit s =>
    val bio1 = IO.now(10).delayExecution(1.millisecond)
    val bio2 = IO.now(20).delayExecution(1.millisecond)
    val bio3 = IO.now(5).delayExecution(1.millisecond)
    val bio4 = IO.now(15).delayExecution(1.millisecond)
    val bio5 = IO.now(15).delayExecution(1.millisecond)
    val bio6 = IO.now(25).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map6IO = IO.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => (throw dummy): Int)

    val bioExec = map6IO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("IO#map6 should run effects in strict sequence") { implicit s =>
    var effect = 0
    val bio1 = IO.evalAsync { effect += 1 }.delayExecution(1.millisecond)
    val bio2 = IO.evalAsync { effect += 10 }.delayExecution(1.millisecond)
    val bio3 = IO.evalAsync { effect += 100 }.delayExecution(1.millisecond)
    val bio4 = IO.evalAsync { effect += 1000 }.delayExecution(1.millisecond)
    val bio5 = IO.evalAsync { effect += 10000 }.delayExecution(1.millisecond)
    val bio6 = IO.evalAsync { effect += 100000 }.delayExecution(1.millisecond)

    IO.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => ()).runToFuture

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
