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

import cats.laws._
import cats.laws.discipline._
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskFlipSuite extends BaseTestSuite with ArbitraryInstances {

  test("flip successfully swaps the error and value parameters") { implicit s =>
    val ex = DummyException("dummy")
    val f = Task.raiseError(ex).flip.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(ex)))
  }

  test("flipWith should successfully apply the provided function to the swapped error value") { implicit s =>
    val ex0 = DummyException("dummy0")
    val ex1 = DummyException("dummy1")

    val f = Task.raiseError(ex0).flipWith(_.map(_ => ex1)).runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex1)))
  }

  test("flip should not alter original successful value") { implicit s =>
    val f = Task(1).flip.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Left(1))))
  }

  test("flipWith should not alter original successful value") { implicit s =>
    val f = Task(1).flipWith(_.map(identity)).runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("F.flip.map(f) <-> F.mapError(f).flip") { implicit ec =>
    val f = (_: String) => "dummy1"

    check1 { F: Task[String, Int] =>
      F.flip.map(f) <-> F.mapError(f).flip
    }
  }

  test("F.flipWith(f) <-> F.mapError(f)") { implicit s =>
    val f0 = (ex: Task[Int, String]) => ex.map(_ => "dummy1")
    val f1 = (_: String) => "dummy1"

    check1 { F: Task[String, Int] =>
      F.flipWith(f0) <-> F.mapError(f1)
    }
  }
}
