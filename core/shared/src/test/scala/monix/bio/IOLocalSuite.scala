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

import cats.implicits._
import monix.catnap.{ConcurrentChannel, ConsumerF}
import monix.execution.exceptions.DummyException
import monix.execution.misc.Local
import monix.execution.{BufferCapacity, Scheduler}

import scala.concurrent.Future
import scala.concurrent.duration._

object IOLocalSuite extends SimpleIOTestSuite {
  implicit val ec: Scheduler = monix.execution.Scheduler.Implicits.global
  implicit val opts = IO.defaultOptions.enableLocalContextPropagation

  testAsync("IOLocal.apply") {
    val test =
      for {
        local <- IOLocal(0)
        v1    <- local.read
        _     <- UIO.now(assertEquals(v1, 0))
        _     <- local.write(100)
        _     <- UIO.shift
        v2    <- local.read
        _     <- UIO.now(assertEquals(v2, 100))
        _     <- local.clear
        _     <- UIO.shift
        v3    <- local.read
        _     <- UIO.now(assertEquals(v3, 0))
      } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal.wrap") {
    val local = Local(0)
    val test =
      for {
        local <- IOLocal.wrap(Task(local))
        v1    <- local.read
        _     <- UIO.now(assertEquals(v1, 0))
        _     <- local.write(100)
        _     <- UIO.shift
        v2    <- local.read
        _     <- UIO.now(assertEquals(v2, 100))
        _     <- local.clear
        _     <- UIO.shift
        v3    <- local.read
        _     <- UIO.now(assertEquals(v3, 0))
      } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal!.bind") {
    val test =
      for {
        local <- IOLocal(0)
        _     <- local.write(100)
        _     <- UIO.shift
        v1    <- local.bind(200)(local.read.map(_ * 2))
        _     <- UIO.now(assertEquals(v1, 400))
        v2    <- local.read
        _     <- UIO.now(assertEquals(v2, 100))
      } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal!.bindL") {
    val test =
      for {
        local <- IOLocal(0)
        _     <- local.write(100)
        _     <- UIO.shift
        v1    <- local.bindL(Task.eval(200))(local.read.map(_ * 2))
        _     <- UIO.now(assertEquals(v1, 400))
        v2    <- local.read
        _     <- UIO.now(assertEquals(v2, 100))
      } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal!.bindClear") {
    val test =
      for {
        local <- IOLocal(200)
        _     <- local.write(100)
        _     <- UIO.shift
        v1    <- local.bindClear(local.read.map(_ * 2))
        _     <- UIO.now(assertEquals(v1, 400))
        v2    <- local.read
        _     <- UIO.now(assertEquals(v2, 100))
      } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal canceled") {
    import scala.concurrent.duration._

    val test: UIO[Unit] = for {
      local  <- IOLocal[String]("Good")
      forked <- UIO.sleep(1.second).start
      _      <- local.bind("Bad!")(forked.cancel).start
      _      <- UIO.sleep(1.second)
      s      <- local.read
      _      <- UIO.now(assertEquals(s, "Good"))
    } yield ()

    test.runToFutureOpt
  }

  testAsync("IOLocal!.local") {
    val test =
      for {
        taskLocal <- IOLocal(200)
        local     <- taskLocal.local
        v1        <- taskLocal.read
        _         <- UIO.now(assertEquals(local.get, v1))
        _         <- taskLocal.write(100)
        _         <- UIO.now(assertEquals(local.get, 100))
        _         <- UIO.now(local.update(200))
        v2        <- taskLocal.read
        _         <- UIO.now(assertEquals(v2, 200))
        _         <- UIO.shift
        v3        <- taskLocal.bindClear(UIO.now(local.get * 2))
        _         <- UIO.now(assertEquals(v3, 400))
        v4        <- taskLocal.read
        _         <- UIO.now(assertEquals(v4, local.get))
      } yield ()

    test.runToFutureOpt
  }

  testAsyncE("TaskLocals get restored in IO.create on error") {
    val dummy = "dummy"
    val task = IO.create[String, Int] { (_, cb) =>
      ec.execute(new Runnable {
        def run() = cb.onError(dummy)
      })
    }

    val t = for {
      local <- IOLocal(0)
      _     <- local.write(10)
      i     <- task.onErrorRecover { case `dummy` => 10 }
      l     <- local.read
      _     <- UIO.eval(assertEquals(i, 10))
      _     <- UIO.eval(assertEquals(l, 10))
    } yield ()

    t.attempt.runToFutureOpt
  }

