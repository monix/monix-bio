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

import cats.effect.concurrent.Deferred
import cats.laws._
import cats.laws.discipline._
import monix.execution.exceptions.{CompositeException, DummyException}
import monix.execution.internal.Platform

import scala.util.{Failure, Success}
import scala.concurrent.duration._

object TaskBracketSuite extends BaseTestSuite {
  test("equivalence with onErrorHandleWith") { implicit sc =>
    check2 { (task: IO[String, Int], f: String => UIO[Unit]) =>
      val expected = task.onErrorHandleWith(e => f(e) >> IO.raiseError(e))
      val received = task.bracketE(IO.now) {
        case (_, Left(Some(e))) => e.fold(IO.terminate, f)
        case (_, _) => UIO.unit
      }
      received <-> expected
    }
  }

  test("equivalence with flatMap + redeemWith") { implicit sc =>
    check3 { (acquire: IO[Int, Int], f: Int => IO[Int, Int], release: Int => UIO[Unit]) =>
      val expected = acquire.flatMap { a =>
        f(a).redeemWith(
          e => release(a) >> IO.raiseError(e),
          s => release(a) >> IO.pure(s)
        )
      }

      val received = acquire.bracket(f)(release)
      received <-> expected
    }
  }

  test("equivalence with flatMap + redeemCauseWith") { implicit sc =>
    check3 { (acquire: IO[Int, Int], f: Int => IO[Int, Int], release: Int => UIO[Unit]) =>
      val expected = acquire.flatMap { a =>
        f(a).redeemCauseWith(
          e => release(a) >> e.fold(IO.terminate, IO.raiseError),
          s => release(a) >> IO.pure(s)
        )
      }

      val received = acquire.bracket(f)(release)
      received <-> expected
    }
  }

