---
id: overview
title: Overview
---

`BIO[E, A]` represents a specification for a possibly lazy or asynchronous computation. 
When executed, it will produce a successful value `A`, an error `E`, never terminate or complete with a terminal (untyped) error.

`BIO` handles concurrency, cancellation, resource safety, context propagation, error handling, and can suspend effects.
All of this makes it simple to write good, high-level code that solve problems related to any of these features in a safe and performant manner.

There are two type aliases:
- `type UIO[A] = BIO[Nothing, A]` which represents an effect that can only fail with terminal errors due to abnormal circumstances.
- `type Task[A] = BIO[Throwable, A]` - an effect that can fail with a `Throwable` and is analogous to `monix.eval.Task`.

[More about errors here.](error-handling)

`Monix BIO` builds upon [Monix Task](https://monix.io/api/3.2/monix/eval/Task.html) and enhances it with typed error capabilities.
If you are already familiar with `Task` - learning `BIO` is straightforward because the only difference is in
error handling - the rest of API is the same. 
In many cases, migration might be as simple as changing imports from `monix.eval.Task` to `monix.bio.Task`.

## Usage Example

```scala mdoc:compile-only
import monix.bio.{BIO, UIO}
import monix.execution.CancelableFuture
import scala.concurrent.duration._

// Needed to run BIO, it extends ExecutionContext
// so it can be used with scala.concurrent.Future as well
import monix.execution.Scheduler.Implicits.global

case class TypedError(i: Int)

// E = Nothing, the signature tells us it can't fail
val taskA: UIO[Int] = BIO.now(10)
  .delayExecution(2.seconds)
  // executes the finalizer on cancelation
  .doOnCancel(UIO(println("taskA has been cancelled")))

val taskB: BIO[TypedError, Int] = BIO.raiseError(TypedError(-1))
  .delayExecution(1.second)
  // executes the finalizer regardless of exit condition
  .guarantee(UIO(println("taskB has finished")))

// runs ta and tb in parallel, takes the result of the first
// one to complete and cancels the other effect
val t: BIO[TypedError, Int] = BIO.race(taskA, taskB).map {
  case Left(value) => value * 10 // ta has won
  case Right(value) => value * 20 // tb has won
}

// The error is handled and it is reflected in the signature
val handled: UIO[Int] = t.onErrorHandle { case TypedError(i) => i}

// Nothing happens until it runs, returns -1 after completion
val f: CancelableFuture[Int] = handled.runToFuture
    
// => taskB has finished
// => taskA has been cancelled
```

## Target audience

The target audience of `BIO` are users of `cats.effect.IO`, `monix.eval.Task`, and `Future` who tend to use `EitherT` a lot 
and would like to have a smoother experience, with better type inference, no syntax imports, and without constant wrapping and unwrapping.

If you are completely new to effect types, I'd recommend to start with `cats.effect.IO`, or `monix.eval.Task`, 
but if you really like the concept of typed errors then there is nothing wrong to go for `monix.bio.BIO`, or `zio.ZIO` from the start.

## Motivation

DISCLAIMER: The following part is very subjective opinion of the author.

There are already many effect types in Scala, i.e. [cats.effect.IO](https://github.com/typelevel/cats-effect), [Monix Task](https://github.com/monix/monix), and [ZIO](https://github.com/zio/zio).
It begs a question - why would anyone want another one?

It seems like built-in typed errors have warm reception, and the only other effect which has built-in typed errors is `ZIO`. 
Not everyone likes everything about `ZIO` and I feel like there are enough differences in Monix to make it a valuable alternative.
For instance, if you are a happy user of Typelevel libraries (http4s, fs2, doobie etc.) you might find that `BIO` has a nicer integration, and it is more consistent with the ecosystem.
[More differences here.](comparison)

### Monix Niche

The big difference between Monix and other effect libraries is its approach to impure code.
Both `cats.effect.IO` and `zio.ZIO` will push you to write a 100% purely functional codebase, except for isolated cases where low-level imperative code is needed for performance.
Monix is as good as other effects for pure FP, but the library goes the extra mile to provide support for users who would rather go for a hybrid approach, or are allergic to purely functional programming.
Here are few examples of Monix providing extra support for users of `Future`:
- The `monix-execution` module provides many utilities to use with `Future` even if you're not interested in `Task` at all.
- Monix uses a `Scheduler` which is also an `ExecutionContext` and can be used with `Future` directly. 
- `Local` works with both `Future` and Monix `Task/BIO`. 

In other words, Monix aims to help with impure code too (if you choose to do so), rather than treating it as a temporary nuisance which waits for a rewrite.

## Performance

At the time of writing (Q1 2020) performance is as good as `monix.eval.Task` in most benchmarks and `BIO` can outperform it for error handling operators if the error type is not `Throwable`.
It makes it the fastest effect type in today's Scala ecosystem.

Performance is a high priority, and we will greatly appreciate if you open an issue or write on [gitter](https://gitter.im/monix/monix) if you discover use cases where it performs badly.

You can find benchmarks and their results inside [benchmarks module](https://github.com/monix/monix-bio/tree/master/benchmarks).
