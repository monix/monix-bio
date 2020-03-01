# Monix-BIO

[![Join the chat at https://gitter.im/monix/monix-bio](https://badges.gitter.im/monix/monix-bio.svg)](https://gitter.im/monix/monix-bio?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Experimental alternative to [monix.eval.Task](https://monix.io/api/3.1/monix/eval/Task.html) from [Monix](https://github.com/monix/monix) which uses a second type parameter to represent recoverable errors.

WORK IN PROGRESS.

[Documentation Website](https://monix.github.io/monix-bio/)

## Getting Started

There is a SNAPSHOT version (compatible with Monix 3.x and Cats and Cats-Effect 2.x) available:

```scala
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "io.monix" %% "monix-bio" % "0.0.2-SNAPSHOT"
```

I will really appreciate feedback, bugs and complaints about API if you play with it. Just please do not use it in production yet!

If you'd like to join and help then look for issues tagged with [good first issue](https://github.com/monix/monix-bio/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22).
I'm happy to guide anyone interested in contributing. Just let me know on specific issue or write to me on [gitter](https://gitter.im/monix/monix-bio).

## Short introduction

`BIO[E, A]` represents a specification for a possibly lazy or asynchronous computation, which when executed will produce
a successful value `A`, an error `E`, never terminate or complete with a terminal (untyped) error.

It composes very well and can handle many use cases such as cancellation, resource safety, context propagation, error handling or parallelism.

There are two type aliases:
- `type UIO[A] = BIO[Nothing, A]` which represents an effect which never fails.
- `type Task[A] = BIO[Throwable, A]` - an effect that can fail with a `Throwable` and is analogous to Monix `Task`.

Usage example:

```scala 
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

// Nothing happens until it runs, returns Right(-1) after completion
val f: CancelableFuture[Either[Nothing, Int]] = handled.runToFuture
    
// => taskB has finished
// => taskA has been cancelled
```

I will elaborate much more once the project is ready for general use.

## Stability

Currently there are no backwards compatibility guarantees. It will take a bit of time for everything to stabilize so I strongly advise against using it in serious projects at the moment. :) On the other hand, please play with it, give feedback and report bugs! It will be a tremendous help in getting this production-ready faster.

One of the main goals is to stay as consistent with `monix.eval.Task` as possible so the core functionality will not see much changes but it is likely that some type signatures change, e.g. to return `UIO[A]` where possible. 

Error handling operators and error reporting are actively worked on and they will most likely see breaking changes.

Most of the internals didn't see significant changes when compared to `monix.eval.Task` so this implementation can greatly benefit from maturity of its parent but the project has just started and the changes weren't thoroughly tested yet.

## Motivation

## Brief comparison to other solutions

## Performance

DISCLAIMER: Benchmarks do not necessarily reflect the performance in real conditions so take the results with a grain with salt. 
It is possible that in your specific use different effects will perform differently than the benchmark would suggest.
It is best to always measure yourself and for your specific use case if you are concerned about the performance.

At the time of writing (10 Dec 2019) the performance is excellent and we did not observe any regressions when compared to `monix.eval.Task`.
Please open an issue or write on gitter (https://gitter.im/monix/monix) in case you measure completely different results or find a benchmark where `monix-bio` does not perform well.

The benchmarks are inside [benchmarks module](benchmarks).

```
[info] Benchmark                                        (depth)   Mode  Cnt      Score     Error  Units
[info] IODeepAttemptBenchmark.catsDeepAttempt              1000  thrpt   30  20867.936 ± 113.416  ops/s
[info] IODeepAttemptBenchmark.futureDeepAttempt            1000  thrpt   30  10617.519 ± 266.933  ops/s
[info] IODeepAttemptBenchmark.monixBioDeepAttempt          1000  thrpt   30  21098.927 ±  76.705  ops/s
[info] IODeepAttemptBenchmark.monixBioDeepAttemptTyped     1000  thrpt   30  49799.909 ± 900.961  ops/s
[info] IODeepAttemptBenchmark.monixDeepAttempt             1000  thrpt   30  20858.228 ± 225.967  ops/s
[info] IODeepAttemptBenchmark.zioDeepAttempt               1000  thrpt   30  17182.169 ± 256.507  ops/s
[info] IODeepAttemptBenchmark.zioDeepAttemptTyped          1000  thrpt   30  33391.597 ± 227.462  ops/s

[info] Benchmark                                              (count)  (depth)  (size)   Mode  Cnt       Score      Error  Units 
[info] IOShallowAttemptBenchmark.catsShallowAttempt               N/A     1000     N/A  thrpt   30    2082.374 ±   56.612  ops/s 
[info] IOShallowAttemptBenchmark.futureShallowAttempt             N/A     1000     N/A  thrpt   30   13673.549 ±  254.908  ops/s 
[info] IOShallowAttemptBenchmark.monixBioShallowAttempt           N/A     1000     N/A  thrpt   30    2166.767 ±   70.277  ops/s 
[info] IOShallowAttemptBenchmark.monixBioShallowAttemptTyped      N/A     1000     N/A  thrpt   30   45237.518 ±  201.742  ops/s 
[info] IOShallowAttemptBenchmark.monixShallowAttempt              N/A     1000     N/A  thrpt   30    2237.589 ±   66.912  ops/s 
[info] IOShallowAttemptBenchmark.zioShallowAttempt                N/A     1000     N/A  thrpt   30    1655.358 ±   41.271  ops/s 
[info] IOShallowAttemptBenchmark.zioShallowAttemptTyped           N/A     1000     N/A  thrpt   30   15874.855 ±   55.476  ops/s

[info] IODeepFlatMapBenchmark.catsDeepFlatMap                     N/A       20     N/A  thrpt   30    1369.812 ±   13.374  ops/s 
[info] IODeepFlatMapBenchmark.futureDeepFlatMap                   N/A       20     N/A  thrpt   30     347.436 ±    6.665  ops/s 
[info] IODeepFlatMapBenchmark.monixBioDeepFlatMap                 N/A       20     N/A  thrpt   30    1933.068 ±   13.436  ops/s 
[info] IODeepFlatMapBenchmark.monixDeepFlatMap                    N/A       20     N/A  thrpt   30    1937.212 ±   13.173  ops/s 
[info] IODeepFlatMapBenchmark.zioDeepFlatMap                      N/A       20     N/A  thrpt   30    1159.547 ±    6.212  ops/s 

[info] IODeepLeftBindBenchmark.catsDeepLeftBind                   N/A    10000     N/A  thrpt   30    4340.087 ±   28.919  ops/s 
[info] IODeepLeftBindBenchmark.monixBioDeepLeftBind               N/A    10000     N/A  thrpt   30    7392.089 ±   35.754  ops/s 
[info] IODeepLeftBindBenchmark.monixDeepLeftBind                  N/A    10000     N/A  thrpt   30    7382.817 ±   25.495  ops/s 
[info] IODeepLeftBindBenchmark.zioDeepLeftBind                    N/A    10000     N/A  thrpt   30    2875.241 ±   47.077  ops/s 

[info] IONarrowFlatMapBenchmark.catsNarrowFlatMap                 N/A      N/A   10000  thrpt   30    5878.830 ±   20.724  ops/s 
[info] IONarrowFlatMapBenchmark.futureNarrowFlatMap               N/A      N/A   10000  thrpt   30     847.120 ±   30.852  ops/s 
[info] IONarrowFlatMapBenchmark.monixBioNarrowFlatMap             N/A      N/A   10000  thrpt   30    7502.053 ±   29.003  ops/s 
[info] IONarrowFlatMapBenchmark.monixNarrowFlatMap                N/A      N/A   10000  thrpt   30    7494.606 ±   42.565  ops/s 
[info] IONarrowFlatMapBenchmark.zioNarrowFlatMap                  N/A      N/A   10000  thrpt   30    6053.870 ±   25.552  ops/s 

[info] IOEmptyRaceBenchmark.catsEmptyRace                         N/A      N/A    1000  thrpt   30     376.517 ±    9.853  ops/s 
[info] IOEmptyRaceBenchmark.monixBioEmptyRace                     N/A      N/A    1000  thrpt   30     343.120 ±    1.743  ops/s 
[info] IOEmptyRaceBenchmark.monixEmptyRace                        N/A      N/A    1000  thrpt   30     346.287 ±    3.841  ops/s 
[info] IOEmptyRaceBenchmark.zioEmptyRace                          N/A      N/A    1000  thrpt   30      65.288 ±    1.717  ops/s 

[info] IOSequenceBenchmark.catsParSequence                        100      N/A     N/A  thrpt   30    5931.683 ±   31.683  ops/s 
[info] IOSequenceBenchmark.catsParSequence                       1000      N/A     N/A  thrpt   30     555.898 ±    2.747  ops/s 
[info] IOSequenceBenchmark.catsParSequenceN                       100      N/A     N/A  thrpt   30    6468.484 ±   70.442  ops/s 
[info] IOSequenceBenchmark.catsParSequenceN                      1000      N/A     N/A  thrpt   30     302.005 ±   31.390  ops/s 
[info] IOSequenceBenchmark.catsSequence                           100      N/A     N/A  thrpt   30   99080.596 ±  362.897  ops/s 
[info] IOSequenceBenchmark.catsSequence                          1000      N/A     N/A  thrpt   30    9688.414 ±   45.275  ops/s 
[info] IOSequenceBenchmark.futureSequence                         100      N/A     N/A  thrpt   30   11469.757 ±   48.180  ops/s 
[info] IOSequenceBenchmark.futureSequence                        1000      N/A     N/A  thrpt   30    1556.510 ±  106.805  ops/s 
[info] IOSequenceBenchmark.monixBioGather                         100      N/A     N/A  thrpt   30    9992.616 ±   68.297  ops/s 
[info] IOSequenceBenchmark.monixBioGather                        1000      N/A     N/A  thrpt   30    1825.815 ±   18.950  ops/s 
[info] IOSequenceBenchmark.monixBioGatherN                        100      N/A     N/A  thrpt   30   18452.927 ±   67.554  ops/s 
[info] IOSequenceBenchmark.monixBioGatherN                       1000      N/A     N/A  thrpt   30    3818.984 ±   17.530  ops/s 
[info] IOSequenceBenchmark.monixBioGatherUnordered                100      N/A     N/A  thrpt   30   12254.025 ±  132.574  ops/s 
[info] IOSequenceBenchmark.monixBioGatherUnordered               1000      N/A     N/A  thrpt   30    1177.839 ±   91.644  ops/s 
[info] IOSequenceBenchmark.monixBioSequence                       100      N/A     N/A  thrpt   30  299900.839 ± 1734.770  ops/s 
[info] IOSequenceBenchmark.monixBioSequence                      1000      N/A     N/A  thrpt   30   32325.930 ±  127.897  ops/s 
[info] IOSequenceBenchmark.monixGather                            100      N/A     N/A  thrpt   30   10318.503 ±   79.700  ops/s 
[info] IOSequenceBenchmark.monixGather                           1000      N/A     N/A  thrpt   30    1838.422 ±   41.339  ops/s 
[info] IOSequenceBenchmark.monixGatherN                           100      N/A     N/A  thrpt   30   18673.274 ±   30.147  ops/s 
[info] IOSequenceBenchmark.monixGatherN                          1000      N/A     N/A  thrpt   30    4044.859 ±   23.332  ops/s 
[info] IOSequenceBenchmark.monixGatherUnordered                   100      N/A     N/A  thrpt   30   12635.339 ±  477.038  ops/s 
[info] IOSequenceBenchmark.monixGatherUnordered                  1000      N/A     N/A  thrpt   30    1079.405 ±   64.828  ops/s 
[info] IOSequenceBenchmark.monixSequence                          100      N/A     N/A  thrpt   30  310564.348 ± 8099.175  ops/s 
[info] IOSequenceBenchmark.monixSequence                         1000      N/A     N/A  thrpt   30   32006.174 ±  154.372  ops/s 
[info] IOSequenceBenchmark.zioParSequence                         100      N/A     N/A  thrpt   30     583.752 ±    4.781  ops/s 
[info] IOSequenceBenchmark.zioParSequence                        1000      N/A     N/A  thrpt   30      19.831 ±    0.118  ops/s 
[info] IOSequenceBenchmark.zioParSequenceN                        100      N/A     N/A  thrpt   30    1897.714 ±   17.671  ops/s 
[info] IOSequenceBenchmark.zioParSequenceN                       1000      N/A     N/A  thrpt   30     323.613 ±    4.579  ops/s 
[info] IOSequenceBenchmark.zioSequence                            100      N/A     N/A  thrpt   30  133190.725 ±  707.170  ops/s 
[info] IOSequenceBenchmark.zioSequence                           1000      N/A     N/A  thrpt   30   14209.771 ±  200.079  ops/s 
```

## Credits

Most of the code comes from [Monix](https://github.com/monix/monix) which was customized to include support for error type parameter directly in the internals.

The idea of a second type parameter comes from [ZIO](https://github.com/zio/zio). Its implementation and API for error handling with two error channels served as an inspiration to the entire idea and some of the solutions. A lot of the benchmarks also come from their repository.

[Cats-bio](https://github.com/LukaJCB/cats-bio) has been extremely useful at the beginning because of many similarities between `monix.eval.Task` and `cats.effect.IO` internals.
