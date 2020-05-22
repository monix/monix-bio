---
layout: docs
title:  "Error Handling"
position: 3
---

# Error Handling

When `BIO` fails with an error it short-circuits the computation and returns the error as a result:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val fa = BIO(println("A"))
val fb = BIO.raiseError(DummyException("boom"))
val fc = BIO(println("C"))

val task = fa.flatMap(_ => fb).flatMap(_ => fc)

task.runSyncUnsafe()
//=> A
//=> Exception in thread "main" monix.execution.exceptions.DummyException: boom
```

We can handle the error to prevent it with one of many available methods. 
For better discoverability, they are often prefixed with `onError`.

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val fa = BIO(println("A"))
val fb = BIO.raiseError(DummyException("boom"))
val fc = BIO(println("C"))

val task = fa
  .flatMap(_ => fb)
  .onErrorHandleWith(_ => BIO(println("B recovered")))
  .flatMap(_ => fc)
  .onErrorHandleWith(_ => BIO(println("C recovered")))

task.runSyncUnsafe()
//=> A
//=> B recovered
//=> C
```

## Error Channels

Many applications divide errors in two types:
- Recoverable errors which can be acted upon and often have a meaning in the business domain. 
  Examples: Insufficient permissions for a given action, temporary network failure.
- Non-Recoverable errors which are often fatal or it is not sensible to try to recover from them.
  Examples: `StackOverflow`, throwing an exception in a pure function (programmer's error).
  
`BIO[E, A]` follow this pattern and can fail with two kind of errors:
- Errors of type `E` which represents recoverable errors. Other common names are "typed" or "expected" errors.
- Errors of type `Throwable` for non-recoverable errors. We call them "terminal" or "unexpected" errors. You might also see terminology like "defect" or "unchecked failure" in other libraries.

The general guideline is to use a typed error channel for errors that are expected to be handled or have a value for the caller.
The internal channel (non-recoverable errors) should be used for errors which can't be handled properly or can be only handled somewhere deep downstream in a generic manner (let's say to return `InternalServerError` at the edges).

Non-recoverable errors are hidden in the internal error channel which has very few operators and is supposed to be used as rarely as possible.
Most of these errors are outside of our control and ideally we don't have to burden our minds with it and use a smaller, more comprehensible errors' domain for the most part of the coding.

The number of possible recoverable errors is often limited and each of them could have a specific way to handle in the business logic.
`E` can be any type which allows us to be very precise. 
For instance, we can choose `E` to be an ADT reflecting errors in our business domain or even use `Nothing` to show that we don't have to worry about recovering from any errors.
Possible errors are provided in the type signature which serves as an always up to date documentation and allows us to easily statically check if all errors are handled.
Furthermore, if there is a change and it introduces new errors we might easily miss it.

Consider the following example:

```scala mdoc:silent
import monix.bio.{BIO, Task}
  
case class ForbiddenNumber() extends Exception

def numberService(i: Int): Task[Int] =
  if (i == 0) BIO.raiseError(ForbiddenNumber())
  else BIO.now(i * 2)

def callNumberService(i: Int): Task[Int] = numberService(i).onErrorHandleWith {
  case ForbiddenNumber() => callNumberService(i + 1) // try with a different number
  case other => BIO.raiseError(other) // propagate error
}
```

When writing `callNumberService` method we have to check the implementation of `numberService` to see what kind of 
errors can we expect because the type signature specifies only `Throwable` - so it can be pretty much anything. 
On top of that, we might have to add `case other => ...` just to be safe in case we missed any error and to make our pattern matching exhaustive.

At some point, the implementation might change:

```scala mdoc:silent
import monix.bio.{BIO, Task}
import scala.concurrent.duration._

sealed trait NumberServiceErrors extends Exception

case class ForbiddenNumber() extends NumberServiceErrors
  
case class ServiceTimeout(duration: FiniteDuration) extends NumberServiceErrors

def numberService(i: Int): Task[Int] =
  // Check if we should timeout the caller
  if (i == 5) BIO.raiseError(ServiceTimeout(10.second))
  else if (i == 0) BIO.raiseError(ForbiddenNumber())
  else BIO.now(i * 2)
```

