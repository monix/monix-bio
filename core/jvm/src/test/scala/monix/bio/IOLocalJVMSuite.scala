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

import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.exceptions.DummyException
import monix.execution.{ExecutionModel, Scheduler}
import monix.execution.misc.Local
import monix.execution.schedulers.TracingScheduler
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

object IOLocalJVMSuite extends SimpleIOTestSuite {
  def createShift(ec: ExecutionContext): UIO[Unit] =
    IO.cancelable0[Nothing, Unit] { (_, cb) =>
      ec.execute(() => cb.onSuccess(()))
      IO.unit
    }

  test("locals get transported with executeOn and shift") {
    import Scheduler.Implicits.traced
    val ec = Scheduler.computation(4, "ec1")
    val ec2 = Scheduler.computation(4, "ec2")

    try {
      val task =
        for {
          local <- IOLocal(0)
          _     <- local.write(100).executeOn(ec2)
          v1    <- local.read.executeOn(ec)
          _     <- IO.shift(Scheduler.global)
          v2    <- local.read.executeOn(ec2)
          _     <- IO.shift
          v3    <- local.read.executeOn(ec2)
          _     <- createShift(ec2)
          v4    <- local.read
          v5    <- local.read.executeOn(ec)
        } yield v1 :: v2 :: v3 :: v4 :: v5 :: Nil

      val r = task.runSyncUnsafe(Duration.Inf)
      assertEquals(r, List(100, 100, 100, 100, 100))
    } finally {
      ec.shutdown()
      ec2.shutdown()
    }
  }

  test("locals get transported with executeWithModel") {
    import Scheduler.Implicits.traced

    val task =
      for {
        local <- IOLocal(0)
        _     <- local.write(100).executeWithModel(AlwaysAsyncExecution)
        _     <- IO.shift
        v     <- local.read
      } yield v

    val r = task.runSyncUnsafe(Duration.Inf)
    assertEquals(r, 100)
  }

  test("locals get transported with executeWithOptions") {
    import Scheduler.Implicits.traced

    val task =
      for {
        local <- IOLocal(0)
        _     <- local.write(100).executeWithOptions(_.enableAutoCancelableRunLoops)
        _     <- IO.shift
        v     <- local.read
      } yield v

    val r = task.runSyncUnsafe(Duration.Inf)
    assertEquals(r, 100)
  }

  test("IOLocal.isolate can be nested with executeWithOptions") {
    implicit val s = monix.execution.Scheduler.Implicits.global

    val t = for {
      local <- IOLocal(0)
      _ <- IOLocal.isolate {
        for {
          _ <- local.write(100)
          _ <- IOLocal.isolate(local.write(200))
          x <- local.read
          _ <- IO(assertEquals(x, 100))
          _ <- local.bind(x * 2)(local.read.map(localValue => assertEquals(x * 2, localValue)))
          _ <- IO(assertEquals(x, 100))
        } yield ()
      }
      y <- local.read
      _ <- IO(assertEquals(y, 0))
    } yield ()

    Await.result(t.executeWithOptions(_.enableLocalContextPropagation).runToFuture, Duration.Inf)
    t.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe()
    ()
  }

  test("local.write.executeOn(forceAsync = false) works") {
    import Scheduler.Implicits.traced
    val ec = Scheduler.computation(4, "ec1")

    val task = for {
      l <- IOLocal(10)
      _ <- l.write(100).executeOn(ec, forceAsync = false)
      _ <- IO.shift
      v <- l.read
    } yield v

    val r = task.runSyncUnsafe(Duration.Inf)
    assertEquals(r, 100)
  }

  test("local.write.executeOn(forceAsync = true) works") {
    import monix.execution.Scheduler.Implicits.traced
    val ec = Scheduler.computation(4, "ec1")

    val task = for {
      l <- IOLocal(10)
      _ <- l.write(100).executeOn(ec)
      _ <- IO.shift
      v <- l.read
    } yield v

    val r = task.runSyncUnsafe(Duration.Inf)
    assertEquals(r, 100)
  }

