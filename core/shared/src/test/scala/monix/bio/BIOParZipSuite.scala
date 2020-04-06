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
import scala.util.{Failure, Random, Success}

object BIOParZipSuite extends BaseTestSuite {

  test("BIO.parZip2 should work if source finishes first") { implicit s =>
    val f = BIO.parZip2(BIO(1), BIO(2).delayExecution(1.second)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success((1, 2))))
  }

  test("BIO.parZip2 should work if other finishes first") { implicit s =>
    val f = BIO.parZip2(BIO(1).delayExecution(1.second), BIO(2)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success((1, 2))))
  }

  test("BIO.parZip2 should cancel both") { implicit s =>
    val f = BIO.parZip2(BIO(1).delayExecution(1.second), BIO(2).delayExecution(2.seconds)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.parZip2 should cancel just the source") { implicit s =>
    val f = BIO.parZip2(BIO(1).delayExecution(1.second), BIO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.parZip2 should cancel just the other") { implicit s =>
    val f = BIO.parZip2(BIO(1).delayExecution(2.second), BIO(2).delayExecution(1.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.parZip2 should onError from the source before other") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.parZip2(BIO(throw ex).delayExecution(1.second), BIO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parZip2 handle terminal errors from the source before other") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.parZip2(BIO.terminate(ex).delayExecution(1.second), BIO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parZip2 should onError from the other after the source") { implicit s =>
    val ex = DummyException("dummy")

    val f = BIO.parZip2(BIO(1).delayExecution(1.second), BIO(throw ex)).delayExecution(2.seconds).runToFuture

    s.tick(2.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parZip2 should handle terminal errors from the other after the source") { implicit s =>
    val ex = DummyException("dummy")

    val f = BIO.parZip2(BIO(1).delayExecution(1.second), BIO.terminate(ex)).delayExecution(2.seconds).runToFuture

    s.tick(2.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parZip2 should onError from the other before the source") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.parZip2(BIO(1).delayExecution(2.second), BIO(throw ex).delayExecution(1.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parZip2 works") { implicit s =>
    val f1 = BIO.parZip2(BIO(1), BIO(2)).runToFuture
    val f2 = BIO.parMap2(BIO(1), BIO(2))((a, b) => (a, b)).runToFuture
    s.tick()
    assertEquals(f1.value.get, f2.value.get)
  }

  test("BIO.map2 works") { implicit s =>
    val fa = BIO.map2(BIO(1), BIO(2))(_ + _).runToFuture
    s.tick()
    assertEquals(fa.value, Some(Success(3)))
  }

  test("BIO#map2 should protect against user code") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = BIO.now(10).delayExecution(1.second)
    val tb = BIO.now(20).delayExecution(1.second)
    val result = BIO.map2(ta, tb)((_, _) => (throw dummy): Int)

    val f = result.runToFuture
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.map2 runs effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    val ta = BIO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val tb = BIO.evalAsync { effect2 += 1 }.delayExecution(1.millisecond)
    BIO.map2(ta, tb)((_, _) => ()).runToFuture
    s.tick()
    assertEquals(effect1, 0)
    assertEquals(effect2, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 0)
    s.tick(1.millisecond)
    assertEquals(effect1, 1)
    assertEquals(effect2, 1)
  }

  test("BIO.parMap2 works") { implicit s =>
    val fa = BIO.parMap2(BIO(1), BIO(2))(_ + _).runToFuture
    s.tick()
    assertEquals(fa.value, Some(Success(3)))
  }

  test("BIO#parMap2 should protect against user code") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = BIO.now(10).delayExecution(1.second)
    val tb = BIO.now(20).delayExecution(1.second)
    val result = BIO.parMap2(ta, tb)((_, _) => (throw dummy): Int)

    val f = result.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.parZip3 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parZip3(n(1), n(2), n(3))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("BIO#map3 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(n.seconds)
    val t = BIO.map3(n(1), n(2), n(3))((_, _, _))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, None)
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("BIO#parMap3 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parMap3(n(1), n(2), n(3))((_, _, _))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("BIO.parZip4 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parZip4(n(1), n(2), n(3), n(4))
    val r = t.runToFuture
    s.tick(4.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("BIO#map4 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(n.seconds)
    val t = BIO.map4(n(1), n(2), n(3), n(4))((_, _, _, _))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, None)
    s.tick(4.second)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("BIO#parMap4 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parMap4(n(1), n(2), n(3), n(4))((_, _, _, _))
    val r = t.runToFuture
    s.tick(4.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("BIO.parZip5 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parZip5(n(1), n(2), n(3), n(4), n(5))
    val r = t.runToFuture
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("BIO#map5 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(n.seconds)
    val t = BIO.map5(n(1), n(2), n(3), n(4), n(5))((_, _, _, _, _))
    val r = t.runToFuture
    s.tick(10.seconds)
    assertEquals(r.value, None)
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("BIO#parMap5 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parMap5(n(1), n(2), n(3), n(4), n(5))((_, _, _, _, _))
    val r = t.runToFuture
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("BIO.parZip6 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parZip6(n(1), n(2), n(3), n(4), n(5), n(6))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }

  test("BIO#map6 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(n.seconds)
    val t = BIO.map6(n(1), n(2), n(3), n(4), n(5), n(6))((_, _, _, _, _, _))
    val r = t.runToFuture
    s.tick(20.seconds)
    assertEquals(r.value, None)
    s.tick(1.second)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }

  test("BIO#parMap6 works") { implicit s =>
    def n(n: Int) = BIO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = BIO.parMap6(n(1), n(2), n(3), n(4), n(5), n(6))((_, _, _, _, _, _))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }
}
