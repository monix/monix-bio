---
id: creating
title: Creating IO
---

As always, a full and up to date list of operators is available in the API or the companion object.

## Simple builders

### IO.now

`IO.now` lifts an already known value in the `IO` context, the equivalent of `Future.successful`.
Do not use it with any side effects, because they will be evaluated immediately and just once:

```scala mdoc:silent:reset
import monix.bio.IO

val task = IO.now { println("Effect"); "Hello!" }
//=> Effect
```

### IO.raiseError

`IO.raiseError` lifts a typed error to the context of `IO`:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val error: IO[DummyException, Nothing] = IO.raiseError(DummyException("boom"))

error.runAsync(result => println(result))
//=> Left(Cause.Error(DummyException("boom")))
```

### IO.terminate

`IO.raiseError` lifts a terminal error to the context of `IO`:

```scala mdoc:silent:reset
import monix.bio.{IO, UIO}
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val error: UIO[Nothing] = IO.terminate(DummyException("boom"))

error.runAsync(result => println(result))
//=> Left(Cause.Termination(DummyException("boom")))
```

### IO.eval / IO.apply

`IO.eval` is the equivalent of `Function0`, taking a function that will always be evaluated on running, possibly on the same thread (depending on the chosen execution model):

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global

val task: IO[Throwable, String] = IO.eval { println("Effect"); "Hello!" }

task.runToFuture.foreach(println)
//=> Effect
//=> Hello!

// The evaluation (and thus all contained side effects)
// gets triggered on each runToFuture:
task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
```

`IO.eval` catches errors that are thrown in the passed function:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.exceptions.DummyException
import monix.execution.Scheduler.Implicits.global

val task = IO.eval { println("Effect"); throw DummyException("Goodbye")}

task.runAsync(result => println(result))
//=> Effect
//=> Left(Cause.Error(DummyException("Goodbye")))

// The evaluation (and thus all contained side effects)
// gets triggered on each runAsync:
task.runAsync(result => println(result))
//=> Effect
//=> Left(Cause.Error(DummyException("Goodbye")))
```

### IO.evalTotal / UIO.apply

`IO.evalTotal` is similar to `eval` because it also suspends side effects, but it doesn't expect any errors to be thrown, so the error type is `Nothing`.
If there are any, they are considered terminal errors.

```scala mdoc:silent:reset
import monix.bio.{IO, UIO}
import monix.execution.Scheduler.Implicits.global

val task: UIO[String] = IO.evalTotal { println("Effect"); "Hello!" }

task.runToFuture.foreach(println)
//=> Effect
//=> Hello!

// The evaluation (and thus all contained side effects)
// gets triggered on each runToFuture:
task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
```

### IO.evalOnce

`IO.evalOnce` is the equivalent of a `lazy val`, a type that cannot be precisely expressed in Scala. 
The `evalOnce` builder does memoization on the first run, such that the result of the evaluation will be available for subsequent runs. 
It also has guaranteed idempotency and thread-safety:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global

val task = IO.evalOnce { println("Effect"); "Hello!" }

task.runToFuture.foreach(println)
//=> Effect
//=> Hello!

// Result was memoized on the first run!
task.runToFuture.foreach(println)
//=> Hello!
```

NOTE: this operation is effectively `IO.eval(f).memoize`.

### IO.never

`Task.never` returns a Task instance that never completes:

```scala mdoc:silent:reset
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.TimeoutException

// A Task instance that never completes
val never = Task.never[Int]

val timedOut = never.timeoutTo(3.seconds,
  Task.raiseError(new TimeoutException))

timedOut.runAsync(r => println(r))
// After 3 seconds:
// => Left(Cause.Error(java.util.concurrent.TimeoutException))
```

This instance is shared so that it can relieve some stress from the garbage collector.

## Asynchronous builders

### IO.evalAsync

By default, `IO` prefers to execute things on the current thread.

`IO.evalAsync` will evaluate the effect asynchronously; consider it an optimized version of `IO.eval.executeAsync`.

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global

IO.eval(println(s"${Thread.currentThread().getName}: Executing eval")).runSyncUnsafe()
// => main: Executing eval

IO.evalAsync(println(s"${Thread.currentThread().getName}: Executing evalAsync")).runSyncUnsafe()
// => scala-execution-context-global-14: Executing evalAsync
```

### IO.create

`IO.create` aggregates a handful of methods that create a `IO` from a callback.

For example, let's create a utility that evaluates expressions with a given delay:

```scala mdoc:silent:reset
import monix.bio.IO
import scala.util.Try
import concurrent.duration._

