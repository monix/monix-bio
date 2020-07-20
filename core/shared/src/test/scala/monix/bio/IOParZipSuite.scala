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

object IOParZipSuite extends BaseTestSuite {

  test("IO.parZip2 should work if source finishes first") { implicit s =>
    val f = IO.parZip2(IO(1), IO(2).delayExecution(1.second)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success((1, 2))))
  }

  test("IO.parZip2 should work if other finishes first") { implicit s =>
    val f = IO.parZip2(IO(1).delayExecution(1.second), IO(2)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success((1, 2))))
  }

  test("IO.parZip2 should cancel both") { implicit s =>
    val f = IO.parZip2(IO(1).delayExecution(1.second), IO(2).delayExecution(2.seconds)).runToFuture

    s.tick()
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("IO.parZip2 should cancel just the source") { implicit s =>
    val f = IO.parZip2(IO(1).delayExecution(1.second), IO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("IO.parZip2 should cancel just the other") { implicit s =>
    val f = IO.parZip2(IO(1).delayExecution(2.second), IO(2).delayExecution(1.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    f.cancel()

    s.tick()
    assertEquals(f.value, None)
  }

  test("IO.parZip2 should onError from the source before other") { implicit s =>
    val ex = DummyException("dummy")
    val f = IO.parZip2(IO(throw ex).delayExecution(1.second), IO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.parZip2 handle terminal errors from the source before other") { implicit s =>
    val ex = DummyException("dummy")
    val f = IO.parZip2(IO.terminate(ex).delayExecution(1.second), IO(2).delayExecution(2.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.parZip2 should onError from the other after the source") { implicit s =>
    val ex = DummyException("dummy")

    val f = IO.parZip2(IO(1).delayExecution(1.second), IO(throw ex)).delayExecution(2.seconds).runToFuture

    s.tick(2.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.parZip2 should handle terminal errors from the other after the source") { implicit s =>
    val ex = DummyException("dummy")

    val f = IO.parZip2(IO(1).delayExecution(1.second), IO.terminate(ex)).delayExecution(2.seconds).runToFuture

    s.tick(2.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.parZip2 should onError from the other before the source") { implicit s =>
    val ex = DummyException("dummy")
    val f = IO.parZip2(IO(1).delayExecution(2.second), IO(throw ex).delayExecution(1.seconds)).runToFuture

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.parZip2 works") { implicit s =>
    val f1 = IO.parZip2(IO(1), IO(2)).runToFuture
    val f2 = IO.parMap2(IO(1), IO(2))((a, b) => (a, b)).runToFuture
    s.tick()
    assertEquals(f1.value.get, f2.value.get)
  }

  test("IO.map2 works") { implicit s =>
    val fa = IO.map2(IO(1), IO(2))(_ + _).runToFuture
    s.tick()
    assertEquals(fa.value, Some(Success(3)))
  }

  test("IO#map2 should protect against user code") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.now(10).delayExecution(1.second)
    val tb = IO.now(20).delayExecution(1.second)
    val result = IO.map2(ta, tb)((_, _) => (throw dummy): Int)

    val f = result.runToFuture
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.map2 runs effects in strict sequence") { implicit s =>
    var effect1 = 0
    var effect2 = 0
    val ta = IO.evalAsync { effect1 += 1 }.delayExecution(1.millisecond)
    val tb = IO.evalAsync { effect2 += 1 }.delayExecution(1.millisecond)
    IO.map2(ta, tb)((_, _) => ()).runToFuture
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

  test("IO.parMap2 works") { implicit s =>
    val fa = IO.parMap2(IO(1), IO(2))(_ + _).runToFuture
    s.tick()
    assertEquals(fa.value, Some(Success(3)))
  }

  test("IO#parMap2 should protect against user code") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.now(10).delayExecution(1.second)
    val tb = IO.now(20).delayExecution(1.second)
    val result = IO.parMap2(ta, tb)((_, _) => (throw dummy): Int)

    val f = result.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.parZip3 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parZip3(n(1), n(2), n(3))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("IO#map3 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(n.seconds)
    val t = IO.map3(n(1), n(2), n(3))((_, _, _))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, None)
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("IO#parMap3 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parMap3(n(1), n(2), n(3))((_, _, _))
    val r = t.runToFuture
    s.tick(3.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3))))
  }

  test("IO.parZip4 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parZip4(n(1), n(2), n(3), n(4))
    val r = t.runToFuture
    s.tick(4.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("IO#map4 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(n.seconds)
    val t = IO.map4(n(1), n(2), n(3), n(4))((_, _, _, _))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, None)
    s.tick(4.second)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("IO#parMap4 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parMap4(n(1), n(2), n(3), n(4))((_, _, _, _))
    val r = t.runToFuture
    s.tick(4.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4))))
  }

  test("IO.parZip5 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parZip5(n(1), n(2), n(3), n(4), n(5))
    val r = t.runToFuture
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("IO#map5 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(n.seconds)
    val t = IO.map5(n(1), n(2), n(3), n(4), n(5))((_, _, _, _, _))
    val r = t.runToFuture
    s.tick(10.seconds)
    assertEquals(r.value, None)
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("IO#parMap5 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parMap5(n(1), n(2), n(3), n(4), n(5))((_, _, _, _, _))
    val r = t.runToFuture
    s.tick(5.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5))))
  }

  test("IO.parZip6 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parZip6(n(1), n(2), n(3), n(4), n(5), n(6))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }

  test("IO#map6 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(n.seconds)
    val t = IO.map6(n(1), n(2), n(3), n(4), n(5), n(6))((_, _, _, _, _, _))
    val r = t.runToFuture
    s.tick(20.seconds)
    assertEquals(r.value, None)
    s.tick(1.second)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }

  test("IO#parMap6 works") { implicit s =>
    def n(n: Int) = IO.now(n).delayExecution(Random.nextInt(n).seconds)
    val t = IO.parMap6(n(1), n(2), n(3), n(4), n(5), n(6))((_, _, _, _, _, _))
    val r = t.runToFuture
    s.tick(6.seconds)
    assertEquals(r.value, Some(Success((1, 2, 3, 4, 5, 6))))
  }
}
