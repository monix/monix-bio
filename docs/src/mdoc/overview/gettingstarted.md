---
layout: docs
title:  "Getting Started"
position: 2
---

# Getting started with sbt

Add the following line to your `build.sbt`
```
libraryDependencies += "io.monix" %% "monix-bio" % "0.1.0"
```

## Hello world
Let's start with a simple example. Copy and paste it to your favorite
editor and try it yourself.
```scala
import monix.bio.BIO
// Monix uses scheduler instead of execution context
// from scala standard library, to learn more check
// https://monix.io/docs/3x/execution/scheduler.html
import monix.execution.Scheduler.Implicits.global

// used for demonstration at the end
import scala.concurrent.Await
import scala.concurrent.duration._

object HelloWorld {

  def main(args: Array[String]): Unit = {
    // BIO, similarly to Monix's Task is lazy
    // nothing gets executed at this point
    val bio = BIO { "Hello" + " world!" }

    // we can convert it to execute our bio
    // the execution starts here
    val normalScalaFuture = bio.runToFuture

    // used only for demonstration, never block
    // your threads in production code
    val result = Await.result(normalScalaFuture, 5.seconds)
    println(result)
  }
}
```

## Error channels
At this point `BIO` is not much different from `Task` from [Monix library](https://monix.io/docs/3x/eval/task.html).
What makes `BIO` different is the bifunctor concept. 
When you take a look at, how is it implemented `sealed abstract class BIO[+E, +A]` you will
see that `BIO` takes two type parameters:
* `E` - our expected error type, indicates what kind of errors are expected when
`BIO` is evaluated
* `A` - it is the result type, it shows what type will be returned
when the computation finishes.

There are two convenience type aliases:
* `type Task[+A] = BIO[Throwable, A]` - represents a `BIO` which uses `Throwable` as the error chanel
* `type UIO[+A] = BIO[Nothing, A]` - represents `BIO` which is not expected to fail

`BIO` has two error channels, one is `E` from the type signature, which indicates an expected error, and the second
one is `Throwable` which is an unexpected error channel. To fail with an unexpected error you can use `BIO.terminate`,
which accepts a `Throwable` and fails the `BIO`. Unexpected error skips
all expected error handlers, except for `BIO.redeemCause` and `BIO.redeemCauseWith`. Even though `UIO` indicates
no expected errors, every `BIO` instance can fail with an unexpected error. 


Similarly to `Either`, in `BIO` you can transform both: error
and value channel. Please take a look at the example below:

```scala
import monix.bio.{BIO, UIO}

import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Await
import scala.concurrent.duration._

object Bifunctor {
  case class Error(description: String)
  case class OtherError(description: String)

  def main(args: Array[String]): Unit = {

    // BIO.now is a builder from already evaluated value, don't put there anything that can throw
    // its similar to Future.successful
    val successfulBIO: BIO[Error, String] = BIO.now("Hello world!")

    // You can transform the result value with map and flatMap
    val stringLength: BIO[Error, Int] = successfulBIO.map(_.length)

    // BIO.raiseError creates new instance from already evaluated error, again don't put there anything that can throw
    // its similar to Future.failed
    val failedBIO: BIO[Error, Int] = BIO.raiseError(new Error("Error!"))

    val remappedError: BIO[OtherError, Unit] = failedBIO
    // you can map on error, just like on result value
      .mapError(err => OtherError(err.description))
    // but if BIO is failed, normal maps wont be executed
    // this is the same as with running `mapError` on successful BIO
      .map(_ => println("Im never executed!"))

    // sometimes we are not able to handle all the possible cases
    // we can terminate our BIO with a fatal, non expected exception
    val unexpectedError: UIO[Unit] = BIO.terminate(new Exception("unexpected error"))

    // if we want to run our BIO we need to convert it somehow - we cannot `throw` classes,
    // which are not subtypes of throwable. Future, form scala standard library uses Throwable as the error channel,
    // so we either need to handle all of our errors or convert them to Throwable.
    // We can use attempt which converts our BIO[E, A] into UIO[Either[E, A]] (which means it handles all BIO errors)
    val attemptStringLength = stringLength.attempt
    println(Await.result(attemptStringLength.runToFuture, 1.second))

    println(Await.result(remappedError.attempt.runToFuture, 1.second))

    // as opposed to the example above, the exception is thrown here
    // unexpectedError is of type UIO, which indicated that we are not expecting any error,
    // but terminate method is used to fail with *unexpected* error
    println(Await.result(unexpectedError.attempt.runToFuture, 1.second)) 
  }

}
```


 
