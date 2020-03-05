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

import minitest.TestSuite
import monix.execution.exceptions.{CallbackCalledMultipleTimesException, DummyException}
import monix.execution.schedulers.TestScheduler

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

object TaskCallbackSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(env: TestScheduler): Unit =
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")

  case class TestCallback(
    success: Int => Unit = _ => (),
    error: String => Unit = _ => (),
    terminalError: Throwable => Unit = _ => ()
  ) extends BiCallback[String, Int] {

    var successCalled = false
    var errorCalled = false
    var terminationCalled = false

    override def onSuccess(value: Int): Unit = {
      successCalled = true
      success(value)
    }

    override def onError(ex: String): Unit = {
      errorCalled = true
      error(ex)
    }

    override def onTermination(e: Throwable): Unit = {
      terminationCalled = true
      terminalError(e)
    }
  }

  test("onValue should invoke onSuccess") { implicit s =>
    val callback = TestCallback()
    callback.onSuccess(1)
    assert(callback.successCalled)
  }

  test("apply Success(Right(value))  should invoke onSuccess") { implicit s =>
    val callback = TestCallback()
    callback(Success(Right(1)))
    assert(callback.successCalled)
  }

  test("apply Success(Left(value)) should invoke onError") { implicit s =>
    val callback = TestCallback()
    callback(Success(Left("error")))
    assert(callback.errorCalled)
  }

  test("apply Failure(ex) should invoke onTermination") { implicit s =>
    val callback = TestCallback()
    callback(Failure[Either[String, Int]](new IllegalStateException()))
    assert(callback.terminationCalled)
  }

  test("contramap should pipe onError") { implicit s =>
    var result = Option.empty[Try[Either[String, Int]]]
    val callback = TestCallback({ v =>
      result = Some(Success(Right(v)))
    }, { e =>
      result = Some(Success(Left(e)))
    }, { e =>
      result = Some(Failure(e))
    })

    val stringCallback = callback.contramap[String](_.toInt)
    val dummy = DummyException("dummy")

    stringCallback.onTermination(dummy)
    assertEquals(result, Some(Failure(dummy)))
  }

  test("contramap should invoke function before invoking callback") { implicit s =>
    val callback = TestCallback()
    val stringCallback = callback.contramap[String](_.toInt)
    stringCallback.onSuccess("1")
    assert(callback.successCalled)
  }

  test("BiCallback.fromPromise (success)") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)

    cb.onSuccess(1)
    assertEquals(p.future.value, Some(Success(Right(1))))
    intercept[IllegalStateException] { cb.onSuccess(2) }
    intercept[CallbackCalledMultipleTimesException] { cb.onSuccess(2) }
  }

  test("BiCallback.fromPromise (failure)") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)

    val dummy = "dummy"
    cb.onError(dummy)

    assertEquals(p.future.value, Some(Success(Left(dummy))))
    intercept[IllegalStateException] { cb.onSuccess(1) }
    intercept[CallbackCalledMultipleTimesException] { cb.onSuccess(1) }
  }

  test("BiCallback.fromPromise (terminal failure)") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)

    val dummy = DummyException("dummy")
    cb.onTermination(dummy)

    assertEquals(p.future.value, Some(Failure(dummy)))
    intercept[IllegalStateException] { cb.onSuccess(1) }
    intercept[CallbackCalledMultipleTimesException] { cb.onSuccess(1) }
  }

  test("BiCallback.fromPromise(Cause) (terminal failure)") { _ =>
    val p = Promise[Either[Cause[String], Int]]()
    val cb = BiCallback.fromPromise(p)

    val dummy = DummyException("dummy")
    cb.onTermination(dummy)

    assertEquals(p.future.value, Some(Failure(dummy)))
    intercept[IllegalStateException] { cb.onSuccess(1) }
    intercept[CallbackCalledMultipleTimesException] { cb.onSuccess(1) }
  }


  test("BiCallback.empty reports errors") { implicit s =>
    val empty = BiCallback[Throwable].empty[Int]
    val dummy = DummyException("dummy")
    empty.onError(dummy)

    assertEquals(s.state.lastReportedError, dummy)
  }

  test("BiCallback.empty reports terminal errors") { implicit s =>
    val empty = BiCallback[Throwable].empty[Int]
    val dummy = DummyException("dummy")
    empty.onTermination(dummy)

    assertEquals(s.state.lastReportedError, dummy)
  }

  test("BiCallback.safe protects against errors in onSuccess") { implicit s =>
    val dummy = DummyException("dummy")
    var effect = 0

    val cb = new BiCallback[String, Int] {
      def onSuccess(value: Int): Unit = {
        effect += 1
        throw dummy
      }
      def onError(ex: String): Unit =
        throw new IllegalStateException("onError")

      override def onTermination(e: Throwable): Unit =
        throw new IllegalStateException("onTermination")
    }

    val safe = BiCallback[String].safe(cb)
    assert(safe.tryOnSuccess(1), "safe.tryOnSuccess(1)")

    assertEquals(effect, 1)
    assertEquals(s.state.lastReportedError, dummy)

    assert(!safe.tryOnSuccess(1))
    assertEquals(effect, 1)
  }

  test("BiCallback.safe protects against errors in onError") { implicit s =>
    val dummy1 = DummyException("dummy1")
    val dummy2 = DummyException("dummy2")
    var effect = 0

    val cb = new BiCallback[Throwable, Int] {
      def onSuccess(value: Int): Unit =
        throw new IllegalStateException("onSuccess")

      def onError(ex: Throwable): Unit = {
        effect += 1
        throw dummy1
      }

      override def onTermination(e: Throwable): Unit =
        throw new IllegalStateException("onTermination")
    }

    val safe = BiCallback[Throwable].safe(cb)
    assert(safe.tryOnError(dummy2), "safe.onError(dummy2)")

    assertEquals(effect, 1)
    assertEquals(s.state.lastReportedError, dummy1)

    assert(!safe.tryOnError(dummy2), "!safe.onError(dummy2)")
    assertEquals(effect, 1)
  }

  test("BiCallback.safe protects against errors in onTermination") { implicit s =>
    val dummy1 = DummyException("dummy1")
    val dummy2 = DummyException("dummy2")
    var effect = 0

    val cb = new BiCallback[Throwable, Int] {
      def onSuccess(value: Int): Unit =
        throw new IllegalStateException("onSuccess")

      def onError(ex: Throwable): Unit =
        throw new IllegalStateException("onError")

      override def onTermination(e: Throwable): Unit = {
        effect += 1
        throw dummy1
      }
    }

    val safe = BiCallback[Throwable].safe(cb)
    assert(safe.tryOnTermination(dummy2), "safe.onTermination(dummy2)")

    assertEquals(effect, 1)
    assertEquals(s.state.lastReportedError, dummy1)

    assert(!safe.tryOnTermination(dummy2), "!safe.onTermination(dummy2)")
    assertEquals(effect, 1)
  }

  test("callback(Right(a))") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)

    cb(Right[Cause[String], Int](10))
    assertEquals(p.future.value, Some(Success(Right(10))))
  }

  test("callback(Left(e))") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)
    val err = "dummy"

    cb(Left(Cause.Error(err)))
    assertEquals(p.future.value, Some(Success(Left(err))))
  }

  test("callback(Left(ex))") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback.fromPromise(p)
    val err = DummyException("dummy")

    cb(Left(Cause.Termination(err)))
    assertEquals(p.future.value, Some(Failure(err)))
  }

  test("fromAttempt success") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback[String].fromAttempt[Int] {
      case Right(a) => p.success(Right(a))
      case Left(Cause.Error(e)) => p.success(Left(e))
      case Left(Cause.Termination(e)) => p.failure(e)
    }

    cb.onSuccess(10)
    assertEquals(p.future.value, Some(Success(Right(10))))
  }

  test("fromAttempt error") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback[String].fromAttempt[Int] {
      case Right(a) => p.success(Right(a))
      case Left(Cause.Error(e)) => p.success(Left(e))
      case Left(Cause.Termination(e)) => p.failure(e)
    }

    val err = "dummy"
    cb.onError(err)
    assertEquals(p.future.value, Some(Success(Left(err))))
  }

  test("fromAttempt termination") { _ =>
    val p = Promise[Either[String, Int]]()
    val cb = BiCallback[String].fromAttempt[Int] {
      case Right(a) => p.success(Right(a))
      case Left(Cause.Error(e)) => p.success(Left(e))
      case Left(Cause.Termination(e)) => p.failure(e)
    }

    val err = DummyException("dummy")
    cb.onTermination(err)
    assertEquals(p.future.value, Some(Failure(err)))
  }
}