  test("use is protected against user error") { implicit sc =>
    val dummy = new DummyException("dummy")
    var input: Option[(Int, Either[Option[Cause[Int]], Int])] = Option.empty[(Int, Either[Option[Cause[Int]], Int])]

    val task = UIO.evalAsync(1).bracketE[Nothing, Int](_ => throw dummy) { case (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Cause.Termination(dummy))))))
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("release is evaluated on success") { implicit sc =>
    var input: Option[(Int, Either[Option[Cause[Int]], Int])] = Option.empty[(Int, Either[Option[Cause[Int]], Int])]

    val task = UIO.evalAsync(1).bracketE[Nothing, Int](x => UIO.evalAsync(x + 1)) { case (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Right(2))))
    assertEquals(f.value, Some(Success(2)))
  }

  test("release is evaluated on error") { implicit sc =>
    var input: Option[(Int, Either[Option[Cause[Int]], Int])] = Option.empty[(Int, Either[Option[Cause[Int]], Int])]

    val task = UIO.evalAsync(1).bracketE[Int, Int](_ => IO.raiseError[Int](-99)) { case (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.attempt.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Cause.Error(-99))))))
    assertEquals(f.value, Some(Success(Left(-99))))
  }

  test("release is evaluated on terminal error") { implicit sc =>
    val dummy = new DummyException("dummy")
    var input: Option[(Int, Either[Option[Cause[Int]], Int])] = Option.empty[(Int, Either[Option[Cause[Int]], Int])]

    val task: IO[Int, Int] = UIO.evalAsync(1).bracketE[Int, Int](_ => IO.terminate(dummy)) { case (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.attempt.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Cause.Termination(dummy))))))
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("release is evaluated on cancel") { implicit sc =>
    import scala.concurrent.duration._
    var input: Option[(Int, Either[Option[Cause[Int]], Int])] = Option.empty[(Int, Either[Option[Cause[Int]], Int])]

    val task = UIO
      .evalAsync(1)
      .bracketE[Nothing, Int](x => UIO.evalAsync(x + 1).delayExecution(1.second)) { case (a, i) =>
        UIO.eval { input = Some((a, i)) }
      }

    val f = task.runToFuture
    sc.tick()

    f.cancel()
    sc.tick(1.second)

    assertEquals(f.value, None)
    assertEquals(input, Some((1, Left(None))))
  }

  test("if both use and release throw, report release error, signal use error") { implicit sc =>
    val useError = new DummyException("use")
    val releaseError = new DummyException("release")

    val task = Task
      .evalAsync(1)
      .bracket { _ =>
        Task.raiseError[Int](useError)
      } { _ =>
        IO.terminate(releaseError)
      }

    val f = task.runToFuture
    sc.tick()

    f.value match {
      case Some(Failure(error)) =>
        if (Platform.isJVM) {
          assertEquals(error, useError)
          error.getSuppressed match {
            case Array(error2) =>
              assertEquals(error2, releaseError)
            case _ =>
              fail("Unexpected suppressed errors list: " + error.getSuppressed.toList)
          }
        } else
          error match {
            case CompositeException(Seq(`useError`, `releaseError`)) =>
              () // pass
            case _ =>
              fail(s"Unexpected error: $error")
          }

      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("bracket works with auto-cancelable run-loops") { implicit sc =>
    import concurrent.duration._

    var effect = 0
    val task = Task(1)
      .bracket(_ => Task.sleep(1.second))(_ => UIO(effect += 1))
      .executeWithOptions(_.enableAutoCancelableRunLoops)

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, None)

    f.cancel()
    assertEquals(f.value, None)
    assertEquals(effect, 1)
  }

  test("bracket is stack safe (1)") { implicit sc =>
    def loop(n: Int): Task[Unit] =
      if (n > 0)
        Task(n).bracket(n => Task(n - 1))(_ => UIO.unit).flatMap(loop)
      else
        Task.unit

    val cycles = if (Platform.isJVM) 100000 else 1000
    val f = loop(cycles).runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("bracket is stack safe (2)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val bracket = Task.unit.bracket(_ => Task.unit)(_ => UIO.unit)
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      acc.flatMap(_ => bracket)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("bracket is stack safe (3)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      acc.bracket(_ => Task.unit)(_ => UIO.unit)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("bracket is stack safe (4)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      Task.unit.bracket(_ => acc)(_ => UIO.unit)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("use is not evaluated on cancel") { implicit sc =>
    import scala.concurrent.duration._
    var use = false
    var release = false

    val task = Task
      .sleep(2.second)
      .bracket(_ => Task { use = true })(_ => UIO { release = true })

    val f = task.runToFuture
    sc.tick()

    f.cancel()
    sc.tick(2.second)

    assertEquals(f.value, None)
    assertEquals(use, false)

    assertEquals(release, true)
  }

  test("cancel should wait for already started finalizers on success") { implicit sc =>
    val fa = for {
      pa    <- Deferred[Task, Unit]
      fiber <- IO.unit.guarantee(pa.complete(()).hideErrors >> IO.sleep(1.second)).start
      _     <- pa.get
      _     <- fiber.cancel
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("cancel should wait for already started finalizers on failure") { implicit sc =>
    val dummy = new RuntimeException("dummy")

    val fa = for {
      pa    <- Deferred[Task, Unit]
      fiber <- IO.unit.guarantee(pa.complete(()).hideErrors >> IO.sleep(1.second) >> IO.terminate(dummy)).start
      _     <- pa.get
      _     <- fiber.cancel
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("cancel should wait for already started use finalizers") { implicit sc =>
    val fa = for {
      pa <- Deferred[Task, Unit]
      fibA <-
        IO.unit
          .bracket(_ => IO.unit.guarantee(pa.complete(()).hideErrors >> IO.sleep(2.second)))(_ => IO.unit)
          .start
      _ <- pa.get
      _ <- fibA.cancel
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(2.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("second cancel should wait for use finalizers") { implicit sc =>
    val fa = for {
      pa <- Deferred[Task, Unit]
      fiber <-
        IO.unit
          .bracket(_ => (pa.complete(()).hideErrors >> IO.never).guarantee(IO.sleep(2.second)))(_ => IO.unit)
          .start
      _ <- pa.get
      _ <- IO.race(fiber.cancel, fiber.cancel)
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(2.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("second cancel during acquire should wait for it and finalizers to complete") { implicit sc =>
    val fa = for {
      pa <- Deferred[Task, Unit]
      fiber <- (pa.complete(()).hideErrors >> IO.sleep(1.second))
        .bracket(_ => IO.unit)(_ => IO.sleep(1.second))
        .start
      _ <- pa.get
      _ <- IO.race(fiber.cancel, fiber.cancel)
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(1.second)
    assertEquals(f.value, None)

    sc.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("second cancel during acquire should wait for it and finalizers to complete (non-terminating)") { implicit sc =>
    val fa = for {
      pa <- Deferred[Task, Unit]
      fiber <- (pa.complete(()).hideErrors >> IO.sleep(1.second))
        .bracket(_ => IO.unit)(_ => IO.never)
        .start
      _ <- pa.get
      _ <- IO.race(fiber.cancel, fiber.cancel)
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, None)

    sc.tick(1.day)
    assertEquals(f.value, None)
  }

  test("Multiple cancel should not hang") { implicit sc =>
    val fa = for {
      fiber <- IO.sleep(1.second).start
      _     <- fiber.cancel
      _     <- fiber.cancel
    } yield ()

    val f = fa.runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("bracket can be canceled while failing to acquire") { implicit sc =>
    val task = (IO.sleep(2.second) >> IO.raiseError[Unit](DummyException("BOOM")))
      .bracket(_ => Task.unit)(_ => IO.unit)

    val cancelToken = task.runAsyncF(_ => ())

    sc.tick(1.second)
    val f = cancelToken.runToFuture

    sc.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("bracket can be canceled while terminating") { implicit sc =>
    val task = (IO.sleep(2.second) >> IO.terminate(DummyException("BOOM")))
      .bracket(_ => Task.unit)(_ => IO.unit)

    val cancelToken = task.runAsyncF(_ => ())

    sc.tick(1.second)
    val f = cancelToken.runToFuture

    sc.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }
}
