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
import monix.execution.exceptions.DummyException
import org.reactivestreams.{Subscriber, Subscription}

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskMiscSuite extends BaseTestSuite {
  test("IO.attempt should succeed") { implicit s =>
    val result = IO.now(1).attempt.runToFuture
    assertEquals(result.value, Some(Success(Right(1))))
  }

  test("IO.raiseError.attempt should expose error") { implicit s =>
    val ex = 1204
    val result = IO.raiseError(ex).attempt.runToFuture.map(_.flatMap(identity))
    s.tickOne()
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("IO.mapError should map error") { implicit s =>
    val ex = "not dummy"
    val result = IO
      .raiseError("dummy")
      .mapError(_ => ex)
      .attempt
      .runToFuture

    s.tickOne()
    assertEquals(result.value, Some(Success(Left(ex))))
  }

  test("IO.terminate.attempt should not expose error") { implicit s =>
    val ex = DummyException("dummy")
    val result = IO.terminate(ex).attempt.runToFuture.map(_.flatMap(identity))
    s.tickOne()
    assertEquals(result.value, Some(Failure(ex)))
  }

  test("IO.tapError should not alter original successful value") { implicit s =>
    val result = IO.fromEither[String, Int](1.asRight).tapError(e => IO.raiseError(e)).attempt.runToFuture
    assertEquals(result.value, Some(Success(Right(1))))
  }

  test("IO.tapError should not alter original error value") { implicit s =>
    var effect = 0
    val result = IO.fromEither[String, Int]("Error".asLeft).tapError(_ => IO.delay(effect += 1)).attempt.runToFuture
    assertEquals(result.value, Some(Success(Left("Error"))))
    assertEquals(effect, 1)
  }

  test("IO.tapError should not alter original terminal error") { implicit s =>
    var effect = 0
    val ex = DummyException("dummy")
    val result = IO.terminate(ex).tapError(_ => IO.delay(effect += 1)).runToFuture
    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(effect, 0)
  }

  test("IO.failed should expose error") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.raiseError(dummy).failed.runToFuture
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("IO.failed should fail for successful values") { implicit s =>
    intercept[NoSuchElementException] {
      IO.eval(10).failed.runSyncStep
    }
    ()
  }

  test("IO.map protects against user code") { implicit s =>
    val ex = DummyException("dummy")
    val result = IO.now(1).map(_ => throw ex).runToFuture
    assertEquals(result.value, Some(Failure(ex)))
  }

  test("IO.forever") { implicit s =>
    val ex = DummyException("dummy")
    var effect = 0
    val result = IO.eval { if (effect < 10) effect += 1 else throw ex }.loopForever
      .onErrorFallbackTo(IO.eval(effect))
      .runToFuture
    assertEquals(result.value.get.get, 10)
  }

  test("IO.restartUntil should keep retrying IO until predicate succeeds") { implicit s =>
    var effect = 0
    val r = IO.evalAsync { effect += 1; effect }.restartUntil(_ >= 10).runToFuture
    s.tick()
    assertEquals(r.value, Some(Success(10)))
  }

  test("IO.toReactivePublisher should convert tasks with no errors") { implicit s =>
    val publisher = IO.fromTry(Success(123)).toReactivePublisher
    var received = 0
    var wasCompleted = false

    publisher.subscribe {
      new Subscriber[Int] {
        def onSubscribe(s: Subscription): Unit =
          s.request(10)
        def onNext(elem: Int): Unit =
          received = elem
        def onError(ex: Throwable): Unit =
          throw ex
        def onComplete(): Unit =
          wasCompleted = true
      }
    }

    s.tick()
    assert(wasCompleted, "wasCompleted")
    assertEquals(received, 123)
  }

  test("IO.toReactivePublisher should convert tasks with typed errors") { implicit s =>
    val expected = DummyException("Error")
    val publisher = IO.fromTry(Failure(expected)).toReactivePublisher
    var received: Throwable = null

    publisher.subscribe {
      new Subscriber[Int] {
        def onSubscribe(s: Subscription): Unit =
          s.request(10)
        def onNext(elem: Int): Unit =
          throw new IllegalStateException("onNext")
        def onError(ex: Throwable): Unit =
          received = ex
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      }
    }

    s.tick()
    assertEquals(received, expected)
  }

  test("IO.toReactivePublisher should convert tasks with terminal errors") { implicit s =>
    val expected = DummyException("Error")
    val publisher = IO.terminate(expected).toReactivePublisher
    var received: Throwable = null

    publisher.subscribe {
      new Subscriber[Int] {
        def onSubscribe(s: Subscription): Unit =
          s.request(10)
        def onNext(elem: Int): Unit =
          throw new IllegalStateException("onNext")
        def onError(ex: Throwable): Unit =
          received = ex
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      }
    }

    s.tick()
    assertEquals(received, expected)
  }

  test("IO.toReactivePublisher should be cancelable") { implicit s =>
    val publisher = IO.now(123).delayExecution(1.second).toReactivePublisher

    publisher.subscribe {
      new Subscriber[Int] {
        def onSubscribe(s: Subscription): Unit = {
          s.request(10)
          s.cancel()
        }
        def onNext(t: Int): Unit =
          throw new IllegalStateException("onNext")
        def onError(t: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      }
    }

    s.tick()
    assert(s.state.tasks.isEmpty, "should not have tasks left to execute")
  }

  test("IO.toReactivePublisher should throw errors on invalid requests") { implicit s =>
    val publisher = IO.now(1).delayExecution(1.second).toReactivePublisher

    publisher.subscribe {
      new Subscriber[Int] {
        def onSubscribe(s: Subscription): Unit = {
          intercept[IllegalArgumentException] {
            s.request(-1)
          }
          ()
        }
        def onNext(t: Int): Unit =
          throw new IllegalStateException("onNext")
        def onError(t: Throwable): Unit =
          throw new IllegalStateException("onError")
        def onComplete(): Unit =
          throw new IllegalStateException("onComplete")
      }
    }

    s.tick()
    assert(s.state.tasks.isEmpty, "should not have tasks left to execute")
  }

  test("IO.pure is an alias of now") { implicit s =>
    assertEquals(IO.pure(1), IO.now(1))
  }

  test("IO.now.runAsync with Try-based callback") { implicit s =>
    val p = Promise[Either[Int, Int]]()
    (IO.now(1): IO[Int, Int]).runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    assertEquals(p.future.value, Some(Success(Right(1))))
  }

  test("Task.error.runAsync with Try-based callback") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Either[Throwable, Int]]()
    Task.raiseError[Int](ex).runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    assertEquals(p.future.value, Some(Success(Left(ex))))
  }

  test("Task.terminate.runAsync with Try-based callback") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Either[Throwable, Int]]()
    Task.terminate[Int](ex).runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    assertEquals(p.future.value, Some(Failure(ex)))
  }

  test("task.executeAsync.runAsync with Try-based callback for success") { implicit s =>
    val p = Promise[Either[Int, Int]]()
    IO.now(1).executeAsync.runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    s.tick()
    assertEquals(p.future.value, Some(Success(Right(1))))
  }

  test("task.executeAsync.runAsync with Try-based callback for error") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Either[Throwable, Int]]()
    Task.raiseError[Int](ex).executeAsync.runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    s.tick()
    assertEquals(p.future.value, Some(Success(Left(ex))))
  }

  test("task.executeAsync.runAsync with Try-based callback for terminal error") { implicit s =>
    val ex = DummyException("dummy")
    val p = Promise[Either[Throwable, Int]]()
    Task.terminate[Int](ex).executeAsync.runAsync {
      case Left(cause) => cause.fold(p.failure, t => p.success(Left(t)))
      case Right(v) => p.success(Right(v))
    }
    s.tick()
    assertEquals(p.future.value, Some(Failure(ex)))
  }

  test("task.executeWithOptions protects against user error") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.now(1).executeWithOptions(_ => throw ex)
    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(ex)))
  }
}
