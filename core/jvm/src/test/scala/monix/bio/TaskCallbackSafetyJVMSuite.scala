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

import java.util.concurrent.{CountDownLatch, TimeUnit}

import minitest.SimpleTestSuite
import monix.execution.exceptions.{CallbackCalledMultipleTimesException, DummyException}
import monix.execution.schedulers.SchedulerService
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskCallbackSafetyJVMSuite extends SimpleTestSuite {

  val isTravis = {
    System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true"
  }

  val WORKERS = 10
  val RETRIES = if (!isTravis) 1000 else 100

  test("IO.async has a safe callback") {
    runConcurrentCallbackTest(IO.async)
  }

  test("IO.async0 has a safe callback") {
    runConcurrentCallbackTest(f => IO.async0((_, cb) => f(cb)))
  }

  test("IO.asyncF has a safe callback") {
    runConcurrentCallbackTest(f => IO.asyncF(cb => IO.evalTotal(f(cb))))
  }

  test("IO.cancelable has a safe callback") {
    runConcurrentCallbackTest(f =>
      IO.cancelable[String, Int] { cb =>
        f(cb); IO.evalTotal(())
      }
    )
  }

  test("IO.cancelable0 has a safe callback") {
    runConcurrentCallbackTest(f =>
      IO.cancelable0[String, Int] { (_, cb) =>
        f(cb); IO.evalTotal(())
      }
    )
  }

  def runConcurrentCallbackTest(create: (BiCallback[String, Int] => Unit) => IO[String, Int]): Unit = {
    def run(trigger: BiCallback[String, Int] => Unit): Unit = {
      implicit val sc: SchedulerService = Scheduler.io("task-callback-safety")
      try {
        for (_ <- 0 until RETRIES) {
          val task = create { cb =>
            runConcurrently(sc)(trigger(cb))
          }
          val latch = new CountDownLatch(1)
          var effect = 0

          task.runAsync {
            case Right(_) =>
              effect += 1
              latch.countDown()
            case Left(_) =>
              effect += 1
              latch.countDown()
          }

          await(latch)
          assertEquals(effect, 1)
        }
      } finally {
        sc.shutdown()
        assert(sc.awaitTermination(10.seconds), "io.awaitTermination")
      }
    }

    run(_.tryOnSuccess(1))
    run(_.tryApply(Right(1)))
//    run(_.tryApply(Success(Right(1))))

    run { cb =>
      try cb.onSuccess(1)
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb(Right[Cause[String], Int](1))
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb(Success(Right(1)))
      catch { case _: CallbackCalledMultipleTimesException => () }
    }

    val dummyMsg = "dummy"
    val dummy = DummyException("dummy")

    run(_.tryOnError(dummyMsg))
    run(_.tryOnTermination(dummy))
    run(_.tryApply(Left(Cause.Error(dummyMsg))))
//    run(_.tryApply(Failure(dummy)))

    run { cb =>
      try cb.onError(dummyMsg)
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb.onTermination(dummy)
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb(Left(Cause.Error(dummyMsg)))
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb(Success(Left(dummyMsg)))
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
    run { cb =>
      try cb(Failure[Either[String, Int]](dummy))
      catch { case _: CallbackCalledMultipleTimesException => () }
    }
  }

  def runConcurrently(sc: Scheduler)(f: => Unit): Unit = {
    val latchWorkersStart = new CountDownLatch(WORKERS)
    val latchWorkersFinished = new CountDownLatch(WORKERS)

    for (_ <- 0 until WORKERS) {
      sc.execute { () =>
        latchWorkersStart.countDown()
        try {
          f
        } finally {
          latchWorkersFinished.countDown()
        }
      }
    }

    await(latchWorkersStart)
    await(latchWorkersFinished)
  }

  def await(latch: CountDownLatch): Unit = {
    val seconds = 10L
    assert(latch.await(seconds, TimeUnit.SECONDS), s"latch.await($seconds seconds)")
  }
}
