# Monix-BIO

[![Maven Central](https://img.shields.io/maven-central/v/io.monix/monix-bio_2.12.svg)](https://search.maven.org/search?q=g:io.monix%20AND%20a:monix-bio_2.12)
[![Join the chat at https://gitter.im/monix/monix-bio](https://badges.gitter.im/monix/monix-bio.svg)](https://gitter.im/monix/monix-bio?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Alternative to [monix.eval.Task](https://monix.io/api/3.1/monix/eval/Task.html) from [Monix](https://github.com/monix/monix) which uses a second type parameter to represent recoverable errors.

[Documentation Website](https://monix.github.io/monix-bio/)

## Getting Started

The latest stable version which is compatible with Monix 3.x, Cats 2.x and Cats-Effect 2.x:

```scala
libraryDependencies += "io.monix" %% "monix-bio" % "0.1.0"
```

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

// Nothing happens until it runs, returns -1 after completion
val f: CancelableFuture[Int] = handled.runToFuture
    
// => taskB has finished
// => taskA has been cancelled
```

## Current Status

The project will maintain binary compatibility in `0.1.x` line. 
It is suitable for general usage but I would not recommend building a lot of libraries on it just yet unless you are fine with upgrade in few months.

WARNING: Not all scaladocs are updated for `BIO` and currently there is no other documentation on the website. 
It will be my priority in April.
Until then I consider the project as something for more advanced user who are already familiar with idea of IO Monad and error type exposed in the type parameter.

### Plans for the future

I hope to gather some feedback from production usage and then move towards long term stable version.
Core of the functionality is shared with `monix.eval.Task` (which is stable) and will remain consistent with it. 
There might be breaking changes in regards to new error handling combinators, terminology, return types or error reporting.

## Contributing

I will really appreciate feedback, bugs and complaints about the project.

If you'd like to contribute code then look for issues tagged with [good first issue](https://github.com/monix/monix-bio/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)
or just ask me on gitter and I should be able to find something regardless of your level of expertise. :)

I'm happy to mentor anyone interested in contributing.

## Performance

At the time of writing (Q1 2020) performance is as good as `monix.eval.Task` in most benchmarks and `BIO` can outperform it for error handling operators if the error type is not `Throwable`.
It makes it the most performant effect type in today's Scala ecosystem.

Performance is high priority for us and we will greatly appreciate if you open an issue or write on [gitter](https://gitter.im/monix/monix) if you discover use cases where it performs badly.

You can find benchmarks and their results inside [benchmarks module](benchmarks).

## Credits

Most of the code comes from [Monix](https://github.com/monix/monix) which was customized to include support for error type parameter directly in the internals.

The idea of a second type parameter comes from [ZIO](https://github.com/zio/zio). Its implementation and API for error handling with two error channels served as an inspiration to the entire idea and some of the solutions. A lot of the benchmarks also come from their repository.

[Cats-bio](https://github.com/LukaJCB/cats-bio) has been extremely useful at the beginning because of many similarities between `monix.eval.Task` and `cats.effect.IO` internals.