  test("local state is encapsulated by Task run loop with TracingScheduler") {
    import monix.execution.Scheduler.Implicits.traced
    val local = IOLocal(0).memoize

    val task = for {
      l <- local
      x <- l.read
      _ <- l.write(x + 1)
    } yield x

    val runMethods: List[Task[Int] => Int] = List(
      _.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe(),
      _.executeAsync.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe(),
      _.runSyncUnsafe(),
      _.executeWithOptions(_.enableLocalContextPropagation).to[cats.effect.IO].unsafeRunSync(),
      t => Await.result(t.executeWithOptions(_.enableLocalContextPropagation).runToFuture, 1.second),
      t => Await.result(t.runToFuture, 1.second),
      t =>
        Await
          .result(t.executeWithOptions(_.enableLocalContextPropagation).to[cats.effect.IO].unsafeToFuture(), 1.second)
    )

    for (method <- runMethods) {
      for (_ <- 1 to 10) method(task)
      val r = method(task)
      assertEquals(r, 0)
    }
  }

  test("local state is encapsulated by Task run loop without TracingScheduler") {
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = IO.defaultOptions.enableLocalContextPropagation
    val local = IOLocal(0).memoize

    val task = for {
      l <- local
      x <- l.read
      _ <- l.write(x + 1)
    } yield x

    val runMethods: List[Task[Int] => Int] = List(
      _.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe(),
      _.executeAsync.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe(),
      _.runSyncUnsafeOpt(),
      _.executeWithOptions(_.enableLocalContextPropagation).to[cats.effect.IO].unsafeRunSync(),
      t => Await.result(t.executeWithOptions(_.enableLocalContextPropagation).runToFuture, 1.second),
      t => Await.result(t.runToFutureOpt, 1.second),
      t =>
        Await
          .result(t.executeWithOptions(_.enableLocalContextPropagation).to[cats.effect.IO].unsafeToFuture(), 1.second)
    )

    for (method <- runMethods) {
      for (_ <- 1 to 10) method(task)
      val r = method(task)
      assertEquals(r, 0)
    }
  }

  testAsync("local state is encapsulated by Task run loop on single thread") {
    implicit val s = TracingScheduler(Scheduler.singleThread("local-test"))
      .withExecutionModel(ExecutionModel.AlwaysAsyncExecution)
    implicit val opts = IO.defaultOptions.enableLocalContextPropagation

    def runAssertion(run: Task[Unit] => Any, method: String): Future[Unit] = {
      val p = Promise[Unit]()
      val local = Local(0)
      val task = IO.evalAsync(local := 50).guarantee(UIO(p.success(())).void)

      run(task)

      // Future could still carry isolated local
      // because it was created inside isolated block
      val f = Local.isolate(p.future)

      f.map(_ => {
        assert(local() == 0, s"received ${local()} != expected 0 in $method")
      })
    }

    for {
      _ <- runAssertion(_.runSyncUnsafeOpt(), "runSyncUnsafe")
      _ <- runAssertion(_.runToFutureOpt, "runToFuture")
      _ <- runAssertion(_.runAsyncOpt(_ => ()), "runAsync")
      _ <- runAssertion(_.runAsyncOptF(_ => ()), "runAsyncF")
      _ <- runAssertion(_.runAsyncAndForgetOpt, "runAsyncAndForget")
      _ <- runAssertion(_.runAsyncUncancelableOpt(_ => ()), "runAsyncUncancelable")
    } yield ()

  }

  testAsync("IOLocal.isolate should properly isolate during async boundaries") {
    implicit val s = TracingScheduler(Scheduler.singleThread("local-test"))
      .withExecutionModel(ExecutionModel.AlwaysAsyncExecution)

    val local = Local(0)
    val test = for {
      _ <- Task.evalAsync(local := 50)
      _ <- IOLocal.isolate {
        Task.evalAsync(local := 100)
      }
      v <- Task.evalAsync(local())
      _ <- Task.now(assertEquals(v, 50))
    } yield ()

    test.runToFuture
  }

  testAsync("IOLocal.isolate should properly isolate during async boundaries on error") {
    implicit val s = TracingScheduler(Scheduler.singleThread("local-test"))
      .withExecutionModel(ExecutionModel.AlwaysAsyncExecution)

    val local = Local(0)
    val test = for {
      _ <- Task.evalAsync(local := 50)
      _ <- IOLocal.isolate {
        Task.evalAsync(local := 100).flatMap(_ => Task.raiseError(DummyException("boom")))
      }.attempt
      v <- Task.evalAsync(local())
      _ <- Task.now(assertEquals(v, 50))
    } yield ()

    test.runToFuture
  }

