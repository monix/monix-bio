/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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

import cats.syntax.either._
import monix.execution.exceptions.DummyException

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskTimedSuite extends BaseTestSuite {

  test("measure tasks with no errors") { implicit s =>
    val bio = BIO.fromEither(123.asRight[String]).delayExecution(2.second).timed
    val f = bio.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(2.second -> 123))))

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(2.second -> 123))))
  }

  test("not measure tasks with typed errors") { implicit s =>
    val bio = BIO.fromEither("Error".asLeft[Int]).delayExecution(2.second).timed
    val f = bio.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left("Error"))))

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left("Error"))))
  }

  test("not measure tasks with fatal errors") { implicit s =>
    val bio = BIO.raiseFatalError(DummyException("Fatal")).delayExecution(2.second).timed
    val f = bio.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("Fatal"))))

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("Fatal"))))
  }

  test("measure tasks with typed errors followed by `.attempt`") { implicit s =>
    val bio = BIO.fromEither("Error".asLeft[Int]).delayExecution(2.second).attempt.timed
    val f = bio.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(2.second -> Left("Error")))))

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(2.second -> Left("Error")))))
  }

  test("not measure tasks with fatal errors followed by `.attempt`") { implicit s =>
    val bio = BIO.raiseFatalError(DummyException("Fatal")).delayExecution(2.second).attempt.timed
    val f = bio.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("Fatal"))))

    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("Fatal"))))
  }

  test("stack safety") { implicit sc =>
    def loop(n: Int, acc: Duration): BIO[Nothing, Duration] =
      BIO.unit.delayResult(1.second).timed.flatMap {
        case (duration, _) =>
          if (n > 0)
            loop(n - 1, acc + duration)
          else
            BIO.now(acc)
      }

    val f = loop(10000, 0.second).runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(10000.seconds)
    assertEquals(f.value, None)

    sc.tick(10001.seconds)
    assertEquals(f.value, Some(Success(Right(10000.seconds))))
  }

}
