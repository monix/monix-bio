---
id: getting-started
title: Getting Started
---

This section briefly covers how to use the essential features of `IO` and should get you started quickly.
Refer to the rest of the documentation for a more in-depth description.

## Hello World

Let's start with a simple example.
Add the following line to your `build.sbt`:

```
libraryDependencies += "io.monix" %% "monix-bio" % "1.0.0"
```

Copy and paste it to your favorite editor and try it yourself:

```mdoc scala:silent
import monix.bio.IO
// Monix uses scheduler instead of the execution context
// from scala standard library, to learn more check
// https://monix.io/docs/3x/execution/scheduler.html
import monix.execution.Scheduler.Implicits.global

// Used for demonstration at the end
import scala.concurrent.Await
import scala.concurrent.duration._

object HelloWorld {

  def main(args: Array[String]): Unit = {
    // Unlike Future, IO is lazy and nothing gets executed at this point
    val bio = IO { println("Hello, World!"); "Hi" }
    
    // We can run it as a Scala's Future
    val scalaFuture: Future[String] = bio.runToFuture

    // Used only for demonstration, 
    // think three times before you use `Await` in production code!
    val result: String = Await.result(scalaFuture, 5.seconds)

    println(result) // Hi is printed
  }
}
```

## Composing Tasks

We can combine multiple `IO` with a variety of functions, the most basic ones being `flatMap` and `map`.

```scala mdoc:silent
import monix.bio.IO

val hello = IO("Hello ")
val world = IO("World!")

// Will return "Hello World!" after execution
val sayHello = hello
  .flatMap(h => world.map(w => h + w))
```

`fa.flatMap(a => f(a))` says "execute fa, and if it is successful, map the result to f(a) and execute it".

`fa.map(a => f(a))` says "execute fa, and if it is successful, map the result to f(a)".

The difference is that `flatMap` expects `A => IO[E, B]` function, while `map` requires `A => B` so it can return anything.
If we pass `A => IO[E, B]` to `map`, we will end up with `IO[E, IO[E, B]]` which needs to be "flattened" if we want to run the inner `IO`.

`IO` can also return with an error, in which case the computation will short circuit:

```scala mdoc:silent
import monix.bio.IO
import monix.execution.exceptions.DummyException

val fa = IO(println("A"))
val fb = IO.raiseError(DummyException("boom"))
val fc = IO(println("C"))

// Will print A, then throw DummyException after execution
val task = fa.flatMap(_ => fb).flatMap(_ => fc)
```

In the above example, `fc` is never executed. 
We can recover from errors using functions such as `onErrorHandleWith`.
[Check Error Handling section for an in-depth overview.](error-handling)

## Lazy evaluation

`IO` is just a description or a factory of an effect. 
It won't do anything until it is executed through `runXYZ` methods.
In more functional terms, it means that `IO` instances are *values*.
[Here's an explanation why is it cool.](https://www.reddit.com/r/scala/comments/8ygjcq/can_someone_explain_to_me_the_benefits_of_io/e2jfp9b/)

Programming with values helps with composition.
For instance, it is trivial to write a custom retry function:

```scala mdoc:silent:reset
import monix.bio.IO
import scala.concurrent.duration._

def retryBackoff[E, A](source: IO[E, A],
  maxRetries: Int, firstDelay: FiniteDuration): IO[E, A] = {

  source.onErrorHandleWith { ex =>
      if (maxRetries > 0)
        // Recursive call, it's OK as Monix is stack-safe
        retryBackoff(source, maxRetries - 1, firstDelay * 2)
          .delayExecution(firstDelay)
      else
        IO.raiseError(ex)
  }
}
```

If `IO` had an eager execution model, it wouldn't be simple to take an arbitrary `IO` and then enhance it with new behavior.
We would have to be extra careful not to pass an already running task, which is error-prone.

## Concurrency

Running two tasks concurrently, or in parallel (learn the difference in [concurrency section](concurrency)) is as simple as calling one of many concurrent operators:

```scala mdoc:silent:reset
import monix.bio.IO

val fa = IO(println("A"))
val fb = IO(println("B"))

// When task is executed, the code 
// will print "A" and "B" in non-deterministic order
val task = IO.parZip2(fa, fb)
```

## Cancelation

`IO` supports cancelation, which means that it will be stopped at the nearest possible opportunity.
Canceled tasks turn into non-terminating, which means they will never signal any result.
You can signal an error instead if you use `onCancelRaiseError`.

One of the use cases for cancelation is a timeout:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.exceptions.DummyException
import scala.concurrent.duration._

val longRunningTask = IO.sleep(200.millis)

longRunningTask.timeoutWith(100.millis, DummyException("Timed out!"))
```

## Combining high-level blocks

We can combine different pieces to create more complex behavior.
Let's develop a simple dynamic timeout:

```scala mdoc:silent:reset
import cats.effect.concurrent.Deferred
import monix.bio.{IO, Task}
import scala.concurrent.duration._

val longRunningTask = IO.sleep(200.millis)

val task =
  for {
    timeoutSignal <- Deferred[Task, Unit]
    timeoutCaller = IO.sleep(50.millis).flatMap(_ => timeoutSignal.complete(()))
    _ <- timeoutCaller.startAndForget
    _ <- IO.race(longRunningTask, timeoutSignal.get)
  } yield ()
```

We create [Deferred](https://typelevel.org/cats-effect/concurrency/deferred.html) which is an equivalent of [scala.concurrent.Promise](https://www.scala-lang.org/api/current/scala/concurrent/Promise.html).
It can be completed at most once with `complete()`, but it can be read many times with `get`. 
If the value is not yet available, `get` will *block asynchronously* (without blocking any underlying thread, it will just suspend `IO`, which is extremely cheap in comparison) until it is there.
`timeoutCaller` represents a task which signals a timeout from a different part of the program. 

We can use `startAndForget` to run it concurrently with subsequent operations.
This method starts the computation in the background and then returns without waiting for the result.

`IO.race` runs two tasks concurrently, and once any of them completes, the other one is canceled.
If we race `longRunningTask` against `timeoutSignal.get`, it will be canceled once the signal is sent.
