---
id: resource-safety
title: Resource Safety
---

Many tasks use some kind of resource, such as a connection pool, a file handle, or a socket. 
It's crucial to close them when we finish using them, otherwise we end up with resource leaks.

`Task` provides ways to do it safely and goes beyond the capabilities of a `try-catch-finally` block.

## Running finalizer

`guarantee` ensures that we run the provided finalizer regardless of the exit condition, be it successful completion, failure, or cancellation.

`guaranteeCase` is a variant which takes `ExitCase[Cause[E]] => UIO[Unit]` and can distinguish between different exit conditions.

```scala mdoc:silent:reset
import cats.effect.ExitCase
import monix.bio.{Task, UIO}
import monix.execution.Scheduler.Implicits.global

val slowerTask = Task.never.guaranteeCase {
  case ExitCase.Completed => UIO(println("Successful completion"))
  case ExitCase.Error(e) => UIO(println(s"Encountered an error: $e"))
  case ExitCase.Canceled => UIO(println("Task has been cancelled"))
}
  
val fasterTask = UIO.evalAsync(10)
  
Task.race(slowerTask, fasterTask).runSyncUnsafe()
 //=> Task has been cancelled
```

`Task.race` cancels the slower task which executes the corresponding finalizer.

## Safe acquisition and release

`bracket` is a more general operator for the `try-with-resources` pattern, but it works for pure effect types like `Task`,
and supports concurrency and cancellation.

```scala mdoc:compile-only
import java.io._
import monix.bio.{Task, UIO}

def readFirstLine(file: File): Task.Unsafe[String] = {
  val acquire = Task(new BufferedReader(new FileReader(file)))
  // Usage (the try block)
  val use: BufferedReader => Task.Unsafe[String] = in => Task(in.readLine())
  // Releasing the reader (the finally block)
  val release: BufferedReader => UIO[Unit] = in => Task(in.close()).onErrorHandle(_ => ())

  acquire.bracket(use)(release)
}
```

If `Task` is successful, the result will be signaled in `use`.

Note that `release` expects `UIO` - you can ignore any errors, or raise them as terminal errors but make sure to consider what should happen if the finalizer fails.
Don't leak any resources!

Similarly to `guarantee`, there is also a `bracketCase` variant.
Let's use it to only close the `BufferedReader` on cancelation:

```scala mdoc:compile-only
import java.io._
import cats.effect.ExitCase
import monix.bio.{Task, UIO}
import monix.bio.Cause

def readFirstLine(file: File): Task.Unsafe[String] = {
  val acquire = Task(new BufferedReader(new FileReader(file)))
  // Usage (the try block)
  val use: BufferedReader => Task.Unsafe[String] = in => Task(in.readLine())
  // Releasing the reader (the finally block)
  val release: (BufferedReader, ExitCase[Cause[Throwable]]) => UIO[Unit] = {
    case (_, ExitCase.Error(_) | ExitCase.Completed) => UIO.unit
    case (in, ExitCase.Canceled) => Task(in.close()).onErrorHandle(_ => ())
  }

  acquire.bracketCase(use)(release)
}
```

## cats.effect.Resource

[Resource](https://typelevel.org/cats-effect/datatypes/resource.html) is compatible with `Task`.
It allows us to define a task which has already specified `acquire` and `release` logic and clearly informs the users that they are not responsible for closing the resource.

Let's take a look at the example from the Cats-Effect docs, but written in `Task`:

```scala mdoc:silent:reset
import cats.effect.Resource
import monix.bio.{UIO, Task}
import monix.execution.Scheduler.Implicits.global

val acquire: UIO[String] = UIO(println("Acquire cats...")) >> UIO("cats")
val release: String => UIO[Unit] = _ => UIO(println("...release everything"))
val addDogs: String => UIO[String] = x =>
  UIO(println("...more animals...")) >> UIO.pure(x ++ " and dogs")
val report: String => Task.Unsafe[String] = x =>
  UIO(println("...produce weather report...")) >> UIO("It's raining " ++ x)

Resource.make(acquire)(release).evalMap(addDogs).use(report).runSyncUnsafe()
//=> Acquire cats...
//=> ...more animals...
//=> ...produce weather report...
//=> ...release everything
// Returns "It's raining cats and dogs"
```

The main drawback is that `Resource.use` requires the error type to be `Throwable` but we can work with typed errors for `acquire` and `release`.

## Semantics

### Error during finalizers

If `release` fails, the error is signalled as a terminal failure:

```scala
import monix.bio.{Task, UIO}
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException
  
val acquire = UIO("resource")
val use: String => Task[String, String] = s => Task.now(s)
val release: String => UIO[Unit] = _ => UIO (throw DummyException("unexpected error"))

val task: Task[String, String] = acquire.bracket(use)(release)
val result: Either[String, String] = task.attempt.runSyncUnsafe()

//=> Exception in thread "main" monix.execution.exceptions.DummyException: unexpected error
```

If both `use` and `release` fail, the errors are merged via `Platform.composeErrors` and signalled as a terminal failure:

```scala
import monix.bio.{Task, UIO}
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

val acquire = UIO("resource")
val use: String => Task[String, String] = s => Task.raiseError(s"I don't like $s")
val release: String => UIO[Unit] = _ => UIO (throw DummyException("unexpected error"))

val task: Task[String, String] = acquire.bracket(use)(release)
val result: Either[String, String] = task.attempt.runSyncUnsafe()

//=> Exception in thread "main" monix.execution.exceptions.UncaughtErrorException(I don't like resource)
//=>   ...
//=>   Suppressed: monix.execution.exceptions.DummyException: unexpected error
```

### Nesting of finalizers

It is allowed to nest, or install multiple finalizers:

```scala mdoc:silent:reset
import monix.bio.UIO
import monix.execution.Scheduler.Implicits.global

UIO(println("action"))
  .guarantee(
    UIO(println("finalizer A")).guarantee(UIO(println("finalizer B")))
  )
  .guarantee(UIO(println("finalizer C")))
  .runSyncUnsafe()

//=> action
//=> finalizer A
//=> finalizer B
//=> finalizer C
```

All finalizers will be executed even if any of them fail:

```scala
import monix.bio.UIO
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

UIO(println("action"))
  .guarantee(UIO(println("finalizer A")))
  .guarantee(UIO(throw DummyException("dummy")))
  .guarantee(UIO(println("finalizer C")))
  .runSyncUnsafe()

//=> action
//=> finalizer A
//=> finalizer C
//=> Exception in thread "main" monix.execution.exceptions.DummyException: dummy
```

### Ordering guarantees in the face of cancellation

When `Task` is canceled, the `cancel` method will not return until all finalizers have executed:

```scala mdoc:silent:reset
import monix.bio.{Task, UIO}
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration._

val taskA = UIO(println("finished A"))
  .delayExecution(50.millis)
  .guarantee(UIO(println("finalized A")).delayExecution(100.millis))

val taskB = UIO(println("finished B"))

Task.race(taskA, taskB).flatMap(_ => UIO(println("Race Over"))).runSyncUnsafe()

//=> finished B
//=> finalized A
//=> Race Over
```

*Corner case*: `cancel` will not back-pressure on finalizers if source `Task` is set to be `uncancelable`. 
It is unfortunate outcome of the current implementation details. You can expect improvements in this area with Cats-Effect 3 support.