  testAsync("TaskLocals get restored in IO.create on termination") {
    val dummy = DummyException("dummy")
    val task = IO.create[String, Int] { (_, cb) =>
      ec.execute(new Runnable {
        def run() = cb.onTermination(dummy)
      })
    }

    val t = for {
      local <- IOLocal(0)
      _     <- local.write(10)
      i     <- task.redeemCause(_ => 10, identity)
      l     <- local.read
      _     <- UIO.eval(assertEquals(i, 10))
      _     <- UIO.eval(assertEquals(l, 10))
    } yield ()

    t.runToFutureOpt
  }

  testAsync("IOLocal should work with bracket") {
    val t = for {
      local <- IOLocal(0)
      _ <-
        UIO.unit.executeAsync
          .guarantee(local.write(10))
      value <- local.read
      _     <- UIO.eval(assertEquals(value, 10))
    } yield ()

    t.runToFutureOpt
  }

  testAsync("IOLocal.isolate should prevent context changes") {
    val t = for {
      local <- IOLocal(0)
      inc = local.read.map(_ + 1).flatMap(local.write)
      _    <- inc
      res1 <- local.read
      _    <- UIO(assertEquals(res1, 1))
      _    <- IOLocal.isolate(inc)
      res2 <- local.read
      _    <- UIO(assertEquals(res1, res2))
    } yield ()

    t.runToFutureOpt
  }

  testAsync("IOLocal interop with future via deferFutureAction") {
    val t = for {
      local  <- IOLocal(0)
      unsafe <- local.local
      _      <- local.write(1)
      _ <- IO.deferFutureAction { implicit ec =>
        Future {
          assertEquals(unsafe.get, 1)
        }.map { _ =>
          unsafe := 50
        }
      }
      x <- local.read
      _ <- UIO(assertEquals(x, 50))
    } yield ()

    t.runToFutureOpt
  }

  testAsync("IOLocal.bind scoping works") {
    val tl = IOLocal(999)
    val t = Task
      .map3(tl, tl, tl) { (l1, l2, l3) =>
        def setAll(x: Int) = Task.traverse(List(l1, l2, l3))(_.write(x))
        l1.bind(0) {
          setAll(0) >> l2.bind(1) {
            setAll(1)
          }
        } >> List(l1, l2, l3).traverse(_.read).map(assertEquals(_, List(999, 0, 1)))
      }
      .flatten
      .map(_ => ())
    t.runToFutureOpt
  }

  testAsync("IOLocal.bind actually isolates reads") {
    val t = for {
      l1 <- IOLocal(0)
      l2 <- IOLocal(0)
      f  <- l1.bind(0)(UIO.sleep(1.second) *> (l1.read, l2.read).tupled).start
      _  <- l1.write(5)
      _  <- l2.write(5)
      r  <- f.join
      _ = assertEquals(r._1, 0)
      _ = assertEquals(r._2, 5)
    } yield ()

    t.runToFutureOpt
  }

  testAsync("IOLocal.isolate with ConcurrentChannel") {
    val bufferSize = 16

    class Test(
      l: IOLocal[String],
      ch: ConcurrentChannel[Task, Unit, Int]
    ) {
      private[this] def produceLoop(n: Int): Task[Unit] =
        if (n == 0) Task.unit
        else
          ch.push(n) >> l.read.flatMap { s =>
            Task(assertEquals(s, "producer"))
          } >> produceLoop(n - 1)

      def produce: Task[Unit] =
        for {
          _ <- ch.awaitConsumers(1)
          _ <- l.write("producer")
          _ <- produceLoop(bufferSize * 2)
          _ <- ch.halt(())
        } yield ()

      def consume: Task[Unit] =
        ch.consume.use { c =>
          c.pull
            .delayResult(1.milli)
            .flatMap { x =>
              l.write(x.toString).as(x)
            }
            .iterateWhile(_.isRight)
            .void
        }
    }

    val t = for {
      tl <- IOLocal("undefined")
      ch <- ConcurrentChannel[Task].withConfig[Unit, Int](
        ConsumerF.Config(
          capacity = BufferCapacity.Bounded(bufferSize).some
        )
      )
      test = new Test(tl, ch)
      _ <- IOLocal.isolate(test.produce) &> IOLocal.isolate(test.consume)
    } yield ()

    t.runToFutureOpt
  }
}