  testAsync("IOLocal.isolate should properly isolate during async boundaries on cancelation") {
    implicit val s = TracingScheduler(Scheduler.singleThread("local-test"))
      .withExecutionModel(ExecutionModel.AlwaysAsyncExecution)

    val local = Local(0)
    val test = for {
      _ <- Task.evalAsync(local := 50)
      _ <- IOLocal.isolate {
        Task.evalAsync(local := 100).start.flatMap(_.cancel)
      }
      v <- Task.evalAsync(local())
      _ <- Task.now(assertEquals(v, 50))
    } yield ()

    test.runToFuture
  }

  testAsync("IOLocal.isolate should isolate contexts from Future") {
    implicit val s = TracingScheduler(Scheduler.singleThread("local-test"))

    val local = Local(0)
    val test = for {
      _ <- Task(local := 100)
      _ <- IOLocal.isolate {
        Task.deferFuture(Future {
          local.clear()
        })
      }
      _ <- Task.now(assertEquals(local.get, 100))
    } yield ()

    test.runToFuture
  }

  testAsync("Task.evalAsync.runToFuture isolates but preserves context for Future continuation") {
    implicit val s: Scheduler = Scheduler.Implicits.traced
    val local = Local(0)
    val n = 100

    case class TestResult(lastCtx: Local.Context, isolatedCtx: Local.Context, lastValue: Int, expectedValue: Int)

    val promises = Array.fill(n)(Promise[TestResult]())

    def test(i: Int): Future[Unit] = {
      val prev = local.get

      for {
        isolated <- Task.evalAsync {
          local.update(prev + i)
          Local.getContext()
        }.runToFuture
        next <- Future {
          local.get
        }
      } yield {
        val _ = promises(i).success(TestResult(Local.getContext(), isolated, next, i))
      }
    }

    List.range(0, n).foreach(test)

    Future
      .traverse(promises.toList)(_.future)
      .map(_.foreach { case TestResult(ctx, expected, next, expectedValue) =>
        assertEquals(ctx, expected)
        assertEquals(next, expectedValue)
      })
  }

  testAsync("Task.eval.runToFuture isolates but preserves context for Future continuation") {
    implicit val s: Scheduler = Scheduler.Implicits.traced
    val local = Local(0)
    val n = 100

    case class TestResult(lastCtx: Local.Context, isolatedCtx: Local.Context, lastValue: Int, expectedValue: Int)

    val promises = Array.fill(n)(Promise[TestResult]())

    def test(i: Int): Future[Unit] = {
      val prev = local.get

      for {
        isolated <- Task.eval {
          local.update(prev + i)
          Local.getContext()
        }.runToFuture
        next <- Future {
          local.get
        }
      } yield {
        val _ = promises(i).success(TestResult(Local.getContext(), isolated, next, prev + i))
      }
    }

    List.range(0, n).foreach(test)

    Future
      .traverse(promises.toList)(_.future)
      .map(_.foreach { case TestResult(ctx, expected, next, expectedValue) =>
        assertEquals(ctx, expected)
        assertEquals(next, expectedValue)
      })
  }

  testAsync("Task.evalAsync.runToFuture isolates but preserves context for Future continuation on a single thread") {
    implicit val s: Scheduler = TracingScheduler(Scheduler.singleThread("local-test"))
    val local = Local(0)
    val n = 100
    case class TestResult(lastCtx: Local.Context, isolatedCtx: Local.Context, lastValue: Int, expectedValue: Int)

    val promises = Array.fill(n)(Promise[TestResult]())

    def test(i: Int): Future[Unit] = {
      val prev = local.get

      for {
        isolated <- Task.evalAsync {
          local.update(prev + i)
          Local.getContext()
        }.runToFuture
        next <- Future {
          local.get
        }
      } yield {
        val _ = promises(i).success(TestResult(Local.getContext(), isolated, next, i))
      }
    }

    List.range(0, n).foreach(test)

    Future
      .traverse(promises.toList)(_.future)
      .map(_.foreach { case TestResult(ctx, expected, next, expectedValue) =>
        assertEquals(ctx, expected)
        assertEquals(next, expectedValue)
      })
  }

