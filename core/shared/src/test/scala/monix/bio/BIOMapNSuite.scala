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
  test("BIO#map2 should yield value successfully") { implicit s =>
    val bio1 = BIO.now(1).delayExecution(1.millisecond)
    val bio2 = BIO.now(2).delayExecution(2.millisecond)
    val map2BIO = BIO.map2(bio1, bio2)(_ + _)

    val bioExec = map2BIO.runToFuture

    s.tick(1.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(2.millisecond)
    assertEquals(bioExec.value, Some(Success(Right(3))))
  }

  test("BIO#map2 should propagate error when one argument failed") { implicit s =>
    val bio1 = BIO.fromEither[String, Int](Left("1st Step Failure")).delayExecution(1.millisecond)
    val bio2 = BIO((throw DummyException("2nd Step Failure")) : Int)
    val map2BIO = BIO.map2(bio1, bio2)(_ + _)

    val bioExec = map2BIO.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(2.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("1st Step Failure"))))
  }

  test("BIO#map2 should protect against user code") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(20).delayExecution(2.millisecond)
    val dummy = DummyException("dummy")
    val map2BIO = BIO.map2(bio1, bio2)((_, _) => (throw dummy): Int)

    val bioExec = map2BIO.runToFuture

    s.tick(10.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("BIO#map2 should run effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    val bio1 = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val bio2 = BIO.evalAsync { effect2 += 2 }.delayExecution(1.millisecond)
    BIO.map2(bio1, bio2)((_, _) => ()).runToFuture
    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
  }

  test("BIO#map3 should yield value successfully") { implicit s =>
    val bio1 = BIO.now(1).delayExecution(1.millisecond)
    val bio2 = BIO.now(2).delayExecution(2.millisecond)
    val bio3 = BIO.now(3).delayExecution(3.millisecond)
    val map3BIO = BIO.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3BIO.runToFuture

    s.tick(3.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Right(6))))
  }

  test("BIO#map3 should propagate error when one argument failed") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = BIO((throw DummyException("3rd Step Failure")) : Int)
    val map3BIO = BIO.map3(bio1, bio2, bio3)(_ + _ + _)

    val bioExec = map3BIO.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("BIO#map3 should protect against user code") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(20).delayExecution(2.millisecond)
    val bio3 = BIO.now(5).delayExecution(3.millisecond)
    val dummy = DummyException("dummy")
    val map3BIO = BIO.map3(bio1, bio2, bio3)((_, _, _) => (throw dummy): Int)

    val bioExec = map3BIO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("BIO#map3 should run effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0
    val bio1 = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val bio2 = BIO.evalAsync { effect2 += 2 }.delayExecution(1.millisecond)
    val bio3 = BIO.evalAsync { effect3 += 3 }.delayExecution(1.millisecond)
    BIO.map3(bio1, bio2, bio3)((_, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
  }

  test("BIO#map4 should yield value successfully") { implicit s =>
    val bio1 = BIO.now(1).delayExecution(1.millisecond)
    val bio2 = BIO.now(2).delayExecution(1.millisecond)
    val bio3 = BIO.now(3).delayExecution(1.millisecond)
    val bio4 = BIO.now(4).delayExecution(2.millisecond)
    val map4BIO = BIO.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(Right(10))))
  }

  test("BIO#map4 should propagate error when one argument failed") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(5).delayExecution(1.millisecond)
    val bio3 = BIO.fromEither[String, Int](Left("3rd Step Failure")).delayExecution(1.millisecond)
    val bio4 = BIO((throw DummyException("4th Step Failure")) : Int)
    val map4BIO = BIO.map4(bio1, bio2, bio3, bio4)(_ + _ + _ + _)

    val bioExec = map4BIO.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("3rd Step Failure"))))
  }

  test("BIO#map4 should protect against user code") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(20).delayExecution(1.millisecond)
    val bio3 = BIO.now(5).delayExecution(1.millisecond)
    val bio4 = BIO.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map4BIO = BIO.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => (throw dummy): Int)

    val bioExec = map4BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("BIO#map4 should run effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0
    var effect4 = 0
    val bio1 = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val bio2 = BIO.evalAsync { effect2 += 2 }.delayExecution(1.millisecond)
    val bio3 = BIO.evalAsync { effect3 += 3 }.delayExecution(1.millisecond)
    val bio4 = BIO.evalAsync { effect4 += 4 }.delayExecution(1.millisecond)

    BIO.map4(bio1, bio2, bio3, bio4)((_, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
  }

  test("BIO#map5 should yield value successfully") { implicit s =>
    val bio1 = BIO.now(1).delayExecution(1.millisecond)
    val bio2 = BIO.now(2).delayExecution(1.millisecond)
    val bio3 = BIO.now(3).delayExecution(1.millisecond)
    val bio4 = BIO.now(4).delayExecution(1.millisecond)
    val bio5 = BIO.now(5).delayExecution(1.millisecond)
    val map5BIO = BIO.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5BIO.runToFuture

    s.tick(4.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(Right(15))))
  }

  test("BIO#map5 should propagate error when one argument failed") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = BIO.now(5).delayExecution(1.millisecond)
    val bio4 = BIO((throw DummyException("4th Step Failure")) : Int)
    val bio5 = BIO.now(10)
    val map5BIO = BIO.map5(bio1, bio2, bio3, bio4, bio5)(_ + _ + _ + _ + _)

    val bioExec = map5BIO.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(3.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("BIO#map5 should protect against user code") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(20).delayExecution(1.millisecond)
    val bio3 = BIO.now(5).delayExecution(1.millisecond)
    val bio4 = BIO.now(15).delayExecution(1.millisecond)
    val bio5 = BIO.now(15).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map5BIO = BIO.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => (throw dummy): Int)

    val bioExec = map5BIO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("BIO#map5 should run effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0
    var effect4 = 0
    var effect5 = 0
    val bio1 = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val bio2 = BIO.evalAsync { effect2 += 2 }.delayExecution(1.millisecond)
    val bio3 = BIO.evalAsync { effect3 += 3 }.delayExecution(1.millisecond)
    val bio4 = BIO.evalAsync { effect4 += 4 }.delayExecution(1.millisecond)
    val bio5 = BIO.evalAsync { effect5 += 5 }.delayExecution(1.millisecond)

    BIO.map5(bio1, bio2, bio3, bio4, bio5)((_, _, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
    assertEquals(effect5, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
    assertEquals(effect5, 5)
  }

  test("BIO#map6 should yield value successfully") { implicit s =>
    val bio1 = BIO.now(1).delayExecution(1.millisecond)
    val bio2 = BIO.now(2).delayExecution(1.millisecond)
    val bio3 = BIO.now(3).delayExecution(1.millisecond)
    val bio4 = BIO.now(4).delayExecution(1.millisecond)
    val bio5 = BIO.now(5).delayExecution(1.millisecond)
    val bio6 = BIO.now(6).delayExecution(1.millisecond)

    val map6BIO = BIO.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6BIO.runToFuture

    s.tick(5.millisecond)
    assertEquals(bioExec.value, None)
    s.tick(1.millisecond)
    assertEquals(bioExec.value, Some(Success(Right(21))))
  }

  test("BIO#map6 should propagate error when one argument failed") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.fromEither[String, Int](Left("2nd Step Failure")).delayExecution(1.millisecond)
    val bio3 = BIO.now(5).delayExecution(1.millisecond)
    val bio4 = BIO((throw DummyException("4th Step Failure")) : Int)
    val bio5 = BIO.now(10)
    val bio6 = BIO.now(20)

    val map6BIO = BIO.map6(bio1, bio2, bio3, bio4, bio5, bio6)(_ + _ + _ + _ + _ + _)

    val bioExec = map6BIO.runToFuture

    s.tick()
    assertEquals(bioExec.value, None)
    s.tick(4.millisecond)
    assertEquals(bioExec.value, Some(Success(Left("2nd Step Failure"))))
  }

  test("BIO#map6 should protect against user code") { implicit s =>
    val bio1 = BIO.now(10).delayExecution(1.millisecond)
    val bio2 = BIO.now(20).delayExecution(1.millisecond)
    val bio3 = BIO.now(5).delayExecution(1.millisecond)
    val bio4 = BIO.now(15).delayExecution(1.millisecond)
    val bio5 = BIO.now(15).delayExecution(1.millisecond)
    val bio6 = BIO.now(25).delayExecution(1.millisecond)
    val dummy = DummyException("dummy")
    val map6BIO = BIO.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => (throw dummy): Int)

    val bioExec = map6BIO.runToFuture

    s.tick(6.millisecond)
    assertEquals(bioExec.value, Some(Failure(dummy)))
  }

  test("BIO#map6 should run effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    var effect3 = 0
    var effect4 = 0
    var effect5 = 0
    var effect6 = 0
    val bio1 = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val bio2 = BIO.evalAsync { effect2 += 2 }.delayExecution(1.millisecond)
    val bio3 = BIO.evalAsync { effect3 += 3 }.delayExecution(1.millisecond)
    val bio4 = BIO.evalAsync { effect4 += 4 }.delayExecution(1.millisecond)
    val bio5 = BIO.evalAsync { effect5 += 5 }.delayExecution(1.millisecond)
    val bio6 = BIO.evalAsync { effect6 += 6 }.delayExecution(1.millisecond)

    BIO.map6(bio1, bio2, bio3, bio4, bio5, bio6)((_, _, _, _, _, _) => ()).runToFuture

    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 0)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 0)
    assertEquals(effect5, 0)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
    assertEquals(effect5, 0)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
    assertEquals(effect5, 5)
    assertEquals(effect6, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 2)
    assertEquals(effect3, 3)
    assertEquals(effect4, 4)
    assertEquals(effect5, 5)
    assertEquals(effect6, 6)
  }
}
