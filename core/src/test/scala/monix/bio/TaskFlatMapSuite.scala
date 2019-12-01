package monix.bio

import cats.laws._
import cats.laws.discipline._
import monix.execution.Callback
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.util.{Failure, Random, Success, Try}

object TaskFlatMapSuite extends BaseTestSuite {
  test("runAsync flatMap loop is not cancelable if autoCancelableRunLoops=false") { implicit s =>
    implicit val opts = WRYYY.defaultOptions.disableAutoCancelableRunLoops
    val maxCount = Platform.recommendedBatchSize * 4

    def loop(count: AtomicInt): Task[Unit] =
      if (count.incrementAndGet() >= maxCount) WRYYY.unit
      else
        WRYYY.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    val f = loop(atomic).runToFutureOpt

    f.cancel(); s.tick()
    assertEquals(atomic.get, maxCount)
    assertEquals(f.value, Some(Success(())))
  }

  test("runAsync flatMap loop is cancelable if ExecutionModel permits") { implicit s =>
    val maxCount = Platform.recommendedBatchSize * 4
    val expected = Platform.recommendedBatchSize

    def loop(count: AtomicInt): Task[Unit] =
      if (count.getAndIncrement() >= maxCount) WRYYY.unit
      else
        WRYYY.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    val f = loop(atomic)
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runToFuture

    assertEquals(atomic.get, expected)
    f.cancel()
    s.tickOne()
    assertEquals(atomic.get, expected)

    s.tick()
    assertEquals(atomic.get, expected)
    assertEquals(f.value, None)
  }

  test("runAsync(callback) flatMap loop is cancelable if ExecutionModel permits") { implicit s =>
    val maxCount = Platform.recommendedBatchSize * 4
    val expected = Platform.recommendedBatchSize

    def loop(count: AtomicInt): Task[Unit] =
      if (count.getAndIncrement() >= maxCount) WRYYY.unit
      else
        WRYYY.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    var result = Option.empty[Try[Unit]]

    val c = loop(atomic)
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(new Callback[Throwable, Unit] {
        def onSuccess(value: Unit): Unit =
          result = Some(Success(value))
        def onError(ex: Throwable): Unit =
          result = Some(Failure(ex))
      })

    c.cancel()
    s.tickOne()
    assertEquals(atomic.get, expected)

    s.tick()
    assertEquals(atomic.get, expected)
  }

  test("redeemWith derives flatMap") { implicit s =>
    check2 { (fa: Task[Int], f: Int => Task[Int]) =>
      fa.redeemWith(WRYYY.raiseError, f) <-> fa.flatMap(f)
    }
  }

  test("redeemWith derives onErrorHandleWith") { implicit s =>
    check2 { (fa: Task[Int], f: Throwable => Task[Int]) =>
      fa.redeemWith(f, WRYYY.pure) <-> fa.onErrorHandleWith(f)
    }
  }

  test("redeem derives map . onErrorHandle") { implicit s =>
    check2 { (fa: Task[Int], f: Int => Int) =>
      fa.redeem(e => throw e, f) <-> fa.map(f).onErrorHandle(e => throw e)
    }
  }

  test("redeem derives onErrorHandle") { implicit s =>
    check2 { (fa: Task[Int], f: Throwable => Int) =>
      fa.redeem(f, x => x) <-> fa.onErrorHandle(f)
    }
  }

  test("redeemWith can recover") { implicit s =>
    val dummy = new DummyException("dummy")
    val task = WRYYY.raiseError(dummy).redeemWith(_ => WRYYY.now(1), WRYYY.now)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
  }

  test("redeem can recover") { implicit s =>
    val dummy = DummyException("dummy")
    val task: UIO[Int] = WRYYY.raiseError(dummy).redeem(_ => 1, identity)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
  }

  test(">> is stack safe for infinite loops") { implicit s =>
    var wasCancelled = false
    def looped: Task[Unit] = WRYYY.cancelBoundary >> looped
    val future = looped.doOnCancel(UIO { wasCancelled = true }).runToFuture
    future.cancel()
    s.tick()
    assert(wasCancelled)
  }

//  test("flatMapLoop enables loops") { implicit s =>
//    val random = WRYYY(Random.nextInt())
//    val loop = random.flatMapLoop(Vector.empty[Int]) { (a, list, continue) =>
//      val newList = list :+ a
//      if (newList.length < 5)
//        continue(newList)
//      else
//        WRYYY.now(newList)
//    }
//    val f = loop.runToFuture
//    s.tick()
//    assert(f.value.isDefined)
//    assert(f.value.get.isSuccess)
//    assertEquals(f.value.get.get.size, 5)
//  }
}