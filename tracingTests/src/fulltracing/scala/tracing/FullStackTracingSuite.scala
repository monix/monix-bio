package tracing

import monix.bio.tracing.{IOEvent, IOTrace}
import monix.bio.{BaseTestSuite, IO, UIO, Task}

/**
  * All Credits to https://github.com/typelevel/cats-effect and https://github.com/RaasAhsan
  */
object FullStackTracingSuite extends BaseTestSuite {

  def traced[E, A](io: IO[E, A]): IO[E, IOTrace] =
    io.flatMap(_ => IO.trace)

  testAsync("captures map frames") { implicit s =>
    val task = Task.pure(0).map(_ + 1).map(_ + 1)

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 5)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }.count(_.stackTrace.exists(_.getMethodName == "map")),
          3)
      }

    test.runToFuture
  }

  testAsync("captures bind frames") { implicit s =>
    val task = Task.pure(0).flatMap(a => Task(a + 1)).flatMap(a => Task(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 7)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }
            .count(_.stackTrace.exists(_.getMethodName == "flatMap")),
          3
        ) // the extra one is used to capture the trace
      }

    test.runToFuture
  }

  testAsync("captures async frames") { implicit s =>
    val task = Task.async[Int](_(Right(0))).flatMap(a => Task(a + 1)).flatMap(a => Task(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 7)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }.count(_.stackTrace.exists(_.getMethodName == "async")),
          1)
      }

    test.runToFuture
  }

  testAsync("captures pure frames") { implicit s =>
    val task = Task.pure(0).flatMap(a => Task.pure(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 5)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }.count(_.stackTrace.exists(_.getMethodName == "pure")),
          2)
      }

    test.runToFuture
  }

  testAsync("full stack tracing captures eval frames") { implicit s =>
    val task = Task(0).flatMap(a => Task(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 5)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }.count(_.stackTrace.exists(_.getMethodName == "eval")),
          2)
      }

    test.runToFuture
  }

  testAsync("full stack tracing captures suspend frames") { implicit s =>
    val task = Task.suspend(Task(1)).flatMap(a => Task.suspend(Task(a + 1)))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 7)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }
            .count(_.stackTrace.exists(_.getMethodName == "suspend")),
          2)
      }

    test.runToFuture
  }

  testAsync("captures raiseError frames") { implicit s =>
    val task = Task(0).flatMap(_ => Task.raiseError(new Throwable())).onErrorHandleWith(_ => Task.unit)

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 6)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }
            .count(_.stackTrace.exists(_.getMethodName == "raiseError")),
          1)
      }

    test.runToFuture
  }

  testAsync("captures bracket frames") { implicit s =>
    val task = Task.unit.bracket(_ => Task.pure(10))(_ => UIO.unit).flatMap(a => Task(a + 1)).flatMap(a => Task(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 12)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }
            .count(_.stackTrace.exists(_.getMethodName == "bracket")),
          1)
      }

    test.runToFuture
  }

  testAsync("captures bracketCase frames") { implicit s =>
    val task =
      Task.unit.bracketCase(_ => Task.pure(10))((_, _) => UIO.unit).flatMap(a => Task(a + 1)).flatMap(a => Task(a + 1))

    val test =
      for (r <- traced(task)) yield {
        assertEquals(r.captured, 12)
        assertEquals(
          r.events.collect { case e: IOEvent.StackTrace => e }
            .count(_.stackTrace.exists(_.getMethodName == "bracketCase")),
          1)
      }

    test.runToFuture
  }
}
