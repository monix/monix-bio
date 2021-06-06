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

import cats.syntax.either._
import monix.execution.atomic.AtomicInt
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskParTraverseNSuite extends BaseTestSuite {

  test("IO.parTraverseN allows fully sequential execution") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 1)(numbers) { num =>
      IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(76.seconds)
    assertEquals(f.value, None)

    s.tick(77.seconds)
    assertEquals(f.value, Some(Success(Right(List(4, 25, 100, 400, 1600)))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN allows fully concurrent execution") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 5)(numbers) { num =>
      IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(39.seconds)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Success(Right(List(4, 25, 100, 400, 1600)))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN allows partially concurrent execution") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(2.seconds) // num "2" is finished, num "10" starts processing
    s.tick(3.seconds) // num "5" is finished, num "20" starts processing
    s.tick(7.seconds) // num "10" is finished, num "40" starts processing
    s.tick(13.seconds) // num "20" is finished
    s.tick(26.seconds) // num "40" is almost finished
    assertEquals(f.value, None)

    s.tick(1.seconds) // num "40" is finished
    assertEquals(f.value, Some(Success(Right(List(4, 25, 100, 400, 1600)))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when fully sequential execution fails in a typed way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 1)(numbers) { num =>
      if (num == 10) IO.fromEither("dummy error".asLeft[Int]).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(16.second)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Success(Left("dummy error"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when fully sequential execution fails in a terminal way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 1)(numbers) { num =>
      if (num == 10) IO.terminate(DummyException("dummy exception")).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(16.second)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when fully concurrent execution fails in a typed way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 5)(numbers) { num =>
      if (num == 10) IO.fromEither("dummy error".asLeft[Int]).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(9.second)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Success(Left("dummy error"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when fully concurrent execution fails in a terminal way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 5)(numbers) { num =>
      if (num == 10) IO.terminate(DummyException("dummy exception")).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(9.second)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when partially concurrent execution fails in a typed way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      if (num == 10) IO.fromEither("dummy error".asLeft[Int]).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(2.seconds) // num "2" is finished, num "10" starts processing
    s.tick(3.seconds) // num "5" is finished, num "20" starts processing
    s.tick(6.seconds) // num "10" is almost finished
    assertEquals(f.value, None)

    s.tick(1.seconds) // num "10" is finished
    assertEquals(f.value, Some(Success(Left("dummy error"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns an error when partially concurrent execution fails in a terminal way") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      if (num == 10) IO.terminate(DummyException("dummy exception")).delayExecution(num.seconds)
      else IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(2.seconds) // num "2" is finished, num "10" starts processing
    s.tick(3.seconds) // num "5" is finished, num "20" starts processing
    s.tick(6.seconds) // num "10" is almost finished
    assertEquals(f.value, None)

    s.tick(1.seconds) // num "10" is finished
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
    assert(s.state.tasks.isEmpty, "no tasks should be left")
  }

  test("IO.parTraverseN returns a terminal error when an exception is thrown") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 5)(numbers) { num =>
      if (num == 10) throw DummyException("dummy exception")
      else IO.fromEither(num.asRight[String])
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
  }

  test("IO.parTraverseN should be cancelable") { implicit s =>
    val numbers = List(2, 5, 10, 20, 40)
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      IO.fromEither((num * num).asRight[String]).delayExecution(num.seconds)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.nonEmpty, "some tasks should be scheduled")

    f.cancel()
    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "every task should be cancelled")
  }

  test("IO.parTraverseN should be stack safe for synchronous tasks with low parallelism") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val numbers = 1.to(count).toList
    val traverse = IO
      .parTraverseN(parallelism = 10)(numbers)(num => IO.fromEither(num.asRight[String]))
      .map(_.sum)
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Right(numbers.sum))))
  }

  test("IO.parTraverseN should be stack safe for asynchronous tasks with low parallelism") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val numbers = 1.to(count).toList
    val traverse = IO
      .parTraverseN(parallelism = 10)(numbers)(num => IO.fromEither(num.asRight[String]).executeAsync)
      .map(_.sum)
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Right(numbers.sum))))
  }

  test("IO.parTraverseN should be stack safe for synchronous tasks with high parallelism") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val numbers = 1.to(count).toList
    val traverse = IO
      .parTraverseN(parallelism = count)(numbers)(num => IO.fromEither(num.asRight[String]))
      .map(_.sum)
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Right(numbers.sum))))
  }

  test("IO.parTraverseN should be stack safe for asynchronous tasks with high parallelism") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val numbers = 1.to(count).toList
    val traverse = IO
      .parTraverseN(parallelism = count)(numbers)(num => IO.fromEither(num.asRight[String]).executeAsync)
      .map(_.sum)
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Right(numbers.sum))))
  }

  test("IO.parTraverseN allows running the same effect multiple times") { implicit s =>
    val counter = AtomicInt(0)
    val numbers = List(2, 5, 10, 20, 40)
    val effect = IO.evalAsync(counter.increment())
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      effect.map(_ => num * num)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(counter.get(), 5)
    assertEquals(f.value, Some(Success(Right(List(4, 25, 100, 400, 1600)))))
  }

  test("IO.parTraverseN allows reusing a memoized effect multiple times") { implicit s =>
    val counter = AtomicInt(0)
    val numbers = List(2, 5, 10, 20, 40)
    val effect = IO.evalAsync(counter.increment()).memoize
    val traverse = IO.parTraverseN(parallelism = 2)(numbers) { num =>
      effect.map(_ => num * num)
    }
    val f = traverse.attempt.runToFuture

    s.tick()
    assertEquals(counter.get(), 1)
    assertEquals(f.value, Some(Success(Right(List(4, 25, 100, 400, 1600)))))
  }

}
