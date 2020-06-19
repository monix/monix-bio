# Monix-BIO

[![Maven Central](https://img.shields.io/maven-central/v/io.monix/monix-bio_2.12.svg)](https://search.maven.org/search?q=g:io.monix%20AND%20a:monix-bio_2.12)
[![Join the chat at https://gitter.im/monix/monix-bio](https://badges.gitter.im/monix/monix-bio.svg)](https://gitter.im/monix/monix-bio?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/io.monix/monix-bio_2.12.svg)](https://oss.sonatype.org/content/repositories/snapshots/io/monix/monix-bio_2.12/)

Asynchronous data type with typed errors.
An enhanced version of [monix.eval.Task](https://monix.io/api/3.1/monix/eval/Task.html).

[Visit Documentation Website](https://monix.github.io/monix-bio/)

## The latest version

The latest stable version, compatible with Monix 3.x, Cats 2.x and Cats-Effect 2.x:

```scala
libraryDependencies += "io.monix" %% "monix-bio" % "0.1.1"
```
Published for ScalaJS 0.6.x, 1.x, Scala 2.12 and 2.13.

## Current Status

The project maintains binary compatibility in `0.1.x` line and it is suitable for production usage.

`BIO` covers full `monix.eval.Task` API.

The documentation is in progress and not all scaladocs are updated for `BIO`.

## Plans for the future

Long term `1.0.0` version is expected to come around July-August 2020 once the basic documentation is complete.

We are considering [a different encoding](https://github.com/monix/monix-bio/issues/6) but if we don't go forward with it there won't be many changes.

Upcoming features (either `1.0.0` or later):
- `reactive` module to use `monix.reactive.Observable` with `monix.bio.BIO`
- built-in interop with `monix.eval.Task` without any imports
- `Coeval` with typed errors
- better stack traces

## Contributing

I will really appreciate feedback, bugs and complaints about the project.

If you'd like to contribute code then look for issues tagged with [good first issue](https://github.com/monix/monix-bio/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)
or just ask me on gitter and I should be able to find something regardless of your level of expertise. :)

I'm happy to mentor anyone interested in contributing.

## Credits

Most of the code comes from [Monix](https://github.com/monix/monix) which was customized to include support for error type parameter directly in the internals.

The idea of a second type parameter comes from [ZIO](https://github.com/zio/zio). Its implementation and API for error handling with two error channels served as an inspiration to the entire idea and some of the solutions. A lot of the benchmarks also come from their repository.

[Cats-bio](https://github.com/LukaJCB/cats-bio) has been extremely useful at the beginning because of many similarities between `monix.eval.Task` and `cats.effect.IO` internals.
