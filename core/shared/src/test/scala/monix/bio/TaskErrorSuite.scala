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
import monix.execution.internal.Platform

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskErrorSuite extends BaseTestSuite {
  test("Task.attempt should expose typed error") { implicit s =>
    val dummy = "dummy"
    val r = Task.raiseError(dummy).attempt.runSyncStep
    assertEquals(r, Right(Left(dummy)))
  }

  test("Task.attempt should not expose unexpected error") { implicit s =>
    val dummy = DummyException("dummy")
    val f = Task.terminate(dummy).attempt.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.attempt should work for successful values") { implicit s =>
    val r = Task.now(10).attempt.runSyncStep
    assertEquals(r, Right(Right(10)))
  }

  test("Task.rethrow should turn Left to typed error") { implicit s =>
    val dummy = "dummy"
    val f = Task.now(Left(dummy)).rethrow.attempt.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("Task.rethrow should turn Right to success value") { implicit s =>
    val dummy = "dummy"
    val r = Task.now(Right(dummy)).rethrow.runSyncStep
    assertEquals(r, Right(dummy))
  }

  test("Task.failed should expose typed error") { implicit s =>
    val dummy = "dummy"
    val r = Task.raiseError(dummy).failed.runSyncStep
    assertEquals(r, Right(dummy))
  }

  test("Task.failed should fail for unexpected error") { implicit s =>
    val dummy = DummyException("dummy")

    intercept[DummyException] {
      Task.terminate(dummy).failed.runSyncStep
    }
    ()
  }

  test("Task.failed should fail for successful values") { implicit s =>
    intercept[NoSuchElementException] {
      Task.now(10).failed.runSyncStep
    }
    ()
  }

  test("Task#onErrorRecover should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): Task[String, Int]).onErrorRecover { case _: String => 99 }
    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("Task#onErrorRecover should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (Task.raiseError(ex): Task[String, Int]).onErrorRecover {
      case "dummy" => 99
    }

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("Task#onErrorRecover should not recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = (Task.terminate(ex): Task[String, Int]).onErrorRecover {
      case _ => 99
    }

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task#onErrorRecover should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = Task[Int](if (1 == 1) throw ex1 else 1).onErrorRecover { case _ => throw ex2 }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("Task#onErrorHandle should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): Task[String, Int]).onErrorHandle { _: String =>
      99
    }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("Task#onErrorHandle should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (Task.raiseError(ex): Task[String, Int]).onErrorHandle { _: String =>
      99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("Task#onErrorHandle should not recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = Task.evalTotal[Int](if (1 == 1) throw ex else 1).onErrorHandle { _ =>
      99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task#onErrorHandle should protect against user code") { implicit s =>
    val ex1 = "one"
    val ex2 = DummyException("two")

    val task = (Task.raiseError(ex1): Task[String, Int]).onErrorHandle { _ =>
      throw ex2
    }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("Task.onErrorFallbackTo should mirror source onSuccess") { implicit s =>
    val task = UIO.evalAsync(1).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("Task.onErrorFallbackTo should fallback to backup onError") { implicit s =>
    val ex = "dummy"
    val task = (Task.raiseError(ex): Task[String, Int]).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("Task.onErrorFallbackTo should not fallback to backup onTermination") { implicit s =>
    val ex = DummyException("dummy")
    val task = (Task.terminate(ex): Task[String, Int]).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task.onErrorFallbackTo should protect against user code") { implicit s =>
    val ex = "dummy"
    val err = DummyException("unexpected")
    val task = (Task.raiseError(ex): Task[String, Int]).executeAsync.onErrorFallbackTo(Task.defer(throw err))
    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Left(err))))
  }

  test("Task.onErrorFallbackTo should be cancelable if the ExecutionModel allows it") { implicit s =>
    def recursive(): UIO[Int] = {
      Task.raiseError("dummy").onErrorFallbackTo(Task.deferTotal(recursive()))
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
    val task = UIO.eval { tries += 1; 1 }.onErrorRestart(10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestart should retry onError") { implicit s =>
    val ex = "dummy"
    var tries = 0
    val task = Task.deferTotal { tries += 1; if (tries < 5) Task.raiseError(ex) else Task.now(1) }.onErrorRestart(10)
    val f = task.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("Task.onErrorRestart should emit onError after max retries") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; throw ex }.onErrorRestart(10)
    val f = task.attempt.runToFuture

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

  test("Task.onErrorRestart should not restart on terminate error") { implicit s =>
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

    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestartIf should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; if (tries < 5) throw ex else 1 }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 5)
  }

  test("Task.onErrorRestartIf should emit onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = Task.eval { tries += 1; throw ex }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(ex)))
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

  test("Task.onErrorRestartIf should not restart on terminate error") { implicit s =>
    var tries = 0
    val ex = DummyException("dummy")
    val task: UIO[Int] = Task.eval { tries += 1; throw ex }.hideErrors
    val f = task.onErrorRestartIf(_ => tries < 5).runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 1)
  }

  test("Task.onErrorRestartIf should restart on typed error") { implicit s =>
    var tries = 0
    val task = Task.unit.flatMap { _ =>
      tries += 1; if (tries < 5) Task.raiseError("error") else Task.pure(1)
    }
    val f = task.onErrorRestartIf(_ == "error").attempt.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("Task#onErrorRecoverWith should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): Task[Int, Int]).onErrorRecoverWith { case _: Int => UIO.evalAsync(99) }
    val f = task.attempt.runToFuture
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
    assertEquals(f.value, Some(Success(99)))
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
    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("Task#onErrorRecoverWith should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val task = Task[Int](throw dummy).onErrorRecoverWith { case _: TimeoutException => Task.now(10) }
    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.eval.flatMap.redeemCauseWith should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = UIO.eval(1).flatMap(_ => throw dummy).redeemCauseWith(_ => Task.now(10), Task.now)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("Task.evalAsync.flatMap.redeemCauseWith should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = UIO.evalAsync(1).flatMap(_ => throw dummy).redeemCauseWith(_ => Task.now(10), Task.now)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("Task.now.onErrorRecoverWith should be stack safe") { implicit s =>
    val ex = 1111
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: Task[Int, Int], n: Int): Task[Int, Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => Task.now(count)
        case `ex` => loop(task, n - 1)
      }

    val task = loop(Task.raiseError(ex), count)
    val result = task.attempt.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  test("onErrorRecoverWith should be stack safe for loop with executeAsync boundaries") { implicit s =>
    val ex = 2222
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: Task[Int, Int], n: Int): Task[Int, Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => UIO.evalAsync(count)
        case `ex` => loop(task, n - 1).executeAsync
      }

    val task = loop(Task.raiseError(ex), count)
    val result = task.attempt.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  test("Task.onErrorRestartLoop works for success") { implicit s =>
    val dummy = DummyException("dummy1")
    var tries = 0
    val source = Task.eval {
      tries += 1
      if (tries < 5) throw dummy
      tries
    }

    val task = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
      if (maxRetries > 0)
        retry(maxRetries - 1)
      else
        Task.raiseError(err)
    }

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(5)))
    assertEquals(tries, 5)
  }

  test("Task.onErrorRestartLoop can throw different error") { implicit s =>
    val dummy = DummyException("dummy")
    val timeout = new TimeoutException("timeout")
    val source = Task.eval[Int] { throw dummy }

    val task = source.onErrorRestartLoop(5) { (_, maxRetries, retry) =>
      if (maxRetries > 0)
        retry(maxRetries - 1)
      else
        Task.raiseError(timeout)
    }

    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(timeout)))
  }

  test("Task.onErrorRestartLoop can rethrow") { implicit s =>
    val dummy = DummyException("dummy")
    val source = Task.eval[Int] { throw dummy }

    val task = source.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
      if (maxRetries > 0)
        retry(maxRetries - 1)
      else
        Task.raiseError(err)
    }

    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }
}