We introduced a `ServiceTimeout` error which tells the users that their requests will be accepted after it passes.
It's easy to forget to update `callNumberService` to support the new behavior and if we didn't have `case other => ...` then we would end up with errors at runtime.
The method `callNumberService` would also compile if we changed error class of `ForbiddenNumber()` leading to more issues.

Now let's see how it would look like if we leverage `BIO` capabilities:

```scala mdoc:silent
import monix.bio.{BIO, UIO}
  
case class ForbiddenNumber()

def numberService(i: Int): BIO[ForbiddenNumber, Int] =
  if (i == 0) BIO.raiseError(ForbiddenNumber())
  else BIO.now(i * 2)

def callNumberService(i: Int): UIO[Int] = numberService(i).onErrorHandleWith {
  case ForbiddenNumber() => callNumberService(i + 1) // try with a different number
}
```

Now `numberService` specifies possible errors in the type signature so it is immediately clear to us and the compiler what are expected failures.
We don't need `case other => ...` because there are no other possible errors and if they appear in the future the code will stop compiling.
As a nice bonus, `callNumberService` returns `UIO[Int]` (type alias of `BIO[Nothing, Int]`) which tells whoever uses `callNumberService` that they don't have to expect any errors.

If we change `numberService` errors then we will have to change the signature as well:

```scala mdoc:silent
import monix.bio.{BIO, UIO}  
import scala.concurrent.duration._

sealed trait NumberServiceErrors

case class ForbiddenNumber() extends NumberServiceErrors

case class ServiceTimeout(duration: FiniteDuration) extends NumberServiceErrors

def numberService(i: Int): BIO[NumberServiceErrors, Int] =
  // Check if we should timeout the caller
  if (i == 5) BIO.raiseError(ServiceTimeout(10.second))
  else if (i == 0) BIO.raiseError(ForbiddenNumber())
  else BIO.now(i * 2)

def callNumberService(i: Int): UIO[Int] = numberService(i).onErrorHandleWith {
  case ForbiddenNumber() => callNumberService(i + 1) // try with a different number
  // will give a warning without this line!
  case ServiceTimeout(timeout) => callNumberService(i).delayExecution(timeout)
}
```

If the application uses `scalacOptions += "-Xfatal-warnings"` in `build.sbt` we will get the following error if we forget to change `callNumberService`:

```
Error:(29, 80) match may not be exhaustive.
It would fail on the following input: ServiceTimeout(_)
  def callNumberService(i: Int): UIO[Int] = numberService(i).onErrorHandleWith {
```

Similar approach is often used with single parameter effects in combination with `Either` or `EitherT`, that is:

```scala
def numberService(i: Int): IO[Either[NumberServiceErrors, Int]]
```

`IO` can fail with `Throwable` (`BIO`'s terminal error channel) and `Either` can return `Left` of any `E` (`BIO`'s typed error channel).
`BIO` forces this convention which makes it more convenient and safer to follow it but if you are familiar with `IO` of `Either` then the spirit is the same.

