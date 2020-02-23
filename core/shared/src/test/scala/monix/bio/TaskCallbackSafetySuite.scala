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

import monix.bio.internal.BiCallback
import monix.execution.exceptions.CallbackCalledMultipleTimesException
import monix.execution.schedulers.TestScheduler

import scala.util.{Failure, Success}

object TaskCallbackSafetySuite extends BaseTestSuite {
  test("BIO.async's callback can be called multiple times") { implicit sc =>
    runTestCanCallMultipleTimes(BIO.async)
  }

  test("BIO.async0's callback can be called multiple times") { implicit sc =>
    runTestCanCallMultipleTimes(r => BIO.async0((_, cb) => r(cb)))
  }

  test("BIO.asyncF's callback can be called multiple times") { implicit sc =>
    runTestCanCallMultipleTimes(r => BIO.asyncF(cb => BIO.evalTotal(r(cb))))
  }

  test("BIO.cancelable's callback can be called multiple times") { implicit sc =>
    runTestCanCallMultipleTimes(r =>
      BIO.cancelable[Int, Int] { cb =>
        r(cb); BIO.unit
      }
    )
  }

  test("BIO.cancelable0's callback can be called multiple times") { implicit sc =>
    runTestCanCallMultipleTimes(r =>
      BIO.cancelable0[Int, Int] { (_, cb) =>
        r(cb); BIO.unit
      }
    )
  }

  test("BIO.async's register throwing is signaled as error") { implicit sc =>
    runTestRegisterCanThrow(BIO.async)
  }

  test("BIO.async0's register throwing is signaled as error") { implicit sc =>
    runTestRegisterCanThrow(r => BIO.async0((_, cb) => r(cb)))
  }

  test("BIO.asyncF's register throwing is signaled as error (1)") { implicit sc =>
    runTestRegisterCanThrow(r => BIO.asyncF(cb => BIO.evalTotal(r(cb))))
  }

  test("BIO.asyncF's register throwing is signaled as error (2)") { implicit sc =>
    runTestRegisterCanThrow(r => BIO.asyncF(cb => { r(cb); BIO.unit }))
  }

  test("BIO.cancelable's register throwing is signaled as error") { implicit sc =>
    runTestRegisterCanThrow(r =>
      BIO.cancelable[Int, Int] { cb =>
        r(cb); BIO.unit
      }
    )
  }

  test("BIO.cancelable0's register throwing is signaled as error") { implicit sc =>
    runTestRegisterCanThrow(r =>
      BIO.cancelable0[Int, Int] { (_, cb) =>
        r(cb); BIO.unit
      }
    )
  }

  test("BIO.async's register throwing, after result, is reported") { implicit sc =>
    runTestRegisterThrowingCanBeReported(BIO.async)
  }

  test("BIO.async0's register throwing, after result, is reported") { implicit sc =>
    runTestRegisterThrowingCanBeReported(r => BIO.async0((_, cb) => r(cb)))
  }

  test("BIO.asyncF's register throwing, after result, is reported (1)") { implicit sc =>
    runTestRegisterThrowingCanBeReported(r => BIO.asyncF(cb => BIO.evalTotal(r(cb))))
  }

  test("BIO.asyncF's register throwing, after result, is reported (2)") { implicit sc =>
    runTestRegisterThrowingCanBeReported(r => BIO.asyncF(cb => { r(cb); BIO.unit }))
  }

  test("BIO.cancelable's register throwing, after result, is reported") { implicit sc =>
    runTestRegisterThrowingCanBeReported(r =>
      BIO.cancelable[Int, Int] { cb =>
        r(cb); BIO.unit
      }
    )
  }

  test("BIO.cancelable0's register throwing, after result, is reported") { implicit sc =>
    runTestRegisterThrowingCanBeReported(r =>
      BIO.cancelable0[Int, Int] { (_, cb) =>
        r(cb); BIO.unit
      }
    )
  }

  def runTestRegisterCanThrow(
    create: (BiCallback[Int, Int] => Unit) => BIO[Int, Int]
  )(implicit sc: TestScheduler): Unit = {

    var effect = 0
    val task = create { _ =>
      throw WrappedEx(10)
    }

    task.runAsync {
      case Right(_) => ()
      case Left(Cause.Error(nr)) => effect += nr
      case Left(Cause.Termination(ex)) =>
        ex match {
          case WrappedEx(nr) => effect += nr
          case other => throw other
        }
    }

    sc.tick()
    assertEquals(effect, 10)
    assertEquals(sc.state.lastReportedError, null)
  }

  def runTestRegisterThrowingCanBeReported(
    create: (BiCallback[Int, Int] => Unit) => BIO[Int, Int]
  )(implicit sc: TestScheduler): Unit = {

    var effect = 0
    val task = create { cb =>
      cb.onSuccess(1)
      throw WrappedEx(10)
    }

    task.runAsync {
      case Right(_) => effect += 1
      case Left(Cause.Error(nr)) => effect += nr
      case Left(Cause.Termination(ex)) =>
        ex match {
          case WrappedEx(nr) => effect += nr
          case other => throw other
        }
    }

    sc.tick()
    assertEquals(effect, 1)
    assertEquals(sc.state.lastReportedError, WrappedEx(10))
  }

  def runTestCanCallMultipleTimes(
    create: (BiCallback[Int, Int] => Unit) => BIO[Int, Int]
  )(implicit sc: TestScheduler): Unit = {

    def run(expected: Int)(trySignal: BiCallback[Int, Int] => Boolean) = {
      var effect = 0
      val task = create { cb =>
        assert(trySignal(cb))
        assert(!trySignal(cb))
        assert(!trySignal(cb))
      }

      task.runAsync {
        case Right(nr) => effect += nr
        case Left(Cause.Error(nr)) => effect += nr
        case Left(Cause.Termination(ex)) =>
          ex match {
            case WrappedEx(nr) => effect += nr
            case other => throw other
          }
      }

      sc.tick()
      assertEquals(effect, expected)
      assertEquals(sc.state.lastReportedError, null)
    }

    run(1)(_.tryOnSuccess(1))
    run(1)(_.tryApply(Success(Right(1))))
    run(1)(_.tryApply(Right(1)))

    run(1)(cb =>
      try {
        cb.onSuccess(1); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
    run(1)(cb =>
      try {
        cb(Right(1)); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
    run(1)(cb =>
      try {
        cb(Success(Right(1))); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )

    run(10)(_.tryOnTermination(WrappedEx(10)))
    run(10)(_.tryOnError(10))
    run(10)(_.tryApply(Failure(WrappedEx(10))))
    run(10)(_.tryApply(Left(10)))

    run(10)(cb =>
      try {
        cb.onTermination(WrappedEx(10)); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
    run(10)(cb =>
      try {
        cb.onError(10); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
    run(10)(cb =>
      try {
        cb(Left(10)); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
    run(10)(cb =>
      try {
        cb(Failure(WrappedEx(10))); true
      } catch { case _: CallbackCalledMultipleTimesException => false }
    )
  }

  case class WrappedEx(nr: Int) extends RuntimeException
}
