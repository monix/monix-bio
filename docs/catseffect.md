---
id: cats-effect
title: Cats-Effect Integration
---

Monix-BIO provides [Cats-Effect](https://github.com/typelevel/cats-effect/) integration out of the box.
In practice, it means that integration with Typelevel libraries, such as [http4s](https://github.com/http4s/http4s), or [doobie](https://github.com/tpolecat/doobie) should work without much hassle.

## Getting instances in scope

All Cats instances up until [Effect](https://typelevel.org/cats-effect/typeclasses/effect.html) and [ConcurrentEffect](https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html) (excluded) are available automatically, without any imports.

```scala mdoc:silent:reset
import cats.syntax.parallel._
import monix.bio.IO

val taskA = IO(20)
val taskB = IO(22)

// evaluates taskA and taskB in parallel, then sums the results
val taskAplusB = (taskA, taskB).parMapN(_ + _)
```

`ConcurrentEffect` and `Effect` can be derived if there is `Scheduler` in scope.

### Sync and above

Infamous [Sync type class](https://typelevel.org/cats-effect/typeclasses/sync.html) extends `Bracket[F, Throwable]`.
`Throwable` is the error type and as an unfortunate consequence - any type class from `Sync` and above will only work with `IO[Throwable, A]`.

For instance, let's say we want to use [monix.catnap.ConcurrentQueue](https://monix.io/api/current/monix/catnap/ConcurrentQueue.html)
which exposes an interface built on Cats-Effect type classes so it can be used with any effect:

```scala mdoc:silent:reset
import monix.bio.{IO, Task}
import monix.catnap.ConcurrentQueue

val queueExample: IO[Throwable, String] = for {
  queue <- ConcurrentQueue[Task].bounded[String](10)
  _ <- queue.offer("Message")
  msg <- queue.poll
} yield msg
```

The `bounded` constructor requires `Concurrent[F]` and `ContextShift[F]` in scope.
Both requirements are automatically derived by `IO`, but `Concurrent` extends `Sync`, so we need to settle on `Throwable` error type.
Since our `F` is `IO[Throwable, *]`, all operations on `ConcurrentQueue` will return `IO[Throwable, *]`.

A workaround is to use `hideErrors` because these methods don't throw any errors, and even if they did - how would we handle them?

```scala mdoc:silent:reset
import monix.bio.{Task, UIO}
import monix.catnap.ConcurrentQueue

val queueExample: UIO[String] = (for {
  queue <- ConcurrentQueue[Task].bounded[String](10)
  _ <- queue.offer("Message")
  msg <- queue.poll
} yield msg).hideErrors
```

If typed errors prove to be a great idea in the long term, and not just a temporary fashion, new editions of Cats will likely support it more naturally.

## Converting from/to other effects

Cats-Effect provides a hierarchy of type classes that open the door to conversion between effects.

Monix-BIO provides:
- `monix.bio.IOLike` to convert other effects to `IO` with nice `IO.from` syntax.
- `monix.bio.IOLift` to convert `IO` to a different type with `io.to[F]` syntax.

### cats.effect.IO

Going from `cats.effect.IO` to `monix.bio.IO` is very simple because `cats.effect.IO` does not need any runtime to execute:

```scala mdoc:silent:reset
import monix.bio.IO

val catsIO = cats.effect.IO(20)
val monixIO: IO[Throwable, Int] = IO.from(catsIO)
```

Unfortunately, we need `Scheduler` in scope to go the other way:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global

val monixIO: IO[Throwable, Int] = IO(20)
val catsAgain: cats.effect.IO[Int] = monixIO.to[cats.effect.IO]
```

### monix.eval.Task

`monix.bio.IO` does not include `monix.eval.Task` in dependencies, but since they both use `Scheduler` to run, we can do it
without requiring any implicit in scope, if we use `deferAction`:

```scala mdoc:silent:reset
import monix.bio.IO
import monix.eval.Task

val task = Task(20)
val bio: IO[Throwable, Int] = IO.deferAction(implicit s => IO.from(task))
val taskAgain: Task[Int] = Task.deferAction(implicit s => bio.to[Task])
```

In the future, we might introduce a type class in `monix-execution`, which will allow this conversion without any tricks with `deferAction`.

### zio.ZIO

To convert from `ZIO`, you will need `ConcurrentEffect[zio.Task]` instance. 
It can be derived with [zio-interop-cats](https://github.com/zio/interop-cats) if you have `zio.Runtime` in scope:

```scala mdoc:silent:reset
import monix.bio.IO
import zio.interop.catz._

implicit val rts = zio.Runtime.default

val z = zio.ZIO.effect(20)
val monixIO: IO[Throwable, Int] = IO.from(z)
```

The other direction requires `Scheduler` in scope:

```scala mdoc:silent:reset
import monix.bio.IO
import zio.interop.catz._

implicit val s = monix.execution.Scheduler.global

val monixIO: IO[Throwable, Int] = IO(20)
val zioAgain: zio.Task[Int] = monixIO.to[zio.Task]
```