def evalDelayed[A](delay: FiniteDuration)
  (f: => A): IO[Throwable, A] = {

  // On execution, we have the scheduler and
  // the callback injected ;-)
  IO.create { (scheduler, callback) =>
    val cancelable =
      scheduler.scheduleOnce(delay) {
        callback(Try(f))
      }

    // We must return something that can
    // cancel the async computation
    cancelable
  }
}
```

`IO.create` supports different cancelation tokens, such as:
- `Unit` for non-cancelable tasks
- `cats.effect.IO`
- `monix.bio.IO`
- `monix.execution.Cancelable`
- And others.

Some notes:
- Tasks created with this builder are guaranteed to execute asynchronously
- Even if the callback is called on a different thread pool, the resulting task will continue on the default Scheduler.
- The [Scheduler](https://monix.io/docs/3x/execution/scheduler.html) gets injected, and with it, we can schedule things for async execution, we can delay, etc.
- But as said, this callback will already execute asynchronously, so you don’t need to explicitly schedule things to run on the provided Scheduler unless you really need to do it.
- The [Callback](https://monix.io/docs/3x/execution/callback.html) gets injected on execution, and that callback has a contract. In particular, you need to execute `onSuccess`, `onError`, or `onTermination` or apply only once. The implementation does a reasonably good job to protect against contract violations, but if you do call it multiple times, then you’re doing it risking undefined and nondeterministic behavior.
- It’s OK to return a `Cancelable.empty` in case the executed process really can’t be canceled in time. Still, you should strive to produce a cancelable that does cancel your execution, if possible.

### IO.fromFuture

`IO.fromFuture` can convert any Scala Future instance into a `IO`:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Future

val future = Future { println("Effect"); "Hello!" }
val task = IO.fromFuture(future)
//=> Effect

task.runToFuture.foreach(println)
//=> Hello!
task.runToFuture.foreach(println)
//=> Hello!
```

Note that `fromFuture` takes a strict argument, and that may not be what you want. 
When you receive a Future like this, whatever process that’s supposed to complete has probably started already.
You might want a factory of Future to be able to suspend its evaluation and reuse it.
The design of `IO` is to have fine-grained control over the evaluation model, so in case you want a factory, 
you need to either combine it with `IO.defer` or use `IO.deferFuture`:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Future

val task = IO.defer {
  val future = Future { println("Effect"); "Hello!" }
  IO.fromFuture(future)
}

task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
```
Or use the equivalent:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Future

val task = IO.deferFuture {
  Future { println("Effect"); "Hello!" }
}

task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
task.runToFuture.foreach(println)
//=> Effect
//=> Hello!
```

### IO.deferFutureAction

`IO.deferFutureAction` wraps calls that generate Future results into Task, 
provided a callback with an injected Scheduler to act as the necessary ExecutionContext.

This builder helps with wrapping Future-enabled APIs that need an implicit ExecutionContext to work. 
Consider this example:

```scala mdoc:silent:reset
import scala.concurrent.{ExecutionContext, Future}

def sumFuture(list: Seq[Int])(implicit ec: ExecutionContext): Future[Int] =
  Future(list.sum)
```

We’d like to wrap this function into one that returns a lazy Task that evaluates this sum every time it is called because that’s how tasks work best. However, to invoke this function, an ExecutionContext is needed:

```scala mdoc:silent
import monix.bio.Task
import scala.concurrent.ExecutionContext

def sumTask(list: Seq[Int])(implicit ec: ExecutionContext): Task[Int] =
  Task.deferFuture(sumFuture(list))
```

But this is not only superfluous but against the best practices of using `IO`. 
The difference is that Task takes a Scheduler (inheriting from ExecutionContext) only when the run gets called, but we don’t need it just for building a Task reference.
`IO` is aware that `Scheduler` will be supplied during execution, and it can access it any time. 
With `deferFutureAction` or `deferAction` we get to have an injected Scheduler in the passed callback:

```scala mdoc:silent
import monix.bio.Task

def sumTask(list: Seq[Int]): Task[Int] =
  Task.deferFutureAction { implicit scheduler =>
    sumFuture(list)
  }
```

Voilà! No more implicit ExecutionContext passed around.
