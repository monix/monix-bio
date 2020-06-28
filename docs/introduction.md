---
id: introduction
title: Introduction
---

`Task[E, A]` represents a specification for a possibly lazy or asynchronous computation. 
When executed, it will produce a successful value `A`, an error `E`, never terminate or complete with a terminal (untyped) error.

`Task` handles concurrency, cancellation, resource safety, context propagation, error handling, and can suspend effects.
All of this makes it simple to write good, high-level code that solves problems related to any of these features in a safe and performant manner.

There are following type aliases:
- `Task.Safe[A] = Task[Nothing, A]` represents an effect that handled all errors that can be handled safely, and can only fail with terminal errors due to abnormal circumstances.
It has a second alias - `UIO[A]` (from Unexceptional IO) with a companion object for convenience.
- `Task.Unsafe[A] = Task[Throwable, A]` - an effect that can fail with a `Throwable` and is analogous to `monix.eval.Task`.

`Monix BIO` builds upon [Monix Task](https://monix.io/api/3.2/monix/eval/Task.html) and enhances it with typed error capabilities.
If you are already familiar with `Task[A]` - learning `Task[E, A]` is straightforward because the only difference is in
error handling - the rest of the API is the same. 
In many cases, migration might be as simple as changing imports from `monix.eval.Task` to `monix.bio.Task`, or `monix.bio.Task.Unsafe`.

[Go here if you're looking to get started as quickly as possible.](getting-started)

## Usage Example

```scala mdoc:silent
import monix.bio.{Task, UIO}
import monix.execution.CancelableFuture
import scala.concurrent.duration._

// Needed to run Task, it extends ExecutionContext
// so it can be used with scala.concurrent.Future as well
import monix.execution.Scheduler.Implicits.global

case class TypedError(i: Int)

// E = Nothing, the signature tells us it can't fail
val taskA: UIO[Int] = Task.now(10)
  .delayExecution(2.seconds)
  // executes the finalizer on cancelation
  .doOnCancel(UIO(println("taskA has been cancelled")))

val taskB: Task[TypedError, Int] = Task.raiseError(TypedError(-1))
  .delayExecution(1.second)
  // executes the finalizer regardless of exit condition
  .guarantee(UIO(println("taskB has finished")))

// runs ta and tb in parallel, takes the result of the first
// one to complete and cancels the other effect
val t: Task[TypedError, Int] = Task.race(taskA, taskB).map {
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

The target audience of `Task[E, A]` are users of `cats.effect.IO`, `monix.eval.Task`, and `Future` who tend to use `EitherT` a lot 
and would like to have a smoother experience, with better type inference, no syntax imports, and without constant wrapping and unwrapping.

If you are entirely new to effect types, I'd recommend starting with `cats.effect.IO`, or `monix.eval.Task`, 
but if you really like the concept of typed errors, then there is nothing wrong with going for `monix.bio.Task`, or `zio.ZIO` from the start.

## Motivation

DISCLAIMER: The following part is a very subjective opinion of the author.

There are already many effect types in Scala, i.e. [cats.effect.IO](https://github.com/typelevel/cats-effect), [Monix Task](https://github.com/monix/monix), and [ZIO](https://github.com/zio/zio).
It begs the question - why would anyone want another one?

It seems like built-in typed errors have warm reception, and the only other effect which has built-in typed errors is `ZIO`. 
Not everyone likes everything about `ZIO`, and I feel like there are enough differences in Monix to make it a valuable alternative.
For instance, if you are a happy user of Typelevel libraries (http4s, fs2, doobie, etc.), you might find that `Task` has a nicer integration, and it is more consistent with the ecosystem.
[More differences here.](comparison)

### Monix Niche

The big difference between Monix and other effect libraries is its approach to impure code.
Both `cats.effect.IO` and `zio.ZIO` will push you to write a 100% purely functional codebase, except for isolated cases where low-level imperative code is needed for performance.
Monix is as good as other effects for pure FP, but the library goes the extra mile to provide support for users who prefer to go for a hybrid approach, or are allergic to purely functional programming.
Here are a few examples of Monix providing extra support for users of `Future`:
- The `monix-execution` module offers many utilities for use with `Future` even if you're not interested in `Task` at all.
- Monix uses a `Scheduler`, which is also an `ExecutionContext` and can be used with `Future` directly. 
- `Local` works with both `Future` and Monix `Task`s. 

In other words, Monix aims to help with impure code too (if you choose to do so), rather than treating it as a temporary nuisance which waits for a rewrite.

## Performance

At the time of writing (Q2 2020), performance is as good as `monix.eval.Task` in most benchmarks and `monix-bio` can outperform it for error handling operators if the error type is not `Throwable`.
It makes it the fastest effect type in today's Scala ecosystem.

Performance is a high priority, and we will greatly appreciate it if you open an issue or write on [gitter](https://gitter.im/monix/monix) if you discover use cases where it performs poorly.

You can find benchmarks and their results inside [benchmarks module](https://github.com/monix/monix-bio/tree/master/benchmarks).
