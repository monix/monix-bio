---
id: comparison
title: Other Effects
---

The following section shares the author's opinion about `monix.bio.IO` in comparison to other similar solutions at the time of writing.

Keep in mind it can quickly become outdated. If you have any ideas for possible reasons for, or against Monix then please contribute them to this section.
It's important to me to limit bias to the minimum, and it's hard without an outsider's perspective!

Last Update: September 2020

## Cats-Effect IO

Why Cats-Effect IO:
- *Official Cats-Effect implementation.* Typelevel libraries use it in tests, so you are the least likely to encounter bugs.
Many maintainers of related libraries also contribute to Cats-Effect (including Monix) so there is a big and diverse pool of potential contributors.
- *Minimal API.* `cats.effect.IO` is missing many operators in the API as well as a number of features.
However, if you don't need them (e.g. you are a user of Tagless Final), then the smaller API surface can be an advantage.
- *Stack traces.* Cats IO added better stack traces in [2.2.0 release](https://github.com/typelevel/cats-effect/releases/tag/v2.2.0) which are still missing in Monix.

Why Monix:
- *Richer API.* Monix has a larger, more discoverable API (no syntax imports).
- *Fewer implicits.* Monix only needs a `Scheduler` when executing the effect. 
Cats-Effect IO needs a `ContextShift` (for any concurrent operator) or a `Timer` (for sleeping) depending on the situation. 
It also heavily depends on `Cats` syntax which requires basic familiarity with the library and type class hierarchy.
- *Better Thread Management*. Cats IO is missing an operator like [executeOn](http://bio.monix.io/api/monix/bio/IO.html#executeOn(s:monix.execution.Scheduler,forceAsync:Boolean):monix.bio.IO[E,A]) 
which ensures that the task will execute on a specified thread pool. 
Cats IO can only guarantee it up to the first asynchronous boundary. 
Monix is also safer when using Callback-based builders like `IO.async` because it can guarantee that 
the `IO` will continue on the main `Scheduler` regardless of where the callback is called.
- *Local.* Monix has a way to transparently propagate context over asynchronous boundaries which is handy for tracing without polluting the business logic.
- *Better integration with Scala's Future.* We can use the same `Scheduler` and tracing libraries for both. 
This is important when the code base uses libraries from other ecosystems, such as Akka HTTP.
- *Slightly higher performance*. In a lot of cases, performance is the same, however Monix provides a lot of hand-optimized methods that are derived in Cats IO, and thus, slower.
For instance, compare `monixSequence` and `catsSequence` in [benchmark results](https://github.com/monix/monix-bio/tree/master/benchmarks/results).

Depends on your preference:
- *Typed errors.* 
They can bring extra complexity because of two error channels in the implementation and there are other ways to handle errors
that can be as good, or better depending on your preference. 
For example: Encoding errors and successes in the same ADT, using `EitherT` locally, keeping a single error channel with global error handler, and Cats MTL.
Although there is still `Monix Task` if you like the rest of the differences.
- *Final Tagless.* Many Cats-Effect examples are biased towards its usage so if you prefer concrete effects, it is a noise.

### Summary

Until recently, Monix was `cats.effect.IO` with more stuff, better `Future` interop and optional typed errors.

Since [2.2.0 release](https://github.com/typelevel/cats-effect/releases/tag/v2.2.0), Cats IO includes better stack traces 
that might take few months before coming to Monix.

Also note that many features that are missing in `cats.effect.IO` will come in CE3 release and the design of both
libraries will most likely stay very consistent. 

I expect that the main differences will come from the approach to `Future` support and mixed codebases, 
and an option for the extra type parameter.

## Scala's Future

Why Future:
- *It is in the standard library.* The safest bet in terms of stability and most Scala developers know it well.
- *Minimal API.* If it's good enough for your use cases, why bother to learn anything more?

Why Monix:
- *Easier concurrency.* Tons of extra operators and better composability due to laziness. Parallelism is explicit.
- *Referential transparency.* Take a look at [this classic post](https://www.reddit.com/r/scala/comments/8ygjcq/can_someone_explain_to_me_the_benefits_of_io/e2jfp9b/).
- *No ExecutionContext being dragged everywhere.* 
Monix only requires its `Scheduler` (the equivalent of `ExecutionContext`) at the point of execution which drastically reduces the temptation to `import ExecutionContext.Implicits.global`.
- *Resource Safety.* `Resource` and `Bracket` are convenient tools that ensure your application doesn't leak any resources.
- *Cats-Effect ecosystem.*

Depends on your preference:
- *Cancellation.* Unlike `Future`, `IO` can be cancelled. 
It sounds awesome, but it's no magic, and it's not possible to stop an effect immediately at an arbitrary point in time. 
Sometimes cancellation can be tricky, and many projects don't have any use case for it at all.
- *Typed errors.*

## Monix Task

The only difference between Monix `Task` and `IO` are typed errors - Both the API and performance are very consistent.

If you use `Task[Either[E, A]]` or `EitherT` a lot - you might find `IO` far more pleasant to use.

If you don't, then `Task` might be simpler as well as closer to Scala's `Future` if that's what you're coming from. 
It also has better integration with `Observable`.

## ZIO

Why ZIO:
- *Bigger community and ZIO-specific ecosystem.*
- *Better stack traces.* 
Killer feature. Hopefully it will come to Monix this year but until then it's a huge advantage for ZIO.
- *More flexible with relation to cancellation.* 
Monix has an `uncancelable` operator but unlike ZIO, it doesn't have the reverse. 
ZIO has also implemented structured concurrency for fibers.
- *Fewer dependencies.* 
Monix depends on `Cats-Effect` which brings a ton of redundant syntax and instances from `Cats` which increases jar sizes and makes Monix slower to upgrade to new Scala versions.
- *More innovation.* 
Over the last few years, ZIO brought many great ideas that were followed by Monix and Cats.

Why Monix:
- *Better Cats-Effect integration.* 
Monix depends on Cats-Effect directly and its instances are available without any extra imports. 
We are also very consistent in the implementation, so it is rare to encounter a bug when using a Cats-Effect based library which is exclusive to Monix.
- *Better integration with Scala's Future.* We can use the same `Scheduler` and tracing libraries for both. 
This is important when the code base uses libraries from other ecosystems, such as Akka HTTP.
- *Performance.* [See benchmark results](https://github.com/monix/monix-bio/tree/master/benchmarks/results).
- *Stability.* Monix is definitely more mature library. It also does less which helps in keeping it stable. 
This point is mostly about core Monix because `BIO` is a new thing, but it shares like 90% of `monix.eval.Task` so I'd argue the point is still relevant.

Depends on your preference:
- *ZIO Environment.* ZIO adds an extra type parameter (`R`) for dependency management. 
Monix favors the classic approach (passing dependencies as parameters, any DI library, or `F[_]`).
You don't have to use `R` but it will come up in the signatures and `ZIO` libraries use it so you will be programming "against the tide".
- *Framework experience vs pluggable library.* 
ZIO is more opinionated and pushes you to use `ZIO`-everything, otherwise you might have an underwhelming experience.
Monix wants to naturally interop with both FP and non-FP ecosystem but it might have less "batteries" included. 
ZIO can be better at forcing you to follow a specific pattern of programming, 
while Monix can play nicer with your team's individual preferences and mixed (e.g. using both Monix and Future) codebases.

### Summary

ZIO has more contributors, more features (which you may or may not ever use), is more opinionated, 
and develops a new ecosystem of libraries which are tailored to ZIO needs.
Monix is more stable, faster and puts more focus on integrating with existing ecosystems, rather than trying to create a new one.
