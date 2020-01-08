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

import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskErrorSuite extends BaseTestSuite {
  test("Task.attempt should expose error") { implicit s =>
    val dummy = DummyException("dummy")
    val r = Task.raiseError[Int](dummy).attempt.runSyncStep
    assertEquals(r, Right(Left(dummy)))
  }

  test("Task.attempt should work for successful values") { implicit s =>
    val r = Task.now(10).attempt.runSyncStep
    assertEquals(r, Right(Right(10)))
  }

  test("Task.fail should expose error") { implicit s =>
    val dummy = DummyException("dummy")
    val r = Task.raiseError[Int](dummy).failed.runSyncStep
    assertEquals(r, Right(dummy))
  }

  test("Task.fail should fail for successful values") { implicit s =>
    intercept[NoSuchElementException] {
      Task.now(10).failed.runSyncStep
    }
  }

  test("Task#onErrorRecover should mirror source on success") { implicit s =>
    val task = Task.evalAsync(1).onErrorRecover { case _: Throwable => 99 }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("Task#onErrorRecover should recover") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task[Int](if (1 == 1) throw ex else 1).onErrorRecover {
      case _: DummyException => 99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("Task#onErrorRecover should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = Task[Int](if (1 == 1) throw ex1 else 1).onErrorRecover { case _ => throw ex2 }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("Task#onErrorHandle should mirror source on success") { implicit s =>
    val task = Task.evalAsync(1).onErrorHandle { _: Throwable =>
      99
    }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("Task#onErrorHandle should recover") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task[Int](if (1 == 1) throw ex else 1).onErrorHandle {
      case _: DummyException => 99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("Task#onErrorHandle should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = Task[Int](if (1 == 1) throw ex1 else 1).onErrorHandle { _ =>
      throw ex2
    }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("Task.onErrorFallbackTo should mirror source onSuccess") { implicit s =>
    val task = Task.evalAsync(1).onErrorFallbackTo(Task.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("Task.onErrorFallbackTo should fallback to backup onError") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.evalAsync(throw ex).onErrorFallbackTo(Task.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(2))))
  }

  test("Task.onErrorFallbackTo should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val err = DummyException("unexpected")
    val task = Task.evalAsync(throw ex).onErrorFallbackTo(Task.defer(throw err))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Left(err))))
  }

  test("Task.onErrorFallbackTo should be cancelable if the ExecutionModel allows it") { implicit s =>
    def recursive(): Task[Int] = {
      Task[Int](throw DummyException("dummy")).onErrorFallbackTo(Task.defer(recursive()))
    }

    val task = recursive().executeWithOptions(_.enableAutoCancelableRunLoops)
    val f = task.runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("Task.onErrorRestart should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val task = Task.eval { tries += 1; 1 }.onErrorRestart(10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestart should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; if (tries < 5) throw ex else 1 }.onErrorRestart(10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("Task.onErrorRestart should emit onError after max retries") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; throw ex }.onErrorRestart(10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(Left(ex))))
    assertEquals(tries, 11)
  }

  test("Task.onErrorRestart should be cancelable if ExecutionModel permits") { implicit s =>
    val batchSize = (s.executionModel.recommendedBatchSize * 2).toLong
    val task = Task[Int](throw DummyException("dummy"))
      .onErrorRestart(batchSize)

    val f = task.executeWithOptions(_.enableAutoCancelableRunLoops).runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("Task.onErrorRestart should not restart on fatal error") { implicit s =>
    var tries = 0
    val ex = DummyException("dummy")
    val task = Task.eval { tries += 1; throw ex }.hideErrors.onErrorRestart(5)
    val f = task.runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestartIf should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val task = Task.eval { tries += 1; 1 }.onErrorRestartIf(_ => tries < 10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestartIf should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; if (tries < 5) throw ex else 1 }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("Task.onErrorRestartIf should emit onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; throw ex }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Left(ex))))
    assertEquals(tries, 11)
  }

  test("Task.onErrorRestartIf should be cancelable if ExecutionModel permits") { implicit s =>
    val task = Task[Int](throw DummyException("dummy")).onErrorRestartIf(_ => true)
    val f = task.executeWithOptions(_.enableAutoCancelableRunLoops).runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("Task.onErrorRestartIf should not restart on fatal error") { implicit s =>
    var tries = 0
    val ex = DummyException("dummy")
    val task: Task[Int] = Task.eval { tries += 1; throw ex }.hideErrors
    val f = task.onErrorRestartIf(_ => tries < 5).runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestartIf should restart on typed error") { implicit s =>
    var tries = 0
    val task = BIO.unit.flatMap { _ =>
      tries += 1; if (tries < 5) BIO.raiseError("error") else BIO.pure(1)
    }
    val f = task.onErrorRestartIf(_ == "error").runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("Task#onErrorRecoverWith should mirror source on success") { implicit s =>
    val task = Task.evalAsync(1).onErrorRecoverWith { case _: Throwable => Task.evalAsync(99) }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("Task#onErrorRecoverWith should recover") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task[Int](throw ex).onErrorRecoverWith {
      case _: DummyException => Task.evalAsync(99)
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("Task#onErrorRecoverWith should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = Task[Int](throw ex1).onErrorRecoverWith { case _ => throw ex2 }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("Task#onErrorRecoverWith has a cancelable fallback") { implicit s =>
    val task = Task[Int](throw DummyException("dummy")).onErrorRecoverWith {
      case _: DummyException => Task.evalAsync(99).delayExecution(1.second)
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    // cancelling after scheduled for execution, but before execution
    s.tick(); assertEquals(f.value, None)

    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("Task#onErrorRecover should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task[Int](throw dummy).onErrorRecover { case _: TimeoutException => 10 }
    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("Task#onErrorRecoverWith should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task[Int](throw dummy).onErrorRecoverWith { case _: TimeoutException => Task.now(10) }
    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  // TODO: add tests for variants catching fatal errors instead
//  test("Task.eval.flatMap.onErrorRecoverWith should work") { implicit s =>
//    val dummy = DummyException("dummy")
//    val task = Task.eval(1).flatMap(_ => throw dummy).onErrorRecoverWith { case `dummy` => Task.now(10) }
//
//    val f = task.runToFuture
//    assertEquals(f.value, Some(Success(10)))
//  }
//
//  test("Task.flatMap.onErrorRecoverWith should work") { implicit s =>
//    val dummy = DummyException("dummy")
//    val task = Task.evalAsync(1).flatMap(_ => throw dummy).onErrorRecoverWith { case `dummy` => Task.now(10) }
//
//    val f = task.runToFuture; s.tick()
//    assertEquals(f.value, Some(Success(10)))
//  }
//

  test("Task.now.onErrorRecoverWith should be stack safe") { implicit s =>
    val ex = DummyException("dummy1")
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: Task[Int], n: Int): Task[Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => Task.now(count)
        case `ex` => loop(task, n - 1)
      }

    val task = loop(Task.raiseError(ex), count)
    val result = task.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  test("onErrorRecoverWith should be stack safe for loop with executeAsync boundaries") { implicit s =>
    val ex = DummyException("dummy1")
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: Task[Int], n: Int): Task[Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => Task.evalAsync(count)
        case `ex` => loop(task, n - 1).executeAsync
      }

    val task = loop(Task.raiseError(ex), count)
    val result = task.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }
//
//  test("Task.onErrorRestartLoop works for success") { implicit s =>
//    val dummy = DummyException("dummy")
//    var tries = 0
//    val source = Task.eval {
//      tries += 1
//      if (tries < 5) throw dummy
//      tries
//    }
//
//    val task = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
//      if (maxRetries > 0)
//        retry(maxRetries - 1)
//      else
//        Task.raiseError(err)
//    }
//
//    val f = task.runToFuture
//    assertEquals(f.value, Some(Success(5)))
//    assertEquals(tries, 5)
//  }
//
//  test("Task.onErrorRestartLoop can rethrow") { implicit s =>
//    val dummy = DummyException("dummy")
//    val source = Task.eval[Int] { throw dummy }
//
//    val task = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
//      if (maxRetries > 0)
//        retry(maxRetries - 1)
//      else
//        Task.raiseError(err)
//    }
//
//    val f = task.runToFuture
//    assertEquals(f.value, Some(Failure(dummy)))
//  }
}
