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
  test("BIO.attempt should expose typed error") { implicit s =>
    val dummy = "dummy"
    val r = BIO.raiseError(dummy).attempt.runSyncStep
    assertEquals(r, Right(Left(dummy)))
  }

  test("BIO.attempt should not expose unexpected error") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.terminate(dummy).attempt.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.attempt should work for successful values") { implicit s =>
    val r = BIO.now(10).attempt.runSyncStep
    assertEquals(r, Right(Right(10)))
  }

  test("BIO.rethrow should turn Left to typed error") { implicit s =>
    val dummy = "dummy"
    val f = BIO.now(Left(dummy)).rethrow.attempt.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.rethrow should turn Right to success value") { implicit s =>
    val dummy = "dummy"
    val r = BIO.now(Right(dummy)).rethrow.runSyncStep
    assertEquals(r, Right(dummy))
  }

  test("BIO.failed should expose typed error") { implicit s =>
    val dummy = "dummy"
    val r = BIO.raiseError(dummy).failed.runSyncStep
    assertEquals(r, Right(dummy))
  }

  test("BIO.failed should fail for unexpected error") { implicit s =>
    val dummy = DummyException("dummy")

    intercept[DummyException] {
      BIO.terminate(dummy).failed.runSyncStep
    }
  }

  test("BIO.failed should fail for successful values") { implicit s =>
    intercept[NoSuchElementException] {
      BIO.now(10).failed.runSyncStep
    }
  }

  test("BIO#onErrorRecover should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): BIO[String, Int]).onErrorRecover { case _: String => 99 }
    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO#onErrorRecover should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (BIO.raiseError(ex): BIO[String, Int]).onErrorRecover {
      case "dummy" => 99
    }

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(99))))
  }

  test("BIO#onErrorRecover should not recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = (BIO.terminate(ex): BIO[String, Int]).onErrorRecover {
      case _ => 99
    }

    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO#onErrorRecover should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = BIO[Int](if (1 == 1) throw ex1 else 1).onErrorRecover { case _ => throw ex2 }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("BIO#onErrorHandle should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): BIO[String, Int]).onErrorHandle { _: String =>
      99
    }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO#onErrorHandle should recover typed error") { implicit s =>
    val ex = "dummy"
    val task = (BIO.raiseError(ex): BIO[String, Int]).onErrorHandle { _: String =>
      99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("BIO#onErrorHandle should not recover unexpected error") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.evalTotal[Int](if (1 == 1) throw ex else 1).onErrorHandle { _ =>
      99
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO#onErrorHandle should protect against user code") { implicit s =>
    val ex1 = "one"
    val ex2 = DummyException("two")

    val task = (BIO.raiseError(ex1): BIO[String, Int]).onErrorHandle { _ =>
      throw ex2
    }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("BIO.onErrorFallbackTo should mirror source onSuccess") { implicit s =>
    val task = UIO.evalAsync(1).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.onErrorFallbackTo should fallback to backup onError") { implicit s =>
    val ex = "dummy"
    val task = (BIO.raiseError(ex): BIO[String, Int]).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("BIO.onErrorFallbackTo should not fallback to backup onTermination") { implicit s =>
    val ex = DummyException("dummy")
    val task = (BIO.terminate(ex): BIO[String, Int]).onErrorFallbackTo(UIO.evalAsync(2))
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.onErrorFallbackTo should protect against user code") { implicit s =>
    val ex = "dummy"
    val err = DummyException("unexpected")
    val task = (BIO.raiseError(ex): BIO[String, Int]).executeAsync.onErrorFallbackTo(BIO.defer(throw err))
    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Left(err))))
  }

  test("BIO.onErrorFallbackTo should be cancelable if the ExecutionModel allows it") { implicit s =>
    def recursive(): UIO[Int] = {
      BIO.raiseError("dummy").onErrorFallbackTo(BIO.deferTotal(recursive()))
    }

    val task = recursive().executeWithOptions(_.enableAutoCancelableRunLoops)
    val f = task.runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.onErrorRestart should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val task = UIO.eval { tries += 1; 1 }.onErrorRestart(10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 1)
  }

  test("BIO.onErrorRestart should retry onError") { implicit s =>
    val ex = "dummy"
    var tries = 0
    val task = BIO.deferTotal { tries += 1; if (tries < 5) BIO.raiseError(ex) else BIO.now(1) }.onErrorRestart(10)
    val f = task.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("BIO.onErrorRestart should emit onError after max retries") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = BIO.eval { tries += 1; throw ex }.onErrorRestart(10)
    val f = task.attempt.runToFuture

    assertEquals(f.value, Some(Success(Left(ex))))
    assertEquals(tries, 11)
  }

  test("BIO.onErrorRestart should be cancelable if ExecutionModel permits") { implicit s =>
    val batchSize = (s.executionModel.recommendedBatchSize * 2).toLong
    val task = BIO[Int](throw DummyException("dummy"))
      .onErrorRestart(batchSize)

    val f = task.executeWithOptions(_.enableAutoCancelableRunLoops).runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.onErrorRestart should not restart on terminate error") { implicit s =>
    var tries = 0
    val ex = DummyException("dummy")
    val task = BIO.eval { tries += 1; throw ex }.hideErrors.onErrorRestart(5)
    val f = task.runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 1)
  }

  test("BIO.onErrorRestartIf should mirror the source onSuccess") { implicit s =>
    var tries = 0
    val task = BIO.eval { tries += 1; 1 }.onErrorRestartIf(_ => tries < 10)
    val f = task.runToFuture

    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 1)
  }

  test("BIO.onErrorRestartIf should retry onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = BIO.eval { tries += 1; if (tries < 5) throw ex else 1 }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
    assertEquals(tries, 5)
  }

  test("BIO.onErrorRestartIf should emit onError") { implicit s =>
    val ex = DummyException("dummy")
    var tries = 0
    val task = BIO.eval { tries += 1; throw ex }
      .onErrorRestartIf(_ => tries <= 10)

    val f = task.runToFuture
    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 11)
  }

  test("BIO.onErrorRestartIf should be cancelable if ExecutionModel permits") { implicit s =>
    val task = BIO[Int](throw DummyException("dummy")).onErrorRestartIf(_ => true)
    val f = task.executeWithOptions(_.enableAutoCancelableRunLoops).runToFuture
    assertEquals(f.value, None)

    // cancelling after scheduled for execution, but before execution
    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("BIO.onErrorRestartIf should not restart on terminate error") { implicit s =>
    var tries = 0
    val ex = DummyException("dummy")
    val task: UIO[Int] = BIO.eval { tries += 1; throw ex }.hideErrors
    val f = task.onErrorRestartIf(_ => tries < 5).runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(tries, 1)
  }

  test("BIO.onErrorRestartIf should restart on typed error") { implicit s =>
    var tries = 0
    val task = BIO.unit.flatMap { _ =>
      tries += 1; if (tries < 5) BIO.raiseError("error") else BIO.pure(1)
    }
    val f = task.onErrorRestartIf(_ == "error").attempt.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
    assertEquals(tries, 5)
  }

  test("BIO#onErrorRecoverWith should mirror source on success") { implicit s =>
    val task = (UIO.evalAsync(1): BIO[Int, Int]).onErrorRecoverWith { case _: Int => UIO.evalAsync(99) }
    val f = task.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO#onErrorRecoverWith should recover") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO[Int](throw ex).onErrorRecoverWith {
      case _: DummyException => BIO.evalAsync(99)
    }

    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(99)))
  }

  test("BIO#onErrorRecoverWith should protect against user code") { implicit s =>
    val ex1 = DummyException("one")
    val ex2 = DummyException("two")

    val task = BIO[Int](throw ex1).onErrorRecoverWith { case _ => throw ex2 }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(ex2)))
  }

  test("BIO#onErrorRecoverWith has a cancelable fallback") { implicit s =>
    val task = BIO[Int](throw DummyException("dummy")).onErrorRecoverWith {
      case _: DummyException => BIO.evalAsync(99).delayExecution(1.second)
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    // cancelling after scheduled for execution, but before execution
    s.tick(); assertEquals(f.value, None)

    f.cancel(); s.tick()
    assertEquals(f.value, None)
  }

  test("BIO#onErrorRecover should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO[Int](throw dummy).onErrorRecover { case _: TimeoutException => 10 }
    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO#onErrorRecoverWith should emit error if not matches") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO[Int](throw dummy).onErrorRecoverWith { case _: TimeoutException => Task.now(10) }
    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.eval.flatMap.redeemCauseWith should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = UIO.eval(1).flatMap(_ => throw dummy).redeemCauseWith(_ => BIO.now(10), BIO.now)

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.evalAsync.flatMap.redeemCauseWith should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = UIO.evalAsync(1).flatMap(_ => throw dummy).redeemCauseWith(_ => BIO.now(10), BIO.now)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.now.onErrorRecoverWith should be stack safe") { implicit s =>
    val ex = 1111
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: BIO[Int, Int], n: Int): BIO[Int, Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => BIO.now(count)
        case `ex` => loop(task, n - 1)
      }

    val task = loop(BIO.raiseError(ex), count)
    val result = task.attempt.runToFuture

    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  test("onErrorRecoverWith should be stack safe for loop with executeAsync boundaries") { implicit s =>
    val ex = 2222
    val count = if (Platform.isJVM) 50000 else 5000

    def loop(task: BIO[Int, Int], n: Int): BIO[Int, Int] =
      task.onErrorRecoverWith {
        case `ex` if n <= 0 => UIO.evalAsync(count)
        case `ex` => loop(task, n - 1).executeAsync
      }

    val task = loop(BIO.raiseError(ex), count)
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
