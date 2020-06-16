---
id: overview
title: Overview
---

`BIO[E, A]` represents a specification for a possibly lazy or asynchronous computation. 
When executed, it will produce a successful value `A`, an error `E`, never terminate or complete with a terminal (untyped) error.

`BIO` handles concurrency, cancellation, resource safety, context propagation, error handling, and can suspend effects.
All of it makes it a good tool to write a simple, high-level code to solve problems related to any of these features in a safe and performant manner.

There are two type aliases:
- `type UIO[A] = BIO[Nothing, A]` which represents an effect that can only fail with terminal errors due to abnormal circumstances.
- `type Task[A] = BIO[Throwable, A]` - an effect that can fail with a `Throwable` and is analogous to Monix `Task`.

[More about errors here.](error-handling)

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

# Motivation

DISCLAIMER: The following sections are very subjective and biased opinions of the author.

There are already many effect types in Scala, i.e. [cats.effect.IO](https://github.com/typelevel/cats-effect), [Monix Task](https://github.com/monix/monix), and [ZIO](https://github.com/zio/zio).
It begs a question - why would anyone want another one?

Well, it seems like built-in typed errors are in great demand, and the only other effect which has built-in typed errors is `ZIO`. 
Not everyone wants to use `ZIO` and I feel like there are enough differences in `Monix` to make it a valuable alternative.
The target audience of BIO are users of `IO`, `Task`, and `Future` who tend to use `EitherT` a lot and would like to have a smoother experience, with better type inference, no syntax imports, and without constant wrapping and unwrapping.

`Monix BIO` builds upon `Monix Task` and enhances it with typed error capabilities.
If you are already familiar with `Task` - learning `BIO` is straightforward because the only difference is in
error handling - the rest of API is the same. In many cases, migration might be as simple as changing imports from `monix.eval.Task` to `monix.bio.Task`.

To me, the big difference between Monix and other effect libraries is approach to impure code.
Both `IO` and `ZIO` will push you to write 100% purely functional codebase, except for isolated cases where low-level imperative code is needed for performance.
Monix favors purely functional programming too (the only impure method is `memoize`), but we also recognize and fully support users who would rather go for hybrid approach, or are allergic to pure FP.
I feel like it plays to Scala's unique strengths. 
Few examples of Monix providing extra support for `Future` users:
- `monix-execution` module provides many utilities to use with `Future` even if you're not interested in `Task` at all.
- Monix uses `Scheduler` which is also `ExecutionContext` and can be used with `Future` directly. 
- `Local` works with both `Future` and Monix `Task/BIO`. 

## Other effects

In this section, I will share my thoughts on `BIO` in comparison to other similar solutions at the time of writing (June 2020).
Keep in mind it can quickly become outdated. If you have any ideas for possible reasons for, or against Monix then please contribute them to this section.
It's important to me to limit bias to the minimum, and it's hard without an outsider's perspective!

### Cats-Effect IO

Why Cats-Effect IO:
- *Official Cats-Effect implementation.* Typelevel libraries use it in tests, so you are the least likely to encounter bugs.
Many maintainers also contribute to Cats-Effect (including us) so there is a big and diverse pool of potential contributors.
- *Minimal API.* `cats.effect.IO` is missing many operators in the API and is missing few features but if you don't need them (e.g. you are a user of Tagless Final) then the smaller surface can be an advantage.

Why Monix:
- *Richer API.* Monix has a bigger, more discoverable API (no syntax imports).
- *Fewer implicits.* Monix needs `Scheduler` only when executing the effect. Cats-Effect IO needs `ContextShift` (for any concurrent operator) or `Timer` (for sleeping) depending on the situation. It also depends on `Cats` syntax a lot which requires basic familiarity with this library and type class hierarchy.
- *Better Thread Management*. Cats IO is missing an operator like [executeOn](http://localhost:3000/monix-bio/api/monix/bio/BIO.html#executeOn(s:monix.execution.Scheduler,forceAsync:Boolean):monix.bio.BIO[E,A]) which
ensures that the task will execute on a specified thread pool. 
Cats IO can only guarantee it up to the first asynchronous boundary. 
Monix is also safer when using Callback-based builders like `BIO.async` because it can guarantee that 
the `BIO` will continue on the default `Scheduler` regardless of where callback is called.
- *Local.* Monix has a way to transparently propagate context over asynchronous boundaries which is handy for tracing without polluting the business logic.
- *Better integration with Scala's Future.* We can use the same `Scheduler` and tracing libraries for both.
- *Slightly higher performance*. In a lot of cases, the performance is the same but Monix provides a lot of hand-optimized methods that are derived in Cats IO. 
For instance, compare `monixSequence` and `catsSequence` in [benchmark results](https://github.com/monix/monix-bio/tree/master/benchmarks/results).

Choose for yourself:
- *Typed errors.* Although there is still `Monix Task` if you like the rest.

**Summary**

Monix is `cats.effect.IO` with more stuff. 
Considering the direction on CE3, `IO` gravitates closer towards the design of Monix.

### Scala's Future

Why Future:
- *It is in the standard library.* The safest bet in terms of stability and most Scala developers know it well.
- *Minimal API.* If it's good enough for your use cases, why bother to learn anything more?

Why Monix:
- *Easier concurrency.* Tons of extra operators and better composability due to laziness.
- *Referential transparency.* Take a look at [the classic post](https://www.reddit.com/r/scala/comments/8ygjcq/can_someone_explain_to_me_the_benefits_of_io/e2jfp9b/).
- *No ExecutionContext being dragged everywhere.* Monix equivalent is needed at the execution only which drastically reduces the temptation to `import ExecutionContext.Implicits.global`.
- *Resource Safety.* `Resource` and `Bracket` are very convenient tools to ensure your App does not leak any resources.
- *Cats-Effect ecosystem.*

Choose for yourself:
- *Cancellation.* Unlike `Future`, `BIO` can be cancelled. It sounds awesome, but it's no magic, and it's not possible to stop an effect immediately at an arbitrary point of time. 
Sometimes cancellation can be tricky, and many projects don't have any use case for it at all.
- *Typed errors.*

### Monix Task

The only difference between Monix `Task` and `BIO` are typed errors - API and performance is very consistent.

If you use `Task[Either[E, A]]` or `EitherT` a lot - you might find `BIO` much more pleasant to use.

If you don't, `Task` might be simpler and closer to Scala's `Future`. It is also better integrated with `Observable`.

### ZIO

Why ZIO:
- *Bigger community and ZIO-specific ecosystem.*
- *Better stack traces.* Killer feature. Hopefully it will come to Monix this year but until then it's a huge advantage for ZIO.
- *More flexible with relation to cancellation.* Monix has `uncancelable` operator but unlike ZIO, it doesn't have the reverse.
- *Less dependencies.* Monix depends on `Cats-Effect` which brings a ton of redundant syntax and instances from `Cats` which increases jar sizes and makes Monix slower to upgrade to new Scala versions.

Why Monix:
- *Better Cats-Effect integration.* Monix depends on Cats-Effect directly and its instances are available without any extra imports. 
We are also very consistent in the implementation, so it is rare to encounter a bug when using a Cats-Effect based library which is exclusive to Monix.
- *Better integration with Scala's Future.* We can use the same `Scheduler` and tracing libraries for both.
- *Performance.* Just look at [benchmark results](https://github.com/monix/monix-bio/tree/master/benchmarks/results).
- *Stability.* Monix is definitely more mature library. It also does less which helps in keeping it stable. 
This point is mostly about `Monix Task` because `BIO` is a new thing, but it shares like 90% of `Task` so I'd argue the point is still relevant.

Choose for yourself:
- *ZIO Environment.* ZIO adds extra type parameter (`R`) for dependency management. 
Monix favors classic approach (passing dependencies as parameters, any DI library, or `F[_]`).
If you don't like `Zlayer`, you can pretend `R` doesn't exist, but you will encounter it a lot in type signatures and all `ZIO` libraries use it.
- *Fiber management.* Monix treats `Fiber` as a low level construct and recommends staying away from it unless you know what you're doing. 
`ZIO` embraces the concept and adds fiber supervision, structured concurrency etc. Cool feature, but it's an extra thing to learn about, and I don't see many use cases for it.
Usually it's possible to do everything with other concurrency combinators.
- *Framework experience vs pluggable library.* ZIO is more opinionated and pushes you to use `ZIO`-everything, otherwise you might have an underwhelming experience.
Monix wants to naturally interop with both FP and non-FP ecosystem but it might have less "batteries" included. 
ZIO can be better at forcing you to follow their pattern of programming, while Monix can play nicer with your team's individual preferences and mixed (e.g. using both Monix and Future) codebases.

**Summary**

ZIO has more contributors, more features which you may or may not use, is more opinionated and develops a new ecosystem of libraries which is tailored to ZIO needs.
Monix is more stable, faster and puts more focus on integrating with existing ecosystems, rather than trying to create a new one.

# Performance

At the time of writing (Q1 2020) performance is as good as `monix.eval.Task` in most benchmarks and `BIO` can outperform it for error handling operators if the error type is not `Throwable`.
It makes it the fastest effect type in today's Scala ecosystem.

Performance is a high priority for us, and we will greatly appreciate if you open an issue or write on [gitter](https://gitter.im/monix/monix) if you discover use cases where it performs badly.

You can find benchmarks and their results inside [benchmarks module](https://github.com/monix/monix-bio/tree/master/benchmarks).