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

  test("Task.now(value).to[IO]") { implicit s =>
    assertEquals(Task.now(123).to[IO].unsafeRunSync(), 123)
  }

  test("Task.eval(thunk).to[IO]") { implicit s =>
    assertEquals(Task.eval(123).to[IO].unsafeRunSync(), 123)
  }

  test("Task.eval(thunk).executeAsync.to[IO]") { implicit s =>
    val io = Task.eval(123).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("Task.evalTotal(thunk).to[IO]") { implicit s =>
    assertEquals(Task.evalTotal(123).to[IO].unsafeRunSync(), 123)
  }

  test("Task.evalTotal(thunk).executeAsync.to[IO]") { implicit s =>
    val io = Task.evalTotal(123).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("Task.raiseError(dummy).to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      Task.raiseError(dummy).to[IO].unsafeRunSync()
    }
    ()
  }

  test("Task.raiseError(dummy).executeAsync.to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = Task.raiseError(dummy).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.terminate(dummy).to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      Task.terminate(dummy).to[IO].unsafeRunSync()
    }
    ()
  }

  test("Task.terminate(dummy).executeAsync.to[IO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = Task.terminate(dummy).executeAsync.to[IO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("Task.toAsync converts successful tasks") { implicit s =>
    val io = Task.now(123).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("Task.toAsync converts tasks with typed errors") { implicit s =>
    val ex = DummyException("TypedError")
    val io = Task.raiseError(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task.toAsync converts tasks with terminal errors") { implicit s =>
    val ex = DummyException("Fatal")
    val io = Task.terminate(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task.toConcurrent converts successful tasks") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val io = Task.evalAsync(123).toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("Task.toConcurrent converts tasks with typed errors") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val ex = DummyException("TypedError")
    val io = Task.raiseError(ex).executeAsync.toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task.toConcurrent converts tasks with terminal errors") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val ex = DummyException("Fatal")
    val io = Task.terminate(ex).executeAsync.toConcurrent[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("Task.fromEffect(task)) <-> task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromEffect(task) <-> task
    }
  }

  test("Task.fromEffect(task.toAsync[Task.Unsafe]) <-> task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromEffect(task.toAsync[Task.Unsafe]) <-> task
    }
  }

  test("Task.fromEffect(task.toAsync[IO]) <-> task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromEffect(task.toAsync[IO]) <-> task
    }
  }

  test("Task.fromEffect(task.toAsync[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomEffect = new CustomEffect

    check1 { task: Task.Unsafe[Int] =>
      Task.fromEffect(task.toAsync[CIO]) <-> task
    }
  }

  test("Task.fromEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val f = Task.fromEffect(IO(123)).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = IO.shift >> IO(123)
    val f2 = Task.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val io3 = IO.raiseError(ex)
    val f3 = Task.fromEffect(io3).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("Task.fromEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: Effect[CIO] = new CustomEffect

    val f = Task.fromEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CIO(IO.shift) >> CIO(IO(123))
    val f2 = Task.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val f3 = Task.fromEffect(CIO(IO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("Task.fromEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: Effect[CIO] =
      new CustomEffect()(IO.contextShift(s)) {
        override def runAsync[A](fa: CIO[A])(
          cb: Either[Throwable, A] => IO[Unit]
        ): SyncIO[Unit] = throw ex
      }

    val f = Task.fromEffect(CIO(IO(123))).runToFuture
    s.tick()
    assertEquals(f.value, None)
    assertEquals(s.state.lastReportedError, ex)
  }

  test("Task.fromEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val task0 = Task(123).delayExecution(10.seconds)
    val task = Task.fromEffect(task0.toConcurrent[IO])
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

  test("Task.fromConcurrentEffect(task) <-> task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromConcurrentEffect(task) <-> task
    }
  }

  test("Task.fromConcurrentEffect(task.toConcurrent[Task.Unsafe]) <-> task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromConcurrentEffect(task.toConcurrent[Task.Unsafe]) <-> task
    }
  }

  test("Task.fromConcurrentEffect(task.toConcurrent[IO]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    check1 { task: Task.Unsafe[Int] =>
      Task.fromConcurrentEffect(task.toConcurrent[IO]) <-> task
    }
  }

  test("Task.fromConcurrentEffect(task.toConcurrent[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: CustomConcurrentEffect = new CustomConcurrentEffect

    check1 { task: Task.Unsafe[Int] =>
      Task.fromConcurrentEffect(task.toConcurrent[CIO]) <-> task
    }
  }

  test("Task.fromConcurrentEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val f = Task.fromConcurrentEffect(IO(123)).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = IO.shift >> IO(123)
    val f2 = Task.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val io3 = IO.raiseError(ex)
    val f3 = Task.fromConcurrentEffect(io3).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("Task.fromConcurrentEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect()

    val f = Task.fromConcurrentEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CIO(IO.shift) >> CIO(IO(123))
    val f2 = Task.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val f3 = Task.fromConcurrentEffect(CIO(IO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("Task.fromConcurrentEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: ConcurrentEffect[CIO] =
      new CustomConcurrentEffect()(IO.contextShift(s)) {
        override def runCancelable[A](fa: CIO[A])(
          cb: Either[Throwable, A] => IO[Unit]
        ): SyncIO[CancelToken[CIO]] = throw ex
      }

    val f = Task.fromConcurrentEffect(CIO(IO(123))).runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)

    assertEquals(s.state.lastReportedError, ex)
  }

  test("Task.fromConcurrentEffect preserves cancellability of an `IO`") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val timer = SchedulerEffect.timer[IO](s)
    val io = timer.sleep(10.seconds)
    val f = Task.fromConcurrentEffect(io).runToFuture

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

  test("Task.fromConcurrentEffect preserves cancellability of a custom effect") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect

    val timer = SchedulerEffect.timer[CIO](s)
    val cio = timer.sleep(10.seconds)
    val f = Task.fromConcurrentEffect(cio)(effect).runToFuture

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

  test("Task.fromConcurrentEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)

    val task0 = Task(123).delayExecution(10.seconds)
    val task = Task.fromConcurrentEffect(task0.toConcurrent[IO])

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

  test("Task.fromConcurrentEffect(task.toConcurrent[CIO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[IO] = IO.contextShift(s)
    implicit val effect: ConcurrentEffect[CIO] = new CustomConcurrentEffect

    val task0 = Task(123).delayExecution(10.seconds)
    val task = Task.fromConcurrentEffect(task0.toConcurrent[CIO])

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

  test("Task.fromReactivePublisher converts `.onNext` callbacks") { implicit s =>
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

    assertEquals(Task.fromReactivePublisher(pub).runToFuture.value, Some(Success(Some(123))))
  }

  test("Task.fromReactivePublisher converts `.onComplete` callbacks") { implicit s =>
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

    assertEquals(Task.fromReactivePublisher(pub).runToFuture.value, Some(Success(None)))
  }

  test("Task.fromReactivePublisher converts `.onError` callbacks") { implicit s =>
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

    assertEquals(Task.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("Task.fromReactivePublisher protects against user errors") { implicit s =>
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

    assertEquals(Task.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("Task.fromReactivePublisher is cancelable") { implicit s =>
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

    val bio = Task.fromReactivePublisher(pub).delayExecution(1.second)
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

  test("Task.fromReactivePublisher <-> Task") { implicit s =>
    check1 { task: Task.Unsafe[Int] =>
      Task.fromReactivePublisher(task.toReactivePublisher) <-> task.map(Some(_))
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
