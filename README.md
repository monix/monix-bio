# Monix-bio

Experimental alternative to [monix.eval.Task](https://monix.io/api/3.1/monix/eval/Task.html) from [Monix](https://github.com/monix/monix) which uses a second type parameter to represent recoverable errors.

## Getting Started

## Stability

Currently there are no backwards compatibility guarantees. It will take a bit of time for everything to stabilize so I strongly advise against using in serious projects. :) On the other hand, please play with it, give feedback and report bugs! It will be a tremendous help in getting this production-ready faster.

One of the main goals is to stay as consistent with `monix.eval.Task` as possible so the core functionality will not see much changes but it is possible that type signatures change, e.g. to return `UIO[A]` where possible. 

Error handling operators are actively worked on and they will most likely see breaking changes.

Most of the internals didn't see significant changes when compared to `monix.eval.Task` so this implementation can greatly benefit from maturity of its parent but the project has just started and the changes weren't thoroughly tested yet.

## Motivation

## Brief comparison to other solutions

## Performance

## Credits

Most of the code comes from [Monix](https://github.com/monix/monix) which was customized to include support for error type parameter directly in the internals.

The idea of a second type parameter comes from [ZIO](https://github.com/zio/zio). Its implementation and API for error handling with two error channels served as an inspiration to the entire idea and some of the solutions. A lot of the benchmarks also come from their repository.

[Cats-bio](https://github.com/LukaJCB/cats-bio) has been extremely useful at the beginning because of many similarities between `monix.eval.Task` and `cats.effect.IO` internals.
