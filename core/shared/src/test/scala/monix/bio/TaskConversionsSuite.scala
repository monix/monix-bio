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
import cats.effect.{IO => CIO, _}
import cats.laws._
import cats.laws.discipline._
import cats.syntax.all._
import monix.catnap.SchedulerEffect
import monix.execution.exceptions.DummyException
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskConversionsSuite extends BaseTestSuite {

  test("IO.now(value).to[CIO]") { implicit s =>
    assertEquals(IO.now(123).to[CIO].unsafeRunSync(), 123)
  }

  test("IO.eval(thunk).to[CIO]") { implicit s =>
    assertEquals(IO.eval(123).to[CIO].unsafeRunSync(), 123)
  }

  test("IO.eval(thunk).executeAsync.to[CIO]") { implicit s =>
    val io = IO.eval(123).executeAsync.to[CIO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("IO.evalTotal(thunk).to[CIO]") { implicit s =>
    assertEquals(IO.evalTotal(123).to[CIO].unsafeRunSync(), 123)
  }

  test("IO.evalTotal(thunk).executeAsync.to[CIO]") { implicit s =>
    val io = IO.evalTotal(123).executeAsync.to[CIO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("IO.raiseError(dummy).to[CIO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      IO.raiseError(dummy).to[CIO].unsafeRunSync()
    }
    ()
  }

  test("IO.raiseError(dummy).executeAsync.to[CIO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = IO.raiseError(dummy).executeAsync.to[CIO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.terminate(dummy).to[CIO]") { implicit s =>
    val dummy = DummyException("dummy")
    intercept[DummyException] {
      IO.terminate(dummy).to[CIO].unsafeRunSync()
    }
    ()
  }

  test("IO.terminate(dummy).executeAsync.to[CIO]") { implicit s =>
    val dummy = DummyException("dummy")
    val io = IO.terminate(dummy).executeAsync.to[CIO]
    val f = io.unsafeToFuture()

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.toAsync converts successful tasks") { implicit s =>
    val io = IO.now(123).executeAsync.toAsync[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("IO.toAsync converts tasks with typed errors") { implicit s =>
    val ex = DummyException("TypedError")
    val io = IO.raiseError(ex).executeAsync.toAsync[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.toAsync converts tasks with terminal errors") { implicit s =>
    val ex = DummyException("Fatal")
    val io = IO.terminate(ex).executeAsync.toAsync[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.toConcurrent converts successful tasks") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val io = IO.evalAsync(123).toConcurrent[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("IO.toConcurrent converts tasks with typed errors") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val ex = DummyException("TypedError")
    val io = IO.raiseError(ex).executeAsync.toConcurrent[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.toConcurrent converts tasks with terminal errors") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val ex = DummyException("Fatal")
    val io = IO.terminate(ex).executeAsync.toConcurrent[CIO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("IO.fromEffect(task)) <-> task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromEffect(task) <-> task
    }
  }

  test("IO.fromEffect(task.toAsync[Task]) <-> task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromEffect(task.toAsync[Task]) <-> task
    }
  }

  test("IO.fromEffect(task.toAsync[CIO]) <-> task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromEffect(task.toAsync[CIO]) <-> task
    }
  }

  test("IO.fromEffect(task.toAsync[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: CustomEffect = new CustomEffect

    check1 { (task: Task[Int]) =>
      IO.fromEffect(task.toAsync[CEIO]) <-> task
    }
  }

  test("IO.fromEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val f = IO.fromEffect(CIO(123)).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CIO.shift >> CIO(123)
    val f2 = IO.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val io3 = CIO.raiseError(ex)
    val f3 = IO.fromEffect(io3).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("IO.fromEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: Effect[CEIO] = new CustomEffect

    val f = IO.fromEffect(CEIO(CIO(123))).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CEIO(CIO.shift) >> CEIO(CIO(123))
    val f2 = IO.fromEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val f3 = IO.fromEffect(CEIO(CIO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("IO.fromEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: Effect[CEIO] =
      new CustomEffect()(CIO.contextShift(s)) {
        override def runAsync[A](fa: CEIO[A])(
          cb: Either[Throwable, A] => CIO[Unit]
        ): SyncIO[Unit] = throw ex
      }

    val f = IO.fromEffect(CEIO(CIO(123))).runToFuture
    s.tick()
    assertEquals(f.value, None)
    assertEquals(s.state.lastReportedError, ex)
  }

  test("IO.fromEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val task0 = IO(123).delayExecution(10.seconds)
    val task = IO.fromEffect(task0.toConcurrent[CIO])
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

  test("IO.fromConcurrentEffect(task) <-> task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromConcurrentEffect(task) <-> task
    }
  }

  test("IO.fromConcurrentEffect(task.toConcurrent[Task]) <-> task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromConcurrentEffect(task.toConcurrent[Task]) <-> task
    }
  }

  test("IO.fromConcurrentEffect(task.toConcurrent[IO]) <-> task") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    check1 { (task: Task[Int]) =>
      IO.fromConcurrentEffect(task.toConcurrent[CIO]) <-> task
    }
  }

  test("IO.fromConcurrentEffect(task.toConcurrent[F]) <-> task") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: CustomConcurrentEffect = new CustomConcurrentEffect

    check1 { (task: Task[Int]) =>
      IO.fromConcurrentEffect(task.toConcurrent[CEIO]) <-> task
    }
  }

  test("IO.fromConcurrentEffect converts `IO`") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val f = IO.fromConcurrentEffect(CIO(123)).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CIO.shift >> CIO(123)
    val f2 = IO.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val io3 = CIO.raiseError(ex)
    val f3 = IO.fromConcurrentEffect(io3).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("IO.fromConcurrentEffect converts valid custom effects") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: ConcurrentEffect[CEIO] = new CustomConcurrentEffect()

    val f = IO.fromConcurrentEffect(CEIO(CIO(123))).runToFuture
    assertEquals(f.value, Some(Success(123)))

    val io2 = CEIO(CIO.shift) >> CEIO(CIO(123))
    val f2 = IO.fromConcurrentEffect(io2).runToFuture
    assertEquals(f2.value, None)
    s.tick()
    assertEquals(f2.value, Some(Success(123)))

    val ex = DummyException("Dummy")
    val f3 = IO.fromConcurrentEffect(CEIO(CIO.raiseError(ex))).runToFuture
    assertEquals(f3.value, Some(Failure(ex)))
  }

  test("IO.fromConcurrentEffect doesn't convert invalid custom effects") { implicit s =>
    val ex = DummyException("Dummy")
    implicit val effect: ConcurrentEffect[CEIO] =
      new CustomConcurrentEffect()(CIO.contextShift(s)) {
        override def runCancelable[A](fa: CEIO[A])(
          cb: Either[Throwable, A] => CIO[Unit]
        ): SyncIO[CancelToken[CEIO]] = throw ex
      }

    val f = IO.fromConcurrentEffect(CEIO(CIO(123))).runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)

    assertEquals(s.state.lastReportedError, ex)
  }

  test("IO.fromConcurrentEffect preserves cancellability of an `IO`") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val timer = SchedulerEffect.timer[CIO](s)
    val io = timer.sleep(10.seconds)
    val f = IO.fromConcurrentEffect(io).runToFuture

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

  test("IO.fromConcurrentEffect preserves cancellability of a custom effect") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: ConcurrentEffect[CEIO] = new CustomConcurrentEffect

    val timer = SchedulerEffect.timer[CEIO](s)
    val cio = timer.sleep(10.seconds)
    val f = IO.fromConcurrentEffect(cio)(effect).runToFuture

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

  test("IO.fromConcurrentEffect(task.toConcurrent[IO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)

    val task0 = Task(123).delayExecution(10.seconds)
    val task = IO.fromConcurrentEffect(task0.toConcurrent[CIO])

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

  test("IO.fromConcurrentEffect(task.toConcurrent[CIO]) preserves cancellability") { implicit s =>
    implicit val cs: ContextShift[CIO] = CIO.contextShift(s)
    implicit val effect: ConcurrentEffect[CEIO] = new CustomConcurrentEffect

    val task0 = Task(123).delayExecution(10.seconds)
    val task = IO.fromConcurrentEffect(task0.toConcurrent[CEIO])

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

  test("IO.fromReactivePublisher converts `.onNext` callbacks") { implicit s =>
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

    assertEquals(IO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Some(123))))
  }

  test("IO.fromReactivePublisher converts `.onComplete` callbacks") { implicit s =>
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

    assertEquals(IO.fromReactivePublisher(pub).runToFuture.value, Some(Success(None)))
  }

  test("IO.fromReactivePublisher converts `.onError` callbacks") { implicit s =>
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

    assertEquals(IO.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("IO.fromReactivePublisher protects against user errors") { implicit s =>
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

    assertEquals(IO.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("IO.fromReactivePublisher is cancelable") { implicit s =>
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

    val bio = IO.fromReactivePublisher(pub).delayExecution(1.second)
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

  test("IO.fromReactivePublisher <-> Task") { implicit s =>
    check1 { (task: Task[Int]) =>
      IO.fromReactivePublisher(task.toReactivePublisher) <-> task.map(Some(_))
    }
  }

  final case class CEIO[+A](io: CIO[A])

  class CustomEffect(implicit cs: ContextShift[CIO]) extends Effect[CEIO] {

    override def runAsync[A](fa: CEIO[A])(cb: Either[Throwable, A] => CIO[Unit]): SyncIO[Unit] =
      fa.io.runAsync(cb)

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): CEIO[A] =
      CEIO(CIO.async_(k))

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => CEIO[Unit]): CEIO[A] =
      CEIO(CIO.asyncF(cb => k(cb).io))

    override def suspend[A](thunk: => CEIO[A]): CEIO[A] =
      CEIO(CIO.defer(thunk.io))

    override def flatMap[A, B](fa: CEIO[A])(f: A => CEIO[B]): CEIO[B] =
      CEIO(fa.io.flatMap(a => f(a).io))

    override def tailRecM[A, B](a: A)(f: A => CEIO[Either[A, B]]): CEIO[B] =
      CEIO(CIO.ioConcurrentEffect.tailRecM(a)(x => f(x).io))

    override def raiseError[A](e: Throwable): CEIO[A] =
      CEIO(CIO.raiseError(e))

    override def handleErrorWith[A](fa: CEIO[A])(f: Throwable => CEIO[A]): CEIO[A] =
      CEIO(CIO.ioConcurrentEffect.handleErrorWith(fa.io)(x => f(x).io))

    override def pure[A](x: A): CEIO[A] =
      CEIO(CIO.pure(x))

    override def liftIO[A](ioa: CIO[A]): CEIO[A] =
      CEIO(ioa)

    override def bracketCase[A, B](acquire: CEIO[A])(use: A => CEIO[B])(
      release: (A, ExitCase[Throwable]) => CEIO[Unit]
    ): CEIO[B] = CEIO(acquire.io.bracketCase(a => use(a).io)((a, e) => release(a, e).io))

  }

  class CustomConcurrentEffect(implicit cs: ContextShift[CIO]) extends CustomEffect with ConcurrentEffect[CEIO] {

    override def runCancelable[A](fa: CEIO[A])(cb: Either[Throwable, A] => CIO[Unit]): SyncIO[CancelToken[CEIO]] =
      fa.io.runCancelable(cb).map(CEIO(_))

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[CEIO]): CEIO[A] =
      CEIO(CIO.cancelable(cb => k(cb).io))

    override def uncancelable[A](fa: CEIO[A]): CEIO[A] =
      CEIO(fa.io.uncancelable)

    override def start[A](fa: CEIO[A]): CEIO[effect.Fiber[CEIO, A]] =
      CEIO(fa.io.start.map(fiberT))

    override def racePair[A, B](fa: CEIO[A], fb: CEIO[B]) =
      CEIO {
        CIO.racePair(fa.io, fb.io).map {
          case Left((a, fiber)) => Left((a, fiberT(fiber)))
          case Right((fiber, b)) => Right((fiberT(fiber), b))
        }
      }

    private def fiberT[A](fiber: effect.Fiber[CIO, A]): effect.Fiber[CEIO, A] =
      effect.Fiber(CEIO(fiber.join), CEIO(fiber.cancel))

  }

}
