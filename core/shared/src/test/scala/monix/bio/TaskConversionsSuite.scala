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

import cats.effect
import cats.effect.{IO, _}
import cats.laws._
import cats.laws.discipline._
import cats.syntax.all._
import monix.catnap.SchedulerEffect
import monix.execution.exceptions.DummyException
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskConversionsSuite extends BaseTestSuite {

  test("BIO.now(value).to[IO]") { implicit s =>
    assertEquals(BIO.now(123).to[IO].unsafeRunSync(), 123)
  }

  test("BIO.eval(thunk).to[IO]") { implicit s =>
    assertEquals(BIO.eval(123).to[IO].unsafeRunSync(), 123)
  }

  test("BIO.eval(thunk).executeAsync.to[IO]") { implicit s =>
    val io = BIO.eval(123).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("BIO.evalTotal(thunk).to[IO]") { implicit s =>
    assertEquals(BIO.evalTotal(123).to[IO].unsafeRunSync(), 123)
  }

  test("BIO.evalTotal(thunk).executeAsync.to[IO]") { implicit s =>
    val io = BIO.evalTotal(123).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("BIO.raiseError(dummy).to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      BIO.raiseError(dummy).to[IO].unsafeRunSync()
    }
  }

  test("BIO.raiseError(dummy).executeAsync.to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = BIO.raiseError(dummy).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.raiseFatalError(dummy).to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      BIO.raiseFatalError(dummy).to[IO].unsafeRunSync()
    }
  }

  test("BIO.raiseFatalError(dummy).executeAsync.to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = BIO.raiseFatalError(dummy).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.toAsync converts successful tasks") { implicit s =>
    val io = BIO.now(123).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("BIO.toAsync converts tasks with typed errors") { implicit s =>
    val ex = DummyException("Typed")
    val io = BIO.raiseError(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.toAsync converts tasks with fatal errors") { implicit s =>
    val ex = DummyException("Fatal")
    val io = BIO.raiseFatalError(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.toConcurrent converts successful tasks") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val io = BIO.evalAsync(123).toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("BIO.toConcurrent converts tasks with typed errors") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val ex = DummyException("Typed")
    val io = BIO.raiseError(ex).executeAsync.toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.toConcurrent converts tasks with fatal errors") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val ex = DummyException("Fatal")
    val io = BIO.raiseFatalError(ex).executeAsync.toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.fromEffect(task)) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromEffect(task) <-> task
    }
  }

  test("BIO.fromEffect(task.toAsync[Task]) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromEffect(task.toAsync[Task]) <-> task
    }
  }

  test("BIO.fromEffect(task.toAsync[IO]) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromEffect(task.toAsync[IO]) <-> task
    }
  }

  test("BIO.fromEffect(task.toAsync[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomEffect = new CustomEffect

    check1 { task: Task[Int] =>
      BIO.fromEffect(task.toAsync[CIO]) <-> task
    }
  }

  test("BIO.fromEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val f = BIO.fromEffect(IO(123)).runToFuture
    assertEquals(f.value, Some(Success(Right(123))))

    val io2 = IO.shift >> IO(123)
    val f2 = BIO.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(Right(123))))

    val ex = DummyException("Dummy")
    val io3 = IO.raiseError(ex)
    val f3 = BIO.fromEffect(io3).runToFuture
    assertEquals(f3.value, Some(Success(Left(ex))))
  }

  test("BIO.fromEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: Effect[CIO] = new CustomEffect

    val f = BIO.fromEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, Some(Success(Right(123))))

    val io2 = CIO(IO.shift) >> CIO(IO(123))
    val f2 = BIO.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(Right(123))))

    val ex = DummyException("Dummy")
    val f3 = BIO.fromEffect(CIO(IO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Success(Left(ex))))
  }

  test("BIO.fromEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: Effect[CIO] =
      new CustomEffect()(IO.contextShift(s)) {
        override def runAsync[A](fa: CIO[A])(
          cb: Either[Throwable, A] => IO[Unit]
        ): SyncIO[Unit] = throw ex
      }

    val f = BIO.fromEffect(CIO(IO(123))).runToFuture
    s.tick()
    assertEquals(f.value, None)
    assertEquals(s.state.lastReportedError, ex)
  }

  test("BIO.fromEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val task0 = BIO(123).delayExecution(10.seconds)
    val task = BIO.fromEffect(task0.toConcurrent[IO])
    val f = task.runToFuture

    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.fromConcurrentEffect(task) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromConcurrentEffect(task) <-> task
    }
  }

  test("BIO.fromConcurrentEffect(task.toConcurrent[Task]) <-> task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromConcurrentEffect(task.toConcurrent[Task]) <-> task
    }
  }

  test("BIO.fromConcurrentEffect(task.toConcurrent[IO]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    check1 { task: Task[Int] =>
      BIO.fromConcurrentEffect(task.toConcurrent[IO]) <-> task
    }
  }

  test("BIO.fromConcurrentEffect(task.toConcurrent[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomConcurrentEffect = new CustomConcurrentEffect

    check1 { task: Task[Int] =>
      BIO.fromConcurrentEffect(task.toConcurrent[CIO]) <-> task
    }
  }

  test("BIO.fromConcurrentEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val f = BIO.fromConcurrentEffect(IO(123)).runToFuture
    assertEquals(f.value, Some(Success(Right(123))))

    val io2 = IO.shift >> IO(123)
    val f2 = BIO.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(Right(123))))

    val ex = DummyException("Dummy")
    val io3 = IO.raiseError(ex)
    val f3 = BIO.fromConcurrentEffect(io3).runToFuture
    assertEquals(f3.value, Some(Success(Left(ex))))
  }

  test("BIO.fromConcurrentEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect()

    val f = BIO.fromConcurrentEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, Some(Success(Right(123))))

    val io2 = CIO(IO.shift) >> CIO(IO(123))
    val f2 = BIO.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(Right(123))))

    val ex = DummyException("Dummy")
    val f3 = BIO.fromConcurrentEffect(CIO(IO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Success(Left(ex))))
  }

  test("BIO.fromConcurrentEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: ConcurrentEffect[CIO] =
      new CustomConcurrentEffect()(IO.contextShift(s)) {
        override def runCancelable[A](fa: CIO[A])(
          cb: Either[Throwable, A] => IO[Unit]
        ): SyncIO[CancelToken[CIO]] = throw ex
      }

    val f = BIO.fromConcurrentEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)

    assertEquals(s.state.lastReportedError, ex)
  }

  test("BIO.fromConcurrentEffect preserves cancellability of an `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val timer = SchedulerEffect.timer[IO](s)
    val io = timer.sleep(10.seconds)
    val f = BIO.fromConcurrentEffect(io).runToFuture

    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    assertEquals(f.value, None)

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.fromConcurrentEffect preserves cancellability of a custom effect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect

    val timer = SchedulerEffect.timer[CIO](s)
    val cio = timer.sleep(10.seconds)
    val f = BIO.fromConcurrentEffect(cio)(effect).runToFuture

    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
    assertEquals(f.value, None)

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.fromConcurrentEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val task0 = Task(123).delayExecution(10.seconds)
    val task = BIO.fromConcurrentEffect(task0.toConcurrent[IO])

    val f = task.runToFuture
    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.fromConcurrentEffect(task.toConcurrent[CIO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect

    val task0 = Task(123).delayExecution(10.seconds)
    val task = BIO.fromConcurrentEffect(task0.toConcurrent[CIO])

    val f = task.runToFuture
    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")

    s.tick(10.seconds)
    assertEquals(f.value, None)
  }

  test("BIO.fromReactivePublisher converts `.onNext` callbacks") { implicit s =>
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onNext(123)
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Right(Some(123)))))
  }

  test("BIO.fromReactivePublisher converts `.onComplete` callbacks") { implicit s =>
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Right(None))))
  }

  test("BIO.fromReactivePublisher converts `.onError` callbacks") { implicit s =>
    val dummy = DummyException("Error")
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onError(dummy)
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromReactivePublisher protects against user errors") { implicit s =>
    val dummy = DummyException("Error")
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            def request(n: Long): Unit = throw dummy
            def cancel(): Unit = throw dummy
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("BIO.fromReactivePublisher is cancelable") { implicit s =>
    var wasRequested = false
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                wasRequested = true
                s.onNext(123)
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    val bio = BIO.fromReactivePublisher(pub).delayExecution(1.second)
    val f = bio.runToFuture
    f.cancel()

    s.tick()
    assert(!wasRequested, "nothing should be requested")
    assert(s.state.tasks.isEmpty, "task should be canceled")
    assertEquals(f.value, None)

    s.tick(1.second)
    assert(!wasRequested, "nothing should be requested")
    assert(s.state.tasks.isEmpty, "task should be canceled")
    assertEquals(f.value, None)
  }

  test("BIO.fromReactivePublisher <-> Task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromReactivePublisher(task.toReactivePublisher) <-> task.map(Some(_))
    }
  }

  final case class CIO[+A](io: IO[A])

  class CustomEffect(implicit cs: ContextShift[IO]) extends Effect[CIO] {

    override def runAsync[A](fa: CIO[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      fa.io.runAsync(cb)

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): CIO[A] =
      CIO(IO.async(k))

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => CIO[Unit]): CIO[A] =
      CIO(IO.asyncF(cb => k(cb).io))

    override def suspend[A](thunk: => CIO[A]): CIO[A] =
      CIO(IO.suspend(thunk.io))

    override def flatMap[A, B](fa: CIO[A])(f: A => CIO[B]): CIO[B] =
      CIO(fa.io.flatMap(a => f(a).io))

    override def tailRecM[A, B](a: A)(f: A => CIO[Either[A, B]]): CIO[B] =
      CIO(IO.ioConcurrentEffect.tailRecM(a)(x => f(x).io))

    override def raiseError[A](e: Throwable): CIO[A] =
      CIO(IO.raiseError(e))

    override def handleErrorWith[A](fa: CIO[A])(f: Throwable => CIO[A]): CIO[A] =
      CIO(IO.ioConcurrentEffect.handleErrorWith(fa.io)(x => f(x).io))

    override def pure[A](x: A): CIO[A] =
      CIO(IO.pure(x))

    override def liftIO[A](ioa: IO[A]): CIO[A] =
      CIO(ioa)

    override def bracketCase[A, B](acquire: CIO[A])(use: A => CIO[B])(
      release: (A, ExitCase[Throwable]) => CIO[Unit]
    ): CIO[B] = CIO(acquire.io.bracketCase(a => use(a).io)((a, e) => release(a, e).io))

  }

  class CustomConcurrentEffect(implicit cs: ContextShift[IO]) extends CustomEffect with ConcurrentEffect[CIO] {

    override def runCancelable[A](fa: CIO[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[CIO]] =
      fa.io.runCancelable(cb).map(CIO(_))

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[CIO]): CIO[A] =
      CIO(IO.cancelable(cb => k(cb).io))

    override def uncancelable[A](fa: CIO[A]): CIO[A] =
      CIO(fa.io.uncancelable)

    override def start[A](fa: CIO[A]): CIO[effect.Fiber[CIO, A]] =
      CIO(fa.io.start.map(fiberT))

    override def racePair[A, B](fa: CIO[A], fb: CIO[B]) =
      CIO {
        IO.racePair(fa.io, fb.io).map {
          case Left((a, fiber)) => Left((a, fiberT(fiber)))
          case Right((fiber, b)) => Right((fiberT(fiber), b))
        }
      }

    private def fiberT[A](fiber: effect.Fiber[IO, A]): effect.Fiber[CIO, A] =
      effect.Fiber(CIO(fiber.join), CIO(fiber.cancel))

  }

}