I recommend [this article by John De Goes](https://degoes.net/articles/bifunctor-io) if you are interested in the original motivations behind the idea of embedding this pattern directly in the data type.

## Producing a failed BIO

An error can occur when an `Exception` is thrown or we can construct it ourselves with dedicated builder methods.

### BIO.raiseError

Use `BIO.raiseError` if you already have an error value:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.Scheduler.Implicits.global

val error = "error"
val task: BIO[String, Int] = BIO.raiseError(error)

// Left("error")
task.attempt.runSyncUnsafe()
```

### BIO.terminate

`BIO.terminate` can raise a terminal error (second channel with `Throwable`):

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

val error = DummyException("error")
// It doesn't affect the signature
val task: BIO[String, Int] = BIO.terminate(error)

task.attempt.runSyncUnsafe()
//=> Exception in thread "main" monix.execution.exceptions.DummyException: error
```

### Catching errors in BIO.eval

`BIO.eval` (and `BIO.apply`) will catch any errors that are thrown in the method's body and expose them as typed errors:

```scala mdoc:silent
import monix.bio.{BIO, Task}
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

val error = DummyException("error")
val task: Task[Int] = BIO.eval { throw error }

// Left(DummyException("error"))
task.attempt.runSyncUnsafe()
```

### Catching errors in BIO.evalTotal

If we are sure that our side-effecting code won't have any surprises we can use `BIO.evalTotal` but if we are wrong, the error
will be caught in internal error channel:

```scala mdoc:silent
import monix.bio.{BIO, UIO}
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException

val error = DummyException("error")
val task: UIO[Int] = BIO.evalTotal { throw error }

task.attempt.runSyncUnsafe()
//=> Exception in thread "main" monix.execution.exceptions.DummyException: error
```

Other methods which return `UIO` or use generic `E` (not fixed to `Throwable`) like `map` / `flatMap` will behave the same way in case of throwing an exception.

## Recovering from Errors

When `BIO` fails, it will skip all subsequent operations until the error is handled.
Typed and terminal errors are in different categories - handling typed error will not do anything to unexpected errors but
error handling functions for terminal errors handle "normal" errors as well. 

The section only covers the main error handling operators, refer to [API Documentation](https://monix.github.io/monix-bio/api/monix/bio/BIO.html) for the full list.

### Typed Errors

#### Exposing Errors

`attempt` and `materialize` take the error away and return it as a normal value:

```scala 
final def attempt: UIO[Either[E, A]]
final def materialize(implicit ev: E <:< Throwable): UIO[Try[A]]
```

Note that return type is `UIO` indicating that there are no more expected errors to handle.

```scala mdoc:silent
import monix.bio.{BIO, UIO}
import monix.execution.Scheduler.Implicits.global

val error = "error"
val task: BIO[String, Int] = BIO.raiseError(error)
val attempted: UIO[Either[String, Int]] = task.attempt

// Left("error")
attempted.runSyncUnsafe()
```

It is common to use `attempt` before `runToFuture` or `runSyncUnsafe`. 
Typed error will be exposed as a `Left` and terminal error will result in a failed `Future` or an exception thrown (in `runSyncUnsafe`)

Both methods have corresponding reverse operations:

```scala 
final def rethrow[E1 >: E, B](implicit ev: A <:< Either[E1, B]): BIO[E1, B]
final def dematerialize[B](implicit evE: E <:< Nothing, evA: A <:< Try[B]): Task[B]
```

Example:

```scala mdoc:silent
import monix.bio.BIO

val error = "error"
// same as BIO.raiseError
val task: BIO[String, Int] = BIO.raiseError(error).attempt.rethrow 
```

#### onErrorHandle & onErrorHandleWith

`BIO.onErrorHandleWith` is an operation that takes a function mapping possible exceptions to a desired fallback outcome, so we could do this:

```scala mdoc:silent
import monix.bio.{BIO, UIO}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._

case class TimeoutException()

val source: BIO[TimeoutException, String] =
  BIO.evalTotal("Hello!")
    .delayExecution(10.seconds)
    .timeoutWith(3.seconds, TimeoutException())

val recovered: UIO[String] = source.onErrorHandleWith {
  _: TimeoutException => BIO.now("Recovered!")
}

recovered.attempt.runToFuture.foreach(println)
//=> Recovered!
```

`BIO.onErrorHandle` is a variant which takes a pure recovery function `E => B` instead of effectful `E => BIO[E1, B]` which could also fail.

#### redeem & redeemWith

`BIO.redeem` and `BIO.redeemWith` are a combination of `map` + `onErrorHandle` and `flatMap` + `onErrorHandleWith` respectively.

If `task` is successful then:

`task.redeemWith(fe, fb) <-> task.flatMap(fb)`

And when `task` is failed:

`task.redeemWith(fe, fb) <-> task.onErrorHandleWith(fe)`

Instead of:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val f1 = BIO.raiseError(DummyException("boom"))
val f2 = BIO(println("A"))

val task = f1
  .flatMap(_ => f2)
  .onErrorHandleWith(_ => BIO(println("Recovered!")))

task.runSyncUnsafe()
//=> Recovered!
```

You can do this:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val f1 = BIO.raiseError(DummyException("boom"))
val f2 = BIO(println("A"))

val task = f1
  .redeemWith(_ => BIO(println("Recovered!")), _ => f2)

task.runSyncUnsafe()
//=> Recovered!
```

The latter will be more efficient in terms of memory allocations.

### Terminal Errors

Terminal errors ignore all typed error handlers and can only be caught by more powerful methods.

The example below shows how `redeemWith` does nothing to handle unexpected error even if it uses the same type:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

// Note BIO.termiante instead of BIO.raiseError
val f1 = BIO.terminate(DummyException("boom"))
val f2 = BIO(println("A"))

val task = f1
  .redeemWith(_ => BIO(println("Recovered!")), _ => f2)

task.runSyncUnsafe()
//=> Exception in thread "main" monix.execution.exceptions.DummyException: boom
```

There are special variants of `redeem` and `redeemWith` which are called `redeemCause` and `redeemCauseWith` respectively.
`BIO.redeemCause` takes a `Cause[E] => B` function instead of `E => B` to recover and
`BIO.redeemCauseWith` uses a `Cause[E] => BIO[E1, B]`.

`Cause` is defined as follows:

```scala 
sealed abstract class Cause[+E] extends Product with Serializable {
  // few methods
}

object Cause {
  final case class Error[+E](value: E) extends Cause[E]

  final case class Termination(value: Throwable) extends Cause[Nothing]
}
```

Let's modify the previous example to use `redeemCause`:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

// Note BIO.termiante instead of BIO.raiseError
val f1 = BIO.terminate(DummyException("boom"))
val f2 = BIO(println("A"))

val task = f1
  .redeemCauseWith(_ => BIO(println("Recovered!")), _ => f2)

task.runSyncUnsafe()
//=> Recovered!
```

Basically it is a more powerful version which can access both error channels.
In your actual application you might find yourself using typed error handlers (`onErrorHandle`, `redeem` etc.) almost all of the time 
and only use `Cause` variants when absolutely necessary like at the edges of the application if you don't want to pass failed `BIO` / `Future` to your HTTP library.

## Mapping Errors

### mapError

`BIO.mapError` will not handle any error but it can transform it to something else.

It can be useful to convert an error from a smaller type to a bigger type:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.Scheduler.Implicits.global
import java.time.Instant

case class ErrorA(i: Int)
case class ErrorB(errA: ErrorA, createdAt: Instant)

val task1: BIO[ErrorA, String] = BIO.raiseError(ErrorA(10))
val task2: BIO[ErrorB, String] = task1.mapError(errA => ErrorB(errA, Instant.now()))
```

### tapError

`BIO.tapError` can peek at the error value and execute provided `E => BIO[E1, B]` function without handling the original error.

For instance, we might want to log the error without handling it:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val f1 = BIO.raiseError(DummyException("boom"))
val f2 = BIO(println("A"))

val task = f1
  .tapError(e => BIO(println("Incoming error: " + e)))

task.runSyncUnsafe()
//=> Incoming error: monix.execution.exceptions.DummyException: boom
//=> Exception in thread "main" monix.execution.exceptions.DummyException: boom
```

### Moving errors from typed error channel

If you are sure that your `BIO` shouldn't have any errors and if there are any they should shutdown the task as soon as possible
there is `hideErrors` and `hideErrorsWith` which will hide the error from the type signature and raise it as a terminal error.

```scala mdoc:silent
import monix.bio.{BIO, UIO}
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val task: UIO[Int] = BIO
  .raiseError(DummyException("boom!"))
  .hideErrors
  .map(_ => 10)

// Some(Failure(DummyException(boom!)))
task.runToFuture.value
```

If your `E` is not `Throwable` you can use `hideErrorsWith` which takes a `E => Throwable` function.

This method is handy if you are using generic Cats-Effect based libraries, for example:

```scala mdoc:silent
import monix.bio.{BIO, Task}
import monix.catnap.ConcurrentQueue

val queueExample: BIO[Throwable, String] = for {
  queue <- ConcurrentQueue[Task].bounded[String](10)
  _ <- queue.offer("Message")
  msg <- queue.poll
} yield msg
```

`monix.catnap.ConcurrentQueue` works for a generic effect type (`cats.effect.IO`, `monix.eval.Task`, `zio.ZIO`) but it is written
in terms of type classes which unfortunately don't support two channels of errors and fix everything as `Throwable`.

These methods don't throw any errors so we can safely hide them and have our typed errors back:

```scala mdoc:silent 
import monix.bio.{Task, UIO}
import monix.catnap.ConcurrentQueue

val queueExample: UIO[String] = (for {
  queue <- ConcurrentQueue[Task].bounded[String](10)
  _ <- queue.offer("Message")
  msg <- queue.poll
} yield msg).hideErrors
```

## Restarting on Error

`BIO` type represents a specification of a computation so it can be usually freely restarted if we wish to do so.

There are few retry combinators available but in general it is quite simple to write a custom recursive function.
For instance, retry with exponential backoff would look like as follows:

```scala mdoc:silent
def retryBackoff[E, A](source: BIO[E, A],
  maxRetries: Int, firstDelay: FiniteDuration): BIO[E, A] = {

  source.onErrorHandleWith {
    case ex: E =>
      if (maxRetries > 0)
        // Recursive call, it's OK as Monix is stack-safe
        retryBackoff(source, maxRetries - 1, firstDelay * 2)
          .delayExecution(firstDelay)
      else
        BIO.raiseError(ex)
  }
}
```

In more complicated cases it's worth to take a look at [cats-retry](https://github.com/cb372/cats-retry) library and/or use a stream (e.g. [fs2](https://github.com/functional-streams-for-scala/fs2), [Monix Observable](https://monix.io/docs/3x/reactive/observable.html)) instead of recursive functions.

## Reporting Uncaught Errors

Losing errors is unacceptable.
We can't always return them as a `BIO` result because sometimes the failure could happen concurrently and `BIO` could be already finished with a different value. 
In this case the error is reported with `Scheduler.reportFailure` which by default logs uncaught errors to `System.err`:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration._

// Ensures asynchronous execution, just to show
// that the action doesn't happen on the
// current thread
val task = BIO(2).delayExecution(1.second)

task.runAsync { r =>
  throw new IllegalStateException(r.toString)
}

// After 1 second, this will log the whole stack trace:
//=> java.lang.IllegalStateException: Right(2)
//=>    ...
//=> at monix.bio.BiCallback$$anon$3.tryApply(BiCallback.scala:359)
//=> at monix.bio.BiCallback$$anon$3.apply(BiCallback.scala:352)
//=> at monix.bio.BiCallback$$anon$3.onSuccess(BiCallback.scala:345)
//=> at monix.bio.internal.TaskRunLoop$.startFull(TaskRunLoop.scala:213)
//=> at monix.bio.internal.TaskRestartCallback.syncOnSuccess(TaskRestartCallback.scala:125)
//=> at monix.bio.internal.TaskRestartCallback.onSuccess(TaskRestartCallback.scala:83)
//=> ....
```

We can customize the behavior to use anything we'd like:

```scala mdoc:silent
import monix.bio.BIO
import monix.execution.Scheduler
import monix.execution.UncaughtExceptionReporter
import scala.concurrent.duration._

val reporter = UncaughtExceptionReporter { ex =>
  // our own fancy logger
  println("Customized printing of uncaught exception: " + ex)
}

implicit val s = Scheduler(Scheduler.global, reporter)

val task = BIO(2).delayExecution(1.second)

task.runAsync { r =>
  throw new IllegalStateException(r.toString)
}

// After 1 second:
//=> Customized printing of uncaught exception: java.lang.IllegalStateException: Right(2)
```

## Cats Instances

If you are [Cats](https://github.com/typelevel/cats) user then `BIO` provides [`ApplicativeError`](https://typelevel.org/cats/api/cats/ApplicativeError.html) and 
[`MonadError`](https://typelevel.org/cats/api/cats/MonadError.html) instances.

If you import `cats.syntax.monadError._`, `cats.syntax.applicativeError` or just `cats.syntax.all._` you will have access to all the methods provided by library.

The main gotcha is that anything requiring [`Sync`](https://typelevel.org/cats-effect/typeclasses/sync.html) and above will only work for `BIO[Throwable, A]`