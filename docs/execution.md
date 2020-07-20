---
id: execution
title: Executing IO
---

As mentioned in other sections, `IO` is lazily evaluated - it needs to be executed to start doing anything.

## BIOApp

The ideal way to use `IO` is to run it only once at the edge of your program, in Main.

You can go a step further and use `BIOApp` to run the effect for you and prepare a basic environment.
`BIOApp` piggybacks on [IOApp from Cats-Effect](https://typelevel.org/cats-effect/datatypes/ioapp.html) which brings
convenient features like safely releasing resources in case of `Ctrl-C` or `kill` command.

```scala mdoc:silent
import cats.effect._
import monix.bio._

object Main extends BIOApp {
  def run(args: List[String]): UIO[ExitCode] =
    args.headOption match {
      case Some(name) =>
        UIO(println(s"Hello, $name.")).map(_ => ExitCode.Success)
      case None =>
        UIO(System.err.println("Usage: MyApp name")).map(_ => ExitCode(2))
    }
}
```

## Manual Execution

If you'd prefer to run manually, there are plenty of options.
This section will cover just a few ones - all variants are prefixed with "run" and should be easy to find in API.
As an optimization, `IO` will run on the current thread up to the first asynchronous boundary.
You can use `executeAsync` to make sure entire task will run asynchronously.

Note that all methods to run `IO` require [Scheduler](https://monix.io/docs/3x/execution/scheduler.html).
Examples will use `Scheduler.global`, which is a good default for most applications.

### Running to Future

`IO.runToFuture` starts the execution and returns a `CancelableFuture`, which will complete when the task finishes.
`CancelableFuture` extends standard `scala.concurrent.Future` and adds an ability to cancel it. 
Calling `cancel` will plug into `IO` cancelation.

```scala mdoc:silent
import monix.bio.Task
import monix.execution.CancelableFuture
import scala.concurrent.duration._

implicit val s = monix.execution.Scheduler.global

val task = Task(1 + 1).delayExecution(1.second)

val result: CancelableFuture[Int] =
  task.runToFuture

// If we change our mind
result.cancel()
```

All potential errors will be exposed as a failed `Future`.
One gotcha is that `runToFuture` requires error type to be `E <:< Throwable`.
Thankfully, it also applies to `Nothing`, so if we work with typed errors, we can handle error just before running `IO`.
Probably the most convenient way is to use `attempt`:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.CancelableFuture

implicit val s = monix.execution.Scheduler.global

val task: IO[Int, Int] = IO.raiseError(20)

val result: CancelableFuture[Either[Int, Int]] =
  task.attempt.runToFuture
```

Typed errors will be exposed as `Left`, and terminal errors will result in a failed `Future`.

### Running with a callback

If returning a `Future` is too heavy for your needs, you can use `def runAsync`, which accepts a callback and returns a cancelation token.

```scala mdoc:silent:reset
import monix.bio.Cause
import monix.bio.Task
import scala.concurrent.duration._

implicit val s = monix.execution.Scheduler.global

val task = Task(1 + 1).delayExecution(1.second)

val cancelable = task.runAsync {
  case Right(value) =>
    println(value)
  case Left(Cause.Error(ex)) =>
    System.err.println(s"ERROR: ${ex.getMessage}")
  case Left(Cause.Termination(ex)) =>
    System.err.println(s"TERMINATION: ${ex.getMessage}")
}

// If we change our mind...
cancelable.cancel()
```

If your needs are even more modest, use `runAsyncAndForget`, which will run `IO` in "fire-and-forget" fashion.

### Blocking for a result

Monix is [against blocking threads](https://monix.io/docs/3x/best-practices/blocking.html), but when you have to do it,
you can use `runSyncUnsafe`, or `runToFuture` in combination with `Await.result` from the standard library.