  testAsync("Task.eval.runToFuture continuations keep isolated context in longer continuations") {
    implicit val s: Scheduler = Scheduler.Implicits.traced

    val local = Local(0)

    val f1 = for {
      _ <- Task {
        val i = local.get
        local.update(i + 1)
      }.runToFuture
      i1 <- Future(local.get)
      i2 <- Future(local.get)
      _  <- Future(local.update(i2 + 1))
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 1)
      assertEquals(i2, 1)
      assertEquals(i3, 2)
      assertEquals(local.get, 2)
    }

    val f2 = for {
      _ <- Task.evalAsync {
        val i = local.get
        local.update(i + 1)
      }.runToFuture
      i1 <- Future(local.get)
      i2 <- Future(local.get)
      _  <- Future(local.update(i2 + 1))
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 1)
      assertEquals(i2, 1)
      assertEquals(i3, 2)
      assertEquals(local.get, 2)
    }

    f1.flatMap(_ => f2)
  }

  testAsync("Task.eval.runToFuture can isolate future continuations") {
    implicit val s: Scheduler = Scheduler.Implicits.traced

    val local = Local(0)

    for {
      _  <- Task(local.update(1)).runToFuture
      i1 <- Future(local.get)
      i2 <- Local.isolate {
        Future(local.update(i1 + 1))
          .flatMap(_ => Future(local.get))
      }
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 1)
      assertEquals(i2, 2)
      assertEquals(i3, 1)
      assertEquals(local.get, 1)
    }
  }

  testAsync("Task.eval.runToFuture can isolate future continuations on failure") {
    implicit val s: Scheduler = Scheduler.Implicits.traced

    val local = Local(0)

    for {
      _ <- Task(local.update(1)).flatMap(_ => Task.raiseError(DummyException("boom"))).runToFuture.recover { case _ =>
        ()
      }
      i1 <- Future(local.get)
      i2 <- Local.isolate {
        Future(local.update(i1 + 1))
          .flatMap(_ => Future(local.get))
      }
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 1)
      assertEquals(i2, 2)
      assertEquals(i3, 1)
      assertEquals(local.get, 1)
    }
  }

  testAsync("Task.runToFuture resulting future can be reused") {
    implicit val s: Scheduler = Scheduler.Implicits.traced
    import cats.syntax.all._
    import monix.execution.cancelableFutureCatsInstances

    val local = Local(0)

    val f1 = for {
      _ <- Task {
        val i = local.get
        local.update(i + 10)
      }.runToFuture
      i1 <- Future(local.get)
      i2 <- Future(local.get)
      _  <- Future(local.update(i2 + 10))
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 10)
      assertEquals(i2, 10)
      assertEquals(i3, 20)
      assertEquals(local.get, 20)
    }

    val f2 = for {
      _ <- Task.evalAsync {
        val i = local.get
        local.update(i + 1)
      }.runToFuture
      i1 <- Future(local.get)
      i2 <- Future(local.get)
      _  <- Future(local.update(i2 + 1))
      i3 <- Future(local.get)
    } yield {
      assertEquals(i1, 1)
      assertEquals(i2, 1)
      assertEquals(i3, 2)
      assertEquals(local.get, 2)
    }

    // TODO: f12 and f21 should be the other way around
    val f12 = f1.flatMap(_ => f2).map(_ => assertEquals(local.get, 20))
    val f21 = f2.flatMap(_ => f1).map(_ => assertEquals(local.get, 2))

    val f13 = f1.map(_ => assertEquals(local.get, 20))
    val f23 = f2.map(_ => assertEquals(local.get, 2))

    List(f12, f21, f13, f23).sequence_
  }

  testAsync("Task.eval.runToFuture is isolated from outside changes") {
    implicit val s: Scheduler = Scheduler.Implicits.traced

    val local = Local(0)

    val t1 = for {
      i1 <- Task(local.get)
      _  <- Task.sleep(10.millis)
      i2 <- Task(local.get)
    } yield assertEquals(i1, i2)

    val f = t1.runToFuture

    local.update(100)

    f
  }

}
