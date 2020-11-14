/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio

import cats.effect.{
  CancelToken,
  Clock,
  Concurrent,
  ConcurrentEffect,
  ContextShift,
  Effect,
  ExitCase,
  IO => CIO,
  Timer,
  Fiber => _
}
import cats.{~>, CommutativeApplicative, Monoid, Parallel, Semigroup}
import monix.bio.instances._
import monix.bio.internal._
import monix.catnap.FutureLift
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.annotations.{UnsafeBecauseBlocking, UnsafeBecauseImpure}
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.internal.Platform
import monix.execution.misc.Local
import monix.execution.schedulers.{CanBlock, TracingScheduler, TrampolinedRunnable}
import monix.execution.{Scheduler, _}
import org.reactivestreams.Publisher

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.concurrent.duration.{Duration, FiniteDuration, NANOSECONDS, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import monix.bio.tracing.{IOEvent, IOTrace}

/** `Task` represents a specification for a possibly lazy or
  * asynchronous computation, which when executed will produce an `A`
  * as a result, along with possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation or a value detached from time,
  * as `Task` does not execute anything when working with its builders
  * or operators and it does not submit any work into any thread-pool,
  * the execution eventually taking place only after `runAsync` is
  * called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing
  * `runAsync`.
  *
  * =Getting Started=
  *
  * To build a `IO` from a by-name parameters (thunks), we can use
  * [[monix.bio.IO.apply IO.apply]] (
  * alias [[monix.bio.IO.eval IO.eval]]),
  * [[monix.bio.IO.evalTotal]] if the thunk is guaranteed to not throw any exceptions, or
  * [[monix.bio.IO.evalAsync IO.evalAsync]]:
  *
  * {{{
  *   val hello = IO("Hello ")
  *   val world = IO.evalAsync("World!")
  * }}}
  *
  * Nothing gets executed yet, as `IO` is lazy, nothing executes
  * until you trigger its evaluation via [[IO!.runAsync runAsync]] or
  * [[IO!.runToFuture runToFuture]].
  *
  * To combine `IO` values we can use [[IO!.map .map]] and
  * [[IO!.flatMap .flatMap]], which describe sequencing and this time
  * it's in a very real sense because of the laziness involved:
  *
  * {{{
  *   val sayHello = hello
  *     .flatMap(h => world.map(w => h + w))
  *     .map(println)
  * }}}
  *
  * This `IO` reference will trigger a side effect on evaluation, but
  * not yet. To make the above print its message:
  *
  * {{{
  *   import monix.execution.CancelableFuture
  *   import monix.execution.Scheduler.Implicits.global
  *
  *   val f = sayHello.runToFuture
  *   // => Hello World!
  * }}}
  *
  * The returned type is a
  * [[monix.execution.CancelableFuture CancelableFuture]] which
  * inherits from Scala's standard [[scala.concurrent.Future Future]],
  * a value that can be completed already or might be completed at
  * some point in the future, once the running asynchronous process
  * finishes. Such a future value can also be canceled, see below.
  *
  * =Laziness, Purity and Referential Transparency=
  *
  * The fact that `Task` is lazy whereas `Future` is not
  * has real consequences. For example with `Task` you can do this:
  *
  * {{{
  *   import scala.concurrent.duration._
  *
  *   def retryOnFailure[A](times: Int, source: Task[A]): Task[A] =
  *     source.onErrorHandleWith { err =>
  *       // No more retries left? Re-throw error:
  *       if (times <= 0) Task.raiseError(err) else {
  *         // Recursive call, yes we can!
  *         retryOnFailure(times - 1, source)
  *           // Adding 500 ms delay for good measure
  *           .delayExecution(500.millis)
  *       }
  *     }
  * }}}
  *
  * `Future` being a strict value-wannabe means that the actual value
  * gets "memoized" (means cached), however `Task` is basically a function
  * that can be repeated for as many times as you want.
  *
  * `Task` is a pure data structure that can be used to describe
  * pure functions, the equivalent of Haskell's `IO`.
  *
  * ==Memoization==
  *
  * `Task` can also do memoization, making it behave like a "lazy"
  * Scala `Future`, meaning that nothing is started yet, its
  * side effects being evaluated on the first `runAsync` and then
  * the result reused on subsequent evaluations:
  *
  * {{{
  *   Task(println("boo")).memoize
  * }}}
  *
  * The difference between this and just calling `runAsync()` is that
  * `memoize()` still returns a `Task` and the actual memoization
  * happens on the first `runAsync()` (with idempotency guarantees of
  * course).
  *
  * But here's something else that the `Future` data type cannot do,
  * [[monix.bio.IO!.memoizeOnSuccess memoizeOnSuccess]]:
  *
  * {{{
  *   Task.eval {
  *     if (scala.util.Random.nextDouble() > 0.33)
  *       throw new RuntimeException("error!")
  *     println("moo")
  *   }.memoizeOnSuccess
  * }}}
  *
  * This keeps repeating the computation for as long as the result is a
  * failure and caches it only on success. Yes we can!
  *
  * ''WARNING:'' as awesome as `memoize` can be, use with care
  * because memoization can break referential transparency!
  *
  * ==Parallelism==
  *
  * Because of laziness, invoking
  * [[monix.bio.IO.sequence IO.sequence]] will not work like
  * it does for `Future.sequence`, the given `Task` values being
  * evaluated one after another, in ''sequence'', not in ''parallel''.
  * If you want parallelism, then you need to use
  * [[monix.bio.IO.parSequence IO.parSequence]] and thus be explicit about it.
  *
  * This is great because it gives you the possibility of fine tuning the
  * execution. For example, say you want to execute things in parallel,
  * but with a maximum limit of 30 tasks being executed in parallel.
  * One way of doing that is to process your list in batches:
  *
  * {{{
  *   // Some array of tasks, you come up with something good :-)
  *   val list: Seq[Task[Int]] = Seq.tabulate(100)(Task(_))
  *
  *   // Split our list in chunks of 30 items per chunk,
  *   // this being the maximum parallelism allowed
  *   val chunks = list.sliding(30, 30).toSeq
  *
  *   // Specify that each batch should process stuff in parallel
  *   val batchedTasks = chunks.map(chunk => Task.parSequence(chunk))
  *   // Sequence the batches
  *   val allBatches = Task.sequence(batchedTasks)
  *
  *   // Flatten the result, within the context of Task
  *   val all: Task[Seq[Int]] = allBatches.map(_.flatten)
  * }}}
  *
  * Note that the built `Task` reference is just a specification at
  * this point, or you can view it as a function, as nothing has
  * executed yet, you need to call [[IO!.runAsync runAsync]]
  * or [[IO!.runToFuture runToFuture]] explicitly.
  *
  * =Cancellation=
  *
  * The logic described by an `Task` task could be cancelable,
  * depending on how the `Task` gets built.
  *
  * [[monix.execution.CancelableFuture CancelableFuture]] references
  * can also be canceled, in case the described computation can be
  * canceled. When describing `Task` tasks with `Task.eval` nothing
  * can be cancelled, since there's nothing about a plain function
  * that you can cancel, but we can build cancelable tasks with
  * [[monix.bio.IO.cancelable0[E,A](register* IO.cancelable]].
  *
  * {{{
  *   import scala.concurrent.duration._
  *   import scala.util._
  *
  *   val delayedHello = Task.cancelable0[Unit] { (scheduler, callback) =>
  *     val task = scheduler.scheduleOnce(1.second) {
  *       println("Delayed Hello!")
  *       // Signaling successful completion
  *       callback(Success(()))
  *     }
  *     // Returning a cancel token that knows how to cancel the
  *     // scheduled computation:
  *     Task {
  *       println("Cancelling!")
  *       task.cancel()
  *     }
  *   }
  * }}}
  *
  * The sample above prints a message with a delay, where the delay
  * itself is scheduled with the injected `Scheduler`. The `Scheduler`
  * is in fact an implicit parameter to `runAsync()`.
  *
  * This action can be cancelled, because it specifies cancellation
  * logic. In case we have no cancelable logic to express, then it's
  * OK if we returned a
  * [[monix.execution.Cancelable.empty Cancelable.empty]] reference,
  * in which case the resulting `Task` would not be cancelable.
  *
  * But the `Task` we just described is cancelable, for one at the
  * edge, due to `runAsync` returning [[monix.execution.Cancelable Cancelable]]
  * and [[monix.execution.CancelableFuture CancelableFuture]] references:
  *
  * {{{
  *   // Triggering execution
  *   val cf = delayedHello.runToFuture
  *
  *   // If we change our mind before the timespan has passed:
  *   cf.cancel()
  * }}}
  *
  * But also cancellation is described on `Task` as a pure action,
  * which can be used for example in [[monix.bio.IO.race race]] conditions:
  *
  * {{{
  *   import scala.concurrent.duration._
  *   import scala.concurrent.TimeoutException
  *
  *   val ta = Task(1 + 1).delayExecution(4.seconds)
  *
  *   val tb = Task.raiseError[Int](new TimeoutException)
  *     .delayExecution(4.seconds)
  *
  *   Task.racePair(ta, tb).flatMap {
  *     case Left((a, fiberB)) =>
  *       fiberB.cancel.map(_ => a)
  *     case Right((fiberA, b)) =>
  *       fiberA.cancel.map(_ => b)
  *   }
  * }}}
  *
  * The returned type in `racePair` is [[Fiber]], which is a data
  * type that's meant to wrap tasks linked to an active process
  * and that can be [[Fiber.cancel canceled]] or [[Fiber.join joined]].
  *
  * Also, given a task, we can specify actions that need to be
  * triggered in case of cancellation, see
  * [[monix.bio.IO!.doOnCancel doOnCancel]]:
  *
  * {{{
  *   val task = Task.eval(println("Hello!")).executeAsync
  *
  *   task doOnCancel IO.evalTotal {
  *     println("A cancellation attempt was made!")
  *   }
  * }}}
  *
  * Given a task, we can also create a new task from it that atomic
  * (non cancelable), in the sense that either all of it executes
  * or nothing at all, via [[monix.bio.IO!.uncancelable uncancelable]].
  *
  * =Note on the ExecutionModel=
  *
  * `Task` is conservative in how it introduces async boundaries.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the current call stack on which the
  * asynchronous computation was started. But one shouldn't make
  * assumptions about how things will end up executed, as ultimately
  * it is the implementation's job to decide on the best execution
  * model. All you are guaranteed (and can assume) is asynchronous
  * execution after executing `runAsync`.
  *
  * Currently the default
  * [[monix.execution.ExecutionModel ExecutionModel]] specifies
  * batched execution by default and `Task` in its evaluation respects
  * the injected `ExecutionModel`. If you want a different behavior,
  * you need to execute the `Task` reference with a different scheduler.
  *
  * @define schedulerDesc is an injected
  *         [[monix.execution.Scheduler Scheduler]] that gets used
  *         whenever asynchronous boundaries are needed when
  *         evaluating the task; a `Scheduler` is in general needed
  *         when the `Task` needs to be evaluated via `runAsync`
  * @define schedulerEvalDesc is the
  *         [[monix.execution.Scheduler Scheduler]] needed in order
  *         to evaluate the source, being required in Task's
  *         [[runAsync]], [[runAsyncF]] or [[runToFuture]].
  * @define callbackDesc ==BiCallback==
  *
  *         When executing the task via this method, the user is
  *         required to supply a side effecting callback with the
  *         signature: `Either[Cause[E], A] => Unit`.
  *
  *         This will be used by the implementation to signal completion,
  *         signaling either a `Right(value)` or a `Left(error)`.
  *
  *         `IO` however uses [[monix.bio.BiCallback BiCallback]]
  *         internally, so you can supply a `BiCallback` instance instead
  *         and it will be used to avoid unnecessary boxing. It also has
  *         handy utilities.
  *
  *         Note that with `Callback` you can:
  *
  *          - convert from a plain function using `Either[Cause[E], A]` as input via
  *            [[monix.bio.BiCallback.fromAttempt BiCallback.fromAttempt]]
  *          - wrap a standard Scala `Promise` via
  *            [[monix.bio.BiCallback.fromPromise BiCallback.fromPromise]]
  *          - pass an empty callback that just reports errors via
  *            [[monix.bio.BiCallback.empty BiCallback.empty]]
  * @define callbackParamDesc is a callback that will be invoked upon
  *         completion, either with a successful result, or with an error;
  *         note that you can use [[monix.bio.BiCallback]]
  *         for extra performance (avoids the boxing in [[scala.Either]])
  *
  * @define cancelableDesc a [[monix.execution.Cancelable Cancelable]]
  *         that can be used to cancel a running task
  *
  * @define cancelTokenDesc a `Task[Unit]`, aliased via Cats-Effect
  *         as a `CancelToken[Task]`, that can be used to cancel the
  *         running task. Given that this is a `Task`, it can describe
  *         asynchronous finalizers (if the source had any), therefore
  *         users can apply back-pressure on the completion of such
  *         finalizers.
  *
  * @define optionsDesc a set of [[monix.bio.IO.Options Options]]
  *         that determine the behavior of Task's run-loop.
  *
  * @define startInspiration Inspired by
  *         [[https://github.com/functional-streams-for-scala/fs2 FS2]],
  *         with the difference that this method does not fork
  *         automatically, being consistent with Monix's default
  *         behavior.
  *
  * @define runSyncUnsafeTimeout is a duration that specifies the
  *         maximum amount of time that this operation is allowed to block the
  *         underlying thread. If the timeout expires before the result is ready,
  *         a `TimeoutException` gets thrown. Note that you're allowed to
  *         pass an infinite duration (with `Duration.Inf`), but unless
  *         it's `main` that you're blocking and unless you're doing it only
  *         once, then this is definitely not recommended — provide a finite
  *         timeout in order to avoid deadlocks.
  *
  * @define runSyncUnsafePermit is an implicit value that's only available for
  *         the JVM and not for JavaScript, its purpose being to stop usage of
  *         this operation on top of engines that do not support blocking threads.
  * @define runSyncMaybeReturn `Right(result)` in case a result was processed,
  *         or `Left(future)` in case an asynchronous boundary
  *         was hit and further async execution is needed
  * @define runSyncStepReturn `Right(result)` in case a result was processed,
  *         or `Left(task)` in case an asynchronous boundary
  *         was hit and further async execution is needed
  * @define runAsyncToFutureReturn a
  *         [[monix.execution.CancelableFuture CancelableFuture]]
  *         that can be used to extract the result or to cancel
  *         a running task.
  * @define bracketErrorNote '''NOTE on error handling''': one big
  *         difference versus `try {} finally {}` is that, in case
  *         both the `release` function and the `use` function throws,
  *         the error raised by `use` gets signaled and the error
  *         raised by `release` gets reported with
  *         [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]].
  *
  *         For example:
  *
  *         {{{
  *           IO.evalAsync("resource").bracket { _ =>
  *             // use
  *             IO.raiseError(new RuntimeException("Foo"))
  *           } { _ =>
  *             // release
  *             IO.terminate(new RuntimeException("Bar"))
  *           }
  *         }}}
  *
  *         In this case the error signaled downstream is `"Foo"`,
  *         while the `"Bar"` error gets reported. This is consistent
  *         with the behavior of Haskell's `bracket` operation and NOT
  *         with `try {} finally {}` from Scala, Java or JavaScript.
  * @define unsafeRun '''UNSAFE (referential transparency)''' —
  *         this operation can trigger the execution of side effects, which
  *         breaks referential transparency and is thus not a pure function.
  *
  *         Normally these functions shouldn't be called until
  *         "the end of the world", which is to say at the end of
  *         the program (for a console app), or at the end of a web
  *         request (in case you're working with a web framework or
  *         toolkit that doesn't provide good integration with Monix's
  *         `Task` via Cats-Effect).
  *
  *         Otherwise for modifying or operating on tasks, prefer
  *         its pure functions like `map` and `flatMap`.
  *         In FP code don't use `runAsync`. Remember that `Task`
  *         is not a 1:1 replacement for `Future`, `Task` being
  *         a very different abstraction.
  * @define memoizeCancel '''Cancellation''' — a memoized task will mirror
  *         the behavior of the source on cancellation. This means that:
  *
  *          - if the source isn't cancellable, then the resulting memoized
  *            task won't be cancellable either
  *          - if the source is cancellable, then the memoized task can be
  *            cancelled, which can take unprepared users by surprise
  *
  *         Depending on use-case, there are two ways to ensure no surprises:
  *
  *          - usage of [[onCancelRaiseError]], before applying memoization, to
  *            ensure that on cancellation an error is triggered and then noticed
  *            by the memoization logic
  *          - usage of [[uncancelable]], either before or after applying
  *            memoization, to ensure that the memoized task cannot be cancelled
  * @define memoizeUnsafe '''UNSAFE''' — this operation allocates a shared,
  *         mutable reference, which can break in certain cases
  *         referential transparency, even if this operation guarantees
  *         idempotency (i.e. referential transparency implies idempotency,
  *         but idempotency does not imply referential transparency).
  *
  *         The allocation of a mutable reference is known to be a
  *         side effect, thus breaking referential transparency,
  *         even if calling this method does not trigger the evaluation
  *         of side effects suspended by the source.
  *
  *         Use with care. Sometimes it's easier to just keep a shared,
  *         memoized reference to some connection, but keep in mind
  *         it might be better to pass such a reference around as
  *         a parameter.
  */
sealed abstract class IO[+E, +A] extends Serializable {
  import IO._
  import cats.effect.Async

  /** Triggers the asynchronous execution, returning a cancelable
    * [[monix.execution.CancelableFuture CancelableFuture]] that can
    * be awaited for the final result or canceled.
    *
    * Note that without invoking `runAsync` on a `Task`, nothing
    * gets evaluated, as a `Task` has lazy behavior.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   // A Scheduler is needed for executing tasks via `runAsync`
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   // Nothing executes yet
    *   val task: Task[String] =
    *     for {
    *       _ <- Task.sleep(3.seconds)
    *       r <- Task { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *   // Triggering the task's execution:
    *   val f = task.runToFuture
    *
    *   // Or in case we change our mind
    *   f.cancel()
    * }}}
    *
    * $unsafeRun
    *
    * BAD CODE:
    * {{{
    *   import monix.execution.CancelableFuture
    *   import scala.concurrent.Await
    *
    *   // ANTI-PATTERN 1: Unnecessary side effects
    *   def increment1(sample: UIO[Int]): CancelableFuture[Int] = {
    *     // No reason to trigger `runAsync` for this operation
    *     sample.runToFuture.map(_ + 1)
    *   }
    *
    *   // ANTI-PATTERN 2: blocking threads makes it worse than (1)
    *   def increment2(sample: UIO[Int]): Int = {
    *     // Blocking threads is totally unnecessary
    *     val x = Await.result(sample.runToFuture, 5.seconds)
    *     x + 1
    *   }
    *
    *   // ANTI-PATTERN 3: this is even WORSE than (2)!
    *   def increment3(sample: Task[Int]): Task[Int] = {
    *     // Triggering side-effects, but misleading users/readers
    *     // into thinking this function is pure via the return type
    *     IO.fromFuture(sample.runToFuture.map(_ + 1))
    *   }
    * }}}
    *
    * Instead prefer the pure versions. `IO` has its own [[map]],
    * [[flatMap]], [[onErrorHandleWith]] or [[bracketCase]], which
    * are really powerful and can allow you to operate on a task
    * in however way you like without escaping IO's context and
    * triggering unwanted side-effects.
    *
    * @param s $schedulerDesc
    * @return $runAsyncToFutureReturn
    */
  @UnsafeBecauseImpure
  final def runToFuture(implicit s: Scheduler, ev: E <:< Throwable): CancelableFuture[A] =
    runToFutureOpt(s, IO.defaultOptions, ev)

  /** Triggers the asynchronous execution, much like normal [[runToFuture]],
    * but includes the ability to specify [[monix.bio.IO.Options Options]]
    * that can modify the behavior of the run-loop.
    *
    * This is the configurable version of [[runToFuture]].
    * It allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See [[IO.Options]]. Example:
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- IOLocal(0)
    *       _     <- local.write(100)
    *       _     <- IO.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of IOLocal via:
    *   implicit val opts = IO.defaultOptions.enableLocalContextPropagation
    *   // Actual execution that depends on these custom options:
    *   // task.runToFutureOpt
    * }}}
    *
    * $unsafeRun
    *
    * PLEASE READ the advice on anti-patterns at [[runToFuture]].
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $runAsyncToFutureReturn
    */
  @UnsafeBecauseImpure
  def runToFutureOpt(implicit s: Scheduler, opts: Options, ev: E <:< Throwable): CancelableFuture[A] = {
    val opts2 = opts.withSchedulerFeatures

    if (opts2.localContextPropagation) IORunToFutureWithLocal.startFuture(this, s, opts2)
    else TaskRunLoop.startFuture(this, s, opts2)
  }

  /** Triggers the asynchronous execution, with a provided callback
    * that's going to be called at some point in the future with
    * the final result.
    *
    * Note that without invoking `runAsync` on a `IO`, nothing
    * gets evaluated, as a `IO` has lazy behavior.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   import monix.bio.Cause
    *   // A Scheduler is needed for executing tasks via `runAsync`
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   // Nothing executes yet
    *   val task: IO[String, String] =
    *     for {
    *       _ <- IO.sleep(3.seconds)
    *       r <- IO.evalTotal { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *   // Triggering the task's execution:
    *   val f = task.runAsync {
    *     case Right(str: String) =>
    *       println(s"Received: $$str")
    *     case Left(Cause.Termination(e)) =>
    *       global.reportFailure(e)
    *     case Left(Cause.Error(str: String)) =>
    *       println(s"Received expected error: $$str")
    *   }
    *
    *   // Or in case we change our mind
    *   f.cancel()
    * }}}
    *
    * $callbackDesc
    *
    * Example, equivalent to the above:
    * {{{
    *   import monix.bio.BiCallback
    *
    *   task.runAsync(new BiCallback[String, String] {
    *     def onSuccess(str: String) =
    *       println(s"Received: $$str")
    *     def onError(e: String) =
    *       println(s"Received expected error: $$e")
    *     def onTermination(e: Throwable) =
    *       global.reportFailure(e)
    *   })
    * }}}
    *
    * Example equivalent with [[runAsyncAndForget]]:
    *
    * {{{
    *   task.runAsync(BiCallback.empty)
    * }}}
    *
    * Completing a [[scala.concurrent.Promise]]:
    *
    * {{{
    *   import scala.concurrent.Promise
    *
    *   val p = Promise[Either[String, String]]()
    *   task.runAsync(BiCallback.fromPromise(p))
    * }}}
    *
    * $unsafeRun
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @return $cancelableDesc
    */
  @UnsafeBecauseImpure
  final def runAsync(cb: Either[Cause[E], A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsyncOpt(cb)(s, IO.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsync]], but
    * includes the ability to specify [[monix.bio.IO.Options IO.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * Example:
    * {{{
    *   import monix.bio.Cause
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- IOLocal(0)
    *       _     <- local.write(100)
    *       _     <- IO.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of IOLocal via:
    *   implicit val opts = IO.defaultOptions.enableLocalContextPropagation
    *
    *   // Actual execution that depends on these custom options:
    *   task.runAsyncOpt {
    *     case Right(value) =>
    *       println(s"Received: $$value")
    *     case Left(Cause.Termination(e)) =>
    *       global.reportFailure(e)
    *     case Left(Cause.Error(str)) =>
    *       println(s"Received typed error: $$str")
    *   }
    * }}}
    *
    * See [[IO.Options]].
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelableDesc
    */
  @UnsafeBecauseImpure
  def runAsyncOpt(cb: Either[Cause[E], A] => Unit)(implicit s: Scheduler, opts: Options): Cancelable = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      UnsafeCancelUtils.taskToCancelable(runAsyncOptF(cb)(s, opts2))
    }
  }

  /** Triggers the asynchronous execution, returning a `Task[Unit]`
    * (aliased to `CancelToken[Task]` in Cats-Effect) which can
    * cancel the running computation.
    *
    * This is the more potent version of [[runAsync]],
    * because the returned cancelation token is a `Task[Unit]` that
    * can be used to back-pressure on the result of the cancellation
    * token, in case the finalizers are specified as asynchronous
    * actions that are expensive to complete.
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val task = Task("Hello!").bracketCase { str =>
    *     Task(println(str))
    *   } { (_, exitCode) =>
    *     // Finalization
    *     UIO(println(s"Finished via exit code: $$exitCode"))
    *       .delayExecution(3.seconds)
    *   }
    * }}}
    *
    * In this example we have a task with a registered finalizer
    * (via [[bracketCase]]) that takes 3 whole seconds to finish.
    * Via normal `runAsync` the returned cancelation token has no
    * capability to wait for its completion.
    *
    * {{{
    *   import monix.execution.Callback
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val cancel = task.runAsyncF(Callback.empty)
    *
    *   // Triggering `cancel` and we can wait for its completion
    *   for (_ <- cancel.runToFuture) {
    *     // Takes 3 seconds to print
    *     println("Resources were released!")
    *   }
    * }}}
    *
    * WARN: back-pressuring on the completion of finalizers is not
    * always a good idea. Avoid it if you can.
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * NOTE: the `F` suffix comes from `F[_]`, highlighting our usage
    * of `CancelToken[F]` to return a `Task[Unit]`, instead of a
    * plain and side effectful `Cancelable` object.
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @return $cancelTokenDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncF[E1 >: E](cb: Either[Cause[E1], A] => Unit)(implicit s: Scheduler): CancelToken[UIO] =
    runAsyncOptF(cb)(s, IO.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsyncF]], but
    * includes the ability to specify [[monix.bio.IO.Options IO.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runToFutureOpt]] for an example.
    *
    * The returned cancelation token is a `Task[Unit]` that
    * can be used to back-pressure on the result of the cancellation
    * token, in case the finalizers are specified as asynchronous
    * actions that are expensive to complete.
    *
    * See the description of [[runAsyncF]] for an example.
    *
    * WARN: back-pressuring on the completion of finalizers is not
    * always a good idea. Avoid it if you can.
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * NOTE: the `F` suffix comes from `F[_]`, highlighting our usage
    * of `CancelToken[F]` to return a `Task[Unit]`, instead of a
    * plain and side effectful `Cancelable` object.
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelTokenDesc
    */
  @UnsafeBecauseImpure
  def runAsyncOptF[E1 >: E](
    cb: Either[Cause[E], A] => Unit
  )(implicit s: Scheduler, opts: Options): CancelToken[UIO] = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop
        .startLight(this, s, opts2, BiCallback.fromAttempt(cb).asInstanceOf[BiCallback[Any, A]])
    }
  }

  /** Triggers the asynchronous execution of the source task
    * in a "fire and forget" fashion.
    *
    * Starts the execution of the task, but discards any result
    * generated asynchronously and doesn't return any cancelable
    * tokens either. This affords some optimizations — for example
    * the underlying run-loop doesn't need to worry about
    * cancelation. Also the call-site is more clear in intent.
    *
    * Example:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task = Task(println("Hello!"))
    *
    *   // We don't care about the result, we don't care about the
    *   // cancellation token, we just want this thing to run:
    *   task.runAsyncAndForget
    * }}}
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncAndForget(implicit s: Scheduler): Unit =
    runAsyncAndForgetOpt(s, IO.defaultOptions)

  /** Triggers the asynchronous execution in a "fire and forget"
    * fashion, like normal [[runAsyncAndForget]], but includes the
    * ability to specify [[monix.bio.IO.Options IO.Options]] that
    * can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[IO.Options]].
    *
    * See the description of [[runAsyncAndForget]] for an example
    * of running as a "fire and forget".
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    */
  @UnsafeBecauseImpure
  def runAsyncAndForgetOpt(implicit s: Scheduler, opts: IO.Options): Unit =
    runAsyncUncancelableOpt(BiCallback.empty)(s, opts)

  /** Triggers the asynchronous execution of the source task,
    * but runs it in uncancelable mode.
    *
    * This is an optimization over plain [[runAsync]] or [[runAsyncF]] that
    * doesn't give you a cancellation token for cancelling the task. The runtime
    * can thus not worry about keeping state related to cancellation when
    * evaluating it.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   import monix.bio.Cause
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: IO[String, String] =
    *     for {
    *       _ <- IO.sleep(3.seconds)
    *       r <- UIO { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *   // Triggering the task's execution, without receiving any
    *   // cancelation tokens
    *   task.runAsyncUncancelable {
    *     case Right(str) =>
    *       println(s"Received: $$str")
    *     case Left(Cause.Termination(e)) =>
    *       global.reportFailure(e)
    *     case Left(Cause.Error(str)) =>
    *       println(s"Received typed error: $$str")
    *   }
    * }}}
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncUncancelable(cb: Either[Cause[E], A] => Unit)(implicit s: Scheduler): Unit =
    runAsyncUncancelableOpt(cb)(s, IO.defaultOptions)

  /** Triggers the asynchronous execution in uncancelable mode,
    * like [[runAsyncUncancelable]], but includes the ability to
    * specify [[monix.bio.IO.Options IO.Options]] that can modify
    * the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[IO.Options]].
    *
    * This is an optimization over plain [[runAsyncOpt]] or
    * [[runAsyncOptF]] that doesn't give you a cancellation token for
    * cancelling the task. The runtime can thus not worry about
    * keeping state related to cancellation when evaluating it.
    *
    * $callbackDesc
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    */
  @UnsafeBecauseImpure
  def runAsyncUncancelableOpt(cb: Either[Cause[E], A] => Unit)(implicit s: Scheduler, opts: IO.Options): Unit = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop
        .startLight(this, s, opts2, BiCallback.fromAttempt(cb).asInstanceOf[BiCallback[Any, A]], isCancelable = false)
    }
  }

  /** Executes the source until completion, or until the first async
    * boundary, whichever comes first.
    *
    * This operation is mean to be compliant with
    * `cats.effect.Effect.runSyncStep`, but without suspending the
    * evaluation in `IO`.
    *
    * WARNING: This method is a partial function, throwing exceptions
    * in case errors happen immediately (synchronously).
    *
    * Usage sample:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.util._
    *   import scala.util.control.NonFatal
    *
    *   try Task(42).runSyncStep match {
    *     case Right(a) => println("Success: " + a)
    *     case Left(task) =>
    *       task.runToFuture.onComplete {
    *         case Success(a) => println("Async success: " + a)
    *         case Failure(e) => println("Async error: " + e)
    *       }
    *   } catch {
    *     case NonFatal(e) =>
    *       println("Error: " + e)
    *   }
    * }}}
    *
    * Obviously the purpose of this method is to be used for
    * optimizations.
    *
    * $unsafeRun
    *
    * @see [[runSyncUnsafe]], the blocking execution mode that can
    *      only work on top of the JVM.
    *
    * @param s $schedulerDesc
    * @return $runSyncStepReturn
    */
  @UnsafeBecauseImpure
  final def runSyncStep(implicit s: Scheduler): Either[IO[E, A], A] =
    runSyncStepOpt(s, defaultOptions)

  /** A variant of [[runSyncStep]] that takes an implicit
    * [[IO.Options]] from the current scope.
    *
    * This helps in tuning the evaluation model of task.
    *
    * $unsafeRun
    *
    * @see [[runSyncStep]]
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $runSyncStepReturn
    */
  @UnsafeBecauseImpure
  final def runSyncStepOpt(implicit s: Scheduler, opts: Options): Either[IO[E, A], A] = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop.startStep(this, s, opts2)
    }
  }

  /** Evaluates the source task synchronously and returns the result
    * immediately or blocks the underlying thread until the result is
    * ready.
    *
    * The method requires error type to be `Throwable`. Note that it will work for
    * `Nothing` (`UIO`) as well so if you have a different type then you can use
    * `task.attempt.runSyncUnsafe` to receive `Either[E, A]` or any other error handling operator.
    *
    * '''WARNING:''' blocking operations are unsafe and incredibly
    * error prone on top of the JVM. It's a good practice to not block
    * any threads and use the asynchronous `runAsync` methods instead.
    *
    * In general prefer to use the asynchronous [[IO.runAsync]] or
    * [[IO.runToFuture]] and to structure your logic around asynchronous
    * actions in a non-blocking way. But in case you're blocking only once, in
    * `main`, at the "edge of the world" so to speak, then it's OK.
    *
    * Sample:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   Task(42).runSyncUnsafe(3.seconds)
    * }}}
    *
    * This is equivalent with:
    * {{{
    *   import scala.concurrent.Await
    *
    *   Await.result[Int](Task(42).runToFuture, 3.seconds)
    * }}}
    *
    * Some implementation details:
    *
    *  - blocking the underlying thread is done by triggering Scala's
    *    `BlockingContext` (`scala.concurrent.blocking`), just like
    *    Scala's `Await.result`
    *  - the `timeout` is mandatory, just like when using Scala's
    *    `Await.result`, in order to make the caller aware that the
    *    operation is dangerous and that setting a `timeout` is good
    *    practice
    *  - the loop starts in an execution mode that ignores
    *    [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]] or
    *    [[monix.execution.ExecutionModel.AlwaysAsyncExecution AlwaysAsyncExecution]],
    *    until the first asynchronous boundary. This is because we want to block
    *    the underlying thread for the result, in which case preserving
    *    fairness by forcing (batched) async boundaries doesn't do us any good,
    *    quite the contrary, the underlying thread being stuck until the result
    *    is available or until the timeout exception gets triggered.
    *
    * Not supported on top of JavaScript engines and trying to use it
    * with Scala.js will trigger a compile time error.
    *
    * For optimizations on top of JavaScript you can use
    * [[runSyncStep]] instead.
    *
    * $unsafeRun
    *
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param permit $runSyncUnsafePermit
    */
  @UnsafeBecauseImpure
  @UnsafeBecauseBlocking
  final def runSyncUnsafe(
    timeout: Duration = Duration.Inf
  )(implicit s: Scheduler, permit: CanBlock, ev: E <:< Throwable): A =
    runSyncUnsafeOpt(timeout)(s, defaultOptions, permit, ev)

  /** Variant of [[runSyncUnsafe]] that takes a [[IO.Options]]
    * implicitly from the scope in order to tune the evaluation model
    * of the task.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[IOLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of
    * customizing the default [[IO.Options]].
    *
    * $unsafeRun
    *
    * @see [[runSyncUnsafe]]
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @param permit $runSyncUnsafePermit
    */
  @UnsafeBecauseImpure
  @UnsafeBecauseBlocking
  final def runSyncUnsafeOpt(timeout: Duration = Duration.Inf)(implicit
    s: Scheduler,
    opts: Options,
    permit: CanBlock,
    ev: E <:< Throwable
  ): A = {
    /*_*/
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunSyncUnsafe(this, timeout, s, opts2)
    }
    /*_*/
  }

  /** Memoizes (caches) the result of the source task and reuses it on
    * subsequent invocations of `runAsync`.
    *
    * The resulting task will be idempotent, meaning that
    * evaluating the resulting task multiple times will have the
    * same effect as evaluating it once.
    *
    * $memoizeCancel
    *
    * Example:
    * {{{
    *   import scala.concurrent.CancellationException
    *   import scala.concurrent.duration._
    *
    *   val source = Task(1).delayExecution(5.seconds)
    *
    *   // Option 1: trigger error on cancellation
    *   val err = new CancellationException
    *   val cached1 = source.onCancelRaiseError(err).memoize
    *
    *   // Option 2: make it uninterruptible
    *   val cached2 = source.uncancelable.memoize
    * }}}
    *
    * When using [[onCancelRaiseError]] like in the example above, the
    * behavior of `memoize` is to cache the error. If you want the ability
    * to retry errors until a successful value happens, see [[memoizeOnSuccess]].
    *
    * $memoizeUnsafe
    *
    * @see [[memoizeOnSuccess]] for a version that only caches
    *     successful results
    *
    * @return a `Task` that can be used to wait for the memoized value
    */
  @UnsafeBecauseImpure
  final def memoize: IO[E, A] =
    TaskMemoize(this, cacheErrors = true)

  /** Memoizes (cache) the successful result of the source task
    * and reuses it on subsequent invocations of `runAsync`.
    * Thrown exceptions are not cached.
    *
    * The resulting task will be idempotent, but only if the
    * result is successful.
    *
    * $memoizeCancel
    *
    * Example:
    * {{{
    *   import scala.concurrent.CancellationException
    *   import scala.concurrent.duration._
    *
    *   val source = Task(1).delayExecution(5.seconds)
    *
    *   // Option 1: trigger error on cancellation
    *   val err = new CancellationException
    *   val cached1 = source.onCancelRaiseError(err).memoizeOnSuccess
    *
    *   // Option 2: make it uninterruptible
    *   val cached2 = source.uncancelable.memoizeOnSuccess
    * }}}
    *
    * When using [[onCancelRaiseError]] like in the example above, the
    * behavior of `memoizeOnSuccess` is to retry the source on subsequent
    * invocations. Use [[memoize]] if that's not the desired behavior.
    *
    * $memoizeUnsafe
    *
    * @see [[memoize]] for a version that caches both successful
    *     results and failures
    * @return a `Task` that can be used to wait for the memoized value
    */
  @UnsafeBecauseImpure
  final def memoizeOnSuccess: IO[E, A] =
    TaskMemoize(this, cacheErrors = false)

  /** Creates a new [[IO]] that will expose any triggered typed
    * errors from the source.
    */
  final def attempt: UIO[Either[E, A]] =
    FlatMap(this, AttemptTask.asInstanceOf[A => UIO[Either[E, A]]], null)

  /** Replaces the `A` value in `IO[E, A]` with the supplied value.
    */
  final def as[B](value: B): IO[E, B] =
    this.map(_ => value)

  /** Inverse of `attempt`. Creates a new [[IO]] that absorbs `Either`.
    *
    * `IO.now(Right(42)).rethrow <-> IO.now(42)`
    *
    * `IO.now(Left("error")).rethrow <-> IO.raiseError("error")`
    */
  final def rethrow[E1 >: E, B](implicit ev: A <:< Either[E1, B]): IO[E1, B] =
    this.flatMap(fromEither(_))

  /** Runs this task first and then, when successful, the given task.
    * Returns the result of the given task.
    *
    * Example:
    * {{{
    *   val combined = Task{println("first"); "first"} >> Task{println("second"); "second"}
    *   // Prints "first" and then "second"
    *   // Result value will be "second"
    * }}}
    */
  final def >>[E1 >: E, B](tb: => IO[E1, B]): IO[E1, B] =
    this.flatMap(_ => tb)

  /** Runs this IO first and then, when successful, the given IO.
    * Returns the result of the given IO.
    *
    * Example:
    * {{{
    *   val combined = IO{println("first"); "first"} *> IO{println("second"); "second"}
    *   // Prints "first" and then "second"
    *   // Result value will be "second"
    * }}}
    *
    * As this method is strict, it can lead to an infinite loop / stack overflow for self-referring tasks.
    * @see [[>>]] for the version with a non-strict parameter
    */
  @inline final def *>[E1 >: E, B](tb: IO[E1, B]): IO[E1, B] =
    this.flatMap(_ => tb)

  /** Runs this IO first and then, when successful, the given IO.
    * Returns the result of this IO.
    *
    * Example:
    * {{{
    *   val combined = IO{println("first"); "first"} <* IO{println("second"); "second"}
    *   // Prints "first" and then "second"
    *   // Result value will be "first"
    * }}}
    *
    * As this method is strict, it can lead to an infinite loop / stack overflow for self-referring tasks.
    */
  @inline final def <*[E1 >: E, B](tb: IO[E1, B]): IO[E1, A] =
    this.flatMap(a => tb.map(_ => a))

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline.
    *
    * The `IO` will be returned to the default `Scheduler` to
    * reschedule the rest of its execution.
    *
    * Consider the following example:
    *
    * {{{
    *   import monix.execution.ExecutionModel.SynchronousExecution
    *   import monix.execution.Scheduler
    *
    *   val s = Scheduler.singleThread("example-scheduler").withExecutionModel(SynchronousExecution)
    *
    *   val source1 = IO(println("task 1")).loopForever
    *   val source2 = IO(println("task 2")).loopForever
    *
    *   // Will keep printing only "task 1" or "task 2"
    *   // depending on which one was scheduled first
    *   IO.parZip2(source1, source2)
    * }}}
    *
    * We might expect that both `source1` and `source2` would execute
    * concurrently but since we are using only 1 thread with
    * [[monix.execution.ExecutionModel.SynchronousExecution SynchronousExecution]]
    * execution model, one of them will be scheduled first and then run forever.
    *
    * To prevent this behavior we could introduce asynchronous boundary
    * after each iteration, i.e.:
    *
    * {{{
    *   val source3 = IO(println("task 1")).asyncBoundary.loopForever
    *   val source4 = IO(println("task 2")).asyncBoundary.loopForever
    *
    *   // Will keep printing "task 1" and "task 2" alternately.
    *   IO.parZip2(source3, source4)
    * }}}
    *
    * A lot of asynchronous boundaries can lead to unnecessary overhead so in the
    * majority of cases it is enough to use the default `ExecutionModel` which
    * introduces asynchronous boundaries between `flatMap` periodically on its own.
    *
    * Likelihood that different tasks are able to advance is called `fairness`.
    *
    * @see [[IO.executeOn]] for a way to override the default `Scheduler`
    */
  final def asyncBoundary: IO[E, A] =
    flatMap(a => IO.shift.map(_ => a))

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline, making processing to jump on
    * the given [[monix.execution.Scheduler Scheduler]] (until the
    * next async boundary).
    *
    * Consider the following example:
    *
    * {{{
    *   import monix.execution.Scheduler
    *
    *   implicit val s = Scheduler.global
    *   val io = Scheduler.io()
    *
    *   val source = IO(1) // s
    *     .asyncBoundary(io)
    *     .flatMap(_ => IO(2)) // io
    *     .flatMap(_ => IO(3)) // io
    *     .asyncBoundary
    *     .flatMap(_ => IO(4))  // s
    * }}}
    *
    * If `Scheduler s` is passed implicitly when running the `IO`, `IO(1)`
    * will be executed there. Then it will switch to `io` for `IO(2)` and `IO(3)`.
    * `asyncBoundary` without any arguments returns to the default `Scheduler` so `IO(4)`
    * will be executed there.
    *
    * @param s is the scheduler triggering the asynchronous boundary
    */
  final def asyncBoundary(s: Scheduler): IO[E, A] =
    flatMap(a => IO.shift(s).map(_ => a))

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`.
    *
    * The `bracket` operation is the equivalent of the
    * `try {} catch {} finally {}` statements from mainstream languages.
    *
    * The `bracket` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * If an exception is raised, then `bracket` will re-raise the exception
    * ''after'' performing the `release`. If the resulting task gets cancelled,
    * then `bracket` will still perform the `release`, but the yielded task
    * will be non-terminating (equivalent with [[IO.never]]).
    *
    * Example:
    *
    * {{{
    *   import java.io._
    *
    *   def readFile(file: File): Task[String] = {
    *     // Opening a file handle for reading text
    *     val acquire = Task.eval(new BufferedReader(
    *       new InputStreamReader(new FileInputStream(file), "utf-8")
    *     ))
    *
    *     acquire.bracket { in =>
    *       // Usage part
    *       Task.eval {
    *         // Yes, ugly Java, non-FP loop;
    *         // side-effects are suspended though
    *         var line: String = null
    *         val buff = new StringBuilder()
    *         do {
    *           line = in.readLine()
    *           if (line != null) buff.append(line)
    *         } while (line != null)
    *         buff.toString()
    *       }
    *     } { in =>
    *       // The release part
    *       UIO(in.close())
    *     }
    *   }
    * }}}
    *
    * Note that in case of cancellation the underlying implementation cannot
    * guarantee that the computation described by `use` doesn't end up
    * executed concurrently with the computation from `release`. In the example
    * above that ugly Java loop might end up reading from a `BufferedReader`
    * that is already closed due to the task being cancelled, thus triggering
    * an error in the background with nowhere to go but in
    * [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]].
    *
    * In this particular example, given that we are just reading from a file,
    * it doesn't matter. But in other cases it might matter, as concurrency
    * on top of the JVM when dealing with I/O might lead to corrupted data.
    *
    * For those cases you might want to do synchronization (e.g. usage of
    * locks and semaphores) and you might want to use [[bracketE]], the
    * version that allows you to differentiate between normal termination
    * and cancellation.
    *
    * $bracketErrorNote
    *
    * @see [[bracketCase]] and [[bracketE]]
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by the task returned
    *        by this `bracket` function
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs to be released
    */
  final def bracket[E1 >: E, B](use: A => IO[E1, B])(release: A => UIO[Unit]): IO[E1, B] =
    bracketCase(use)((a, _) => release(a))

  /** Returns a new task that treats the source task as the
    * acquisition of a resource, which is then exploited by the `use`
    * function and then `released`, with the possibility of
    * distinguishing between normal termination and cancelation, such
    * that an appropriate release of resources can be executed.
    *
    * The `bracketCase` operation is the equivalent of
    * `try {} catch {} finally {}` statements from mainstream languages
    * when used for the acquisition and release of resources.
    *
    * The `bracketCase` operation installs the necessary exception handler
    * to release the resource in the event of an exception being raised
    * during the computation, or in case of cancelation.
    *
    * In comparison with the simpler [[bracket]] version, this one
    * allows the caller to differentiate between normal termination,
    * termination in error and cancelation via an `ExitCase`
    * parameter.
    *
    * @see [[bracket]] and [[bracketE]]
    * @param use is a function that evaluates the resource yielded by
    *        the source, yielding a result that will get generated by
    *        this function on evaluation
    * @param release is a function that gets called after `use`
    *        terminates, either normally or in error, or if it gets
    *        canceled, receiving as input the resource that needs that
    *        needs release, along with the result of `use`
    *        (cancelation, error or successful result)
    */
  final def bracketCase[E1 >: E, B](use: A => IO[E1, B])(release: (A, ExitCase[Cause[E1]]) => UIO[Unit]): IO[E1, B] =
    TaskBracket.exitCase(this, use, release)

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`, with
    * the possibility of distinguishing between normal termination and cancellation,
    * such that an appropriate release of resources can be executed.
    *
    * The `bracketE` operation is the equivalent of `try {} catch {} finally {}`
    * statements from mainstream languages.
    *
    * The `bracketE` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * In comparison with the simpler [[bracket]] version, this one allows the
    * caller to differentiate between normal termination and cancellation.
    *
    * The `release` function receives as input:
    *
    *  - `Left(None)` in case of cancellation
    *  - `Left(Some(error))` in case `use` terminated with an error
    *  - `Right(b)` in case of success
    *
    * $bracketErrorNote
    *
    * @see [[bracket]] and [[bracketCase]]
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by this function on
    *        evaluation
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs that needs release, along with
    *        the result of `use` (cancellation, error or successful result)
    */
  final def bracketE[E1 >: E, B](
    use: A => IO[E1, B]
  )(release: (A, Either[Option[Cause[E1]], B]) => UIO[Unit]): IO[E1, B] =
    TaskBracket.either(this, use, release)

  /** Executes the given `finalizer` when the source is finished,
    * either in success or in error, or if canceled.
    *
    * This variant of [[guaranteeCase]] evaluates the given `finalizer`
    * regardless of how the source gets terminated:
    *
    *  - normal completion
    *  - completion in error
    *  - cancellation
    *
    * As best practice, it's not a good idea to release resources
    * via `guaranteeCase` in polymorphic code. Prefer [[bracket]]
    * for the acquisition and release of resources.
    *
    * @see [[guaranteeCase]] for the version that can discriminate
    *      between termination conditions
    * @see [[bracket]] for the more general operation
    */
  final def guarantee(finalizer: UIO[Unit]): IO[E, A] =
    guaranteeCase(_ => finalizer)

  /** Executes the given `finalizer` when the source is finished,
    * either in success or in error, or if canceled, allowing
    * for differentiating between exit conditions.
    *
    * This variant of [[guarantee]] injects an ExitCase in
    * the provided function, allowing one to make a difference
    * between:
    *
    *  - normal completion
    *  - completion in error
    *  - cancellation
    *
    * As best practice, it's not a good idea to release resources
    * via `guaranteeCase` in polymorphic code. Prefer [[bracketCase]]
    * for the acquisition and release of resources.
    *
    * @see [[guarantee]] for the simpler version
    * @see [[bracketCase]] for the more general operation
    */
  final def guaranteeCase(finalizer: ExitCase[Cause[E]] => UIO[Unit]): IO[E, A] =
    TaskBracket.guaranteeCase(this, finalizer)

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    *
    * In this example we're printing to standard output, but before
    * doing that we're introducing a 3 seconds delay:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task(println("Hello!"))
    *     .delayExecution(3.seconds)
    * }}}
    *
    * This operation is also equivalent with:
    *
    * {{{
    *   Task.sleep(3.seconds).flatMap(_ => Task(println("Hello!")))
    * }}}
    *
    * See [[IO.sleep]] for the operation that describes the effect
    * and [[IO.delayResult]] for the version that evaluates the
    * task on time, but delays the signaling of the result.
    *
    * @param timespan is the time span to wait before triggering
    *                 the evaluation of the task
    */
  final def delayExecution(timespan: FiniteDuration): IO[E, A] =
    IO.sleep(timespan).flatMap(_ => this)

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    *
    * See [[delayExecution]] for delaying the evaluation of the
    * task with the specified duration. The [[delayResult]] operation
    * is effectively equivalent with:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task(1 + 1)
    *     .flatMap(a => Task.now(a).delayExecution(3.seconds))
    * }}}
    *
    * Or if we are to use the [[IO.sleep]] describing just the
    * effect, this operation is equivalent with:
    *
    * {{{
    *   Task(1 + 1).flatMap(a => Task.sleep(3.seconds).map(_ => a))
    * }}}
    *
    * Thus in this example 3 seconds will pass before the result
    * is being generated by the source, plus another 5 seconds
    * before it is finally emitted:
    *
    * {{{
    *   Task(1 + 1)
    *     .delayExecution(3.seconds)
    *     .delayResult(5.seconds)
    * }}}
    *
    * @param timespan is the time span to sleep before signaling
    *        the result, but after the evaluation of the source
    */
  final def delayResult(timespan: FiniteDuration): IO[E, A] =
    flatMap(a => IO.sleep(timespan).map(_ => a))

  /** Overrides the default [[monix.execution.Scheduler Scheduler]],
    * possibly forcing an asynchronous boundary before execution
    * (if `forceAsync` is set to `true`, the default).
    *
    * When a `Task` is executed with [[IO.runAsync]] or [[IO.runToFuture]],
    * it needs a `Scheduler`, which is going to be injected in all
    * asynchronous tasks processed within the `flatMap` chain,
    * a `Scheduler` that is used to manage asynchronous boundaries
    * and delayed execution.
    *
    * This scheduler passed in `runAsync` is said to be the "default"
    * and `executeOn` overrides that default.
    *
    * {{{
    *   import monix.execution.Scheduler
    *   import java.io.{BufferedReader, FileInputStream, InputStreamReader}
    *
    *   /** Reads the contents of a file using blocking I/O. */
    *   def readFile(path: String): Task[String] = Task.eval {
    *     val in = new BufferedReader(
    *       new InputStreamReader(new FileInputStream(path), "utf-8"))
    *
    *     val buffer = new StringBuffer()
    *     var line: String = null
    *     do {
    *       line = in.readLine()
    *       if (line != null) buffer.append(line)
    *     } while (line != null)
    *
    *     buffer.toString
    *   }
    *
    *   // Building a Scheduler meant for blocking I/O
    *   val io = Scheduler.io()
    *
    *   // Building the Task reference, specifying that `io` should be
    *   // injected as the Scheduler for managing async boundaries
    *   readFile("path/to/file").executeOn(io, forceAsync = true)
    * }}}
    *
    * In this example we are using [[IO.eval]], which executes the
    * given `thunk` immediately (on the current thread and call stack).
    *
    * By calling `executeOn(io)`, we are ensuring that the used
    * `Scheduler` (injected in [[IO.cancelable0]])
    * will be `io`, a `Scheduler` that we intend to use for blocking
    * I/O actions. And we are also forcing an asynchronous boundary
    * right before execution, by passing the `forceAsync` parameter as
    * `true` (which happens to be the default value).
    *
    * Thus, for our described function that reads files using Java's
    * blocking I/O APIs, we are ensuring that execution is entirely
    * managed by an `io` scheduler, executing that logic on a thread
    * pool meant for blocking I/O actions.
    *
    * Note that in case `forceAsync = false`, then the invocation will
    * not introduce any async boundaries of its own and will not
    * ensure that execution will actually happen on the given
    * `Scheduler`, that depending of the implementation of the `Task`.
    * For example:
    *
    * {{{
    *   Task.eval("Hello, " + "World!")
    *     .executeOn(io, forceAsync = false)
    * }}}
    *
    * The evaluation of this task will probably happen immediately
    * (depending on the configured
    * [[monix.execution.ExecutionModel ExecutionModel]]) and the
    * given scheduler will probably not be used at all.
    *
    * However in case we would use [[IO.evalAsync]], which ensures
    * that execution of the provided thunk will be async, then
    * by using `executeOn` we'll indeed get a logical fork on
    * the `io` scheduler:
    *
    * {{{
    *   IO.evalAsync("Hello, " + "World!").executeOn(io, forceAsync = false)
    * }}}
    *
    * Also note that overriding the "default" scheduler can only
    * happen once, because it's only the "default" that can be
    * overridden.
    *
    * Something like this won't have the desired effect:
    *
    * {{{
    *   val io1 = Scheduler.io()
    *   val io2 = Scheduler.io()
    *
    *   Task(1 + 1).executeOn(io1).executeOn(io2)
    * }}}
    *
    * In this example the implementation of `task` will receive
    * the reference to `io1` and will use it on evaluation, while
    * the second invocation of `executeOn` will create an unnecessary
    * async boundary (if `forceAsync = true`) or be basically a
    * costly no-op. This might be confusing but consider the
    * equivalence to these functions:
    *
    * {{{
    *   import scala.concurrent.ExecutionContext
    *
    *   val io11 = Scheduler.io()
    *   val io22 = Scheduler.io()
    *
    *   def sayHello(ec: ExecutionContext): Unit =
    *     ec.execute(new Runnable {
    *       def run() = println("Hello!")
    *     })
    *
    *   def sayHello2(ec: ExecutionContext): Unit =
    *     // Overriding the default `ec`!
    *     sayHello(io11)
    *
    *   def sayHello3(ec: ExecutionContext): Unit =
    *     // Overriding the default no longer has the desired effect
    *     // because sayHello2 is ignoring it!
    *     sayHello2(io22)
    * }}}
    *
    * @param s is the [[monix.execution.Scheduler Scheduler]] to use
    *        for overriding the default scheduler and for forcing
    *        an asynchronous boundary if `forceAsync` is `true`
    * @param forceAsync indicates whether an asynchronous boundary
    *        should be forced right before the evaluation of the
    *        `Task`, managed by the provided `Scheduler`
    * @return a new `Task` that mirrors the source on evaluation,
    *         but that uses the provided scheduler for overriding
    *         the default and possibly force an extra asynchronous
    *         boundary on execution
    */
  final def executeOn(s: Scheduler, forceAsync: Boolean = true): IO[E, A] =
    TaskExecuteOn(this, s, forceAsync)

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in
    * [[IO.runAsync]] or [[IO.runToFuture]].
    *
    * This operation is equivalent with:
    *
    * {{{
    *   Task.shift.flatMap(_ => Task(1 + 1))
    *
    *   // ... or ...
    *
    *   import cats.syntax.all._
    *
    *   Task.shift *> Task(1 + 1)
    * }}}
    *
    * The [[monix.execution.Scheduler Scheduler]] used for scheduling
    * the async boundary will be the default, meaning the one used to
    * start the run-loop in `runAsync`.
    */
  final def executeAsync: IO[E, A] =
    IO.shift.flatMap(_ => this)

  /** Returns a new task that will execute the source with a different
    * [[monix.execution.ExecutionModel ExecutionModel]].
    *
    * This allows fine-tuning the options injected by the scheduler
    * locally. Example:
    *
    * {{{
    *   import monix.execution.ExecutionModel.AlwaysAsyncExecution
    *   Task(1 + 1).executeWithModel(AlwaysAsyncExecution)
    * }}}
    *
    * @param em is the
    *        [[monix.execution.ExecutionModel ExecutionModel]]
    *        with which the source will get evaluated on `runAsync`
    */
  final def executeWithModel(em: ExecutionModel): IO[E, A] =
    TaskExecuteWithModel(this, em)

  /** Returns a new task that will execute the source with a different
    * set of [[IO.Options Options]].
    *
    * This allows fine-tuning the default options. Example:
    *
    * {{{
    *   Task(1 + 1).executeWithOptions(_.enableAutoCancelableRunLoops)
    * }}}
    *
    * @param f is a function that takes the source's current set of
    *          [[IO.Options options]] and returns a modified set of
    *          options that will be used to execute the source
    *          upon `runAsync`
    */
  final def executeWithOptions(f: Options => Options): IO[E, A] =
    TaskExecuteWithOptions(this, f)

  /** Returns a failed projection of this task.
    *
    * The failed projection is a `IO` holding a value of type `E`,
    * emitting the error yielded by the source, in case the source fails,
    * otherwise if the source succeeds the result will fail with a
    * `NoSuchElementException`.
    */
  final def failed: UIO[E] =
    FlatMap(this, IO.Failed.asInstanceOf[StackFrame[E, A, UIO[E]]], null)

  /** Creates a new IO by swapping the error and value parameters. This allows you to
    * work with the error in a right-biased context, allowing you to apply a series of
    * operations that may depend on the error thrown by this task.
    *
    * Example:
    * {{{
    *   import java.time.Instant
    *
    *   case class ErrorA(i: Int)
    *   case class ErrorB(errA: ErrorA, createdAt: Instant)
    *
    *   def mapToErrorB(error: ErrorA): Task[ErrorB] = ???
    *   def logToStdErr(error: ErrorB): Task[ErrorB] = ???
    *   def logErrorToFile(error: ErrorB): Task[ErrorB] = ???
    *
    *   val f1 = IO.raiseError(ErrorA(500))
    *
    *   for {
    *     errorA <- f1.flip
    *     errorB <- mapToErrorB(errorA)
    *     _      <- logToStdErr(errorB)
    *     _      <- logErrorToFile(errorB)
    *   } yield ()
    *
    * }}}
    */
  final def flip: IO[A, E] = {
    this.redeemWith(IO.now, IO.raiseError)
  }

  /** *
    * This function implements a common pattern with [[flip]] in that it returns the already flipped task
    * and allows applying a series of operations that may depend on the error thrown by this task, before
    * flipping the error and value parameters back.
    *
    * Example:
    * {{{
    *   import java.time.Instant
    *
    *   case class ErrorA(i: Int)
    *   case class ErrorB(errA: ErrorA, createdAt: Instant)
    *
    *   def mapToErrorB(error: ErrorA): Task[ErrorB] = ???
    *   def logToStdErr(error: ErrorB): Task[ErrorB] = ???
    *   def logErrorToFile(error: ErrorB): Task[ErrorB] = ???
    *
    *   val f1 = IO.raiseError(ErrorA(500)).flipWith { f1 =>
    *     for {
    *       errorA <- f1
    *       errorB <- mapToErrorB(errorA)
    *       _      <- logToStdErr(errorB)
    *       _      <- logErrorToFile(errorB)
    *     } yield errorB
    *   }
    *
    * }}}
    */
  final def flipWith[E1, A1](f: IO[A, E] => IO[A1, E1]): IO[E1, A1] =
    f(this.flip).flip

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  final def flatMap[E1 >: E, B](f: A => IO[E1, B]): IO[E1, B] = {
    val trace = IOTracing.getTrace(f.getClass)
    FlatMap(this, f, trace)
  }

  /** Creates a new `IO` that will run a provided effect on the success
    * and return the original value.
    */
  final def tapEval[E1 >: E, B](f: A => IO[E1, B]): IO[E1, A] = {
    this.flatMap { a =>
      f(a).map(_ => a)
    }
  }

  /**  Describes flatMap-driven loops, as an alternative to recursive functions.
    *
    * Sample:
    *
    * {{{
    *   import scala.util.Random
    *
    *   val random = IO(Random.nextInt())
    *   val loop = random.flatMapLoop(Vector.empty[Int]) { (a, list, continue) =>
    *     val newList = list :+ a
    *     if (newList.length < 5)
    *       continue(newList)
    *     else
    *       IO.now(newList)
    *   }
    * }}}
    *
    * @param seed initializes the result of the loop
    * @param f is the function that updates the result
    *        on each iteration, returning a `IO`.
    * @return a new [[IO]] that contains the result of the loop.
    */
  final def flatMapLoop[E1 >: E, S](seed: S)(f: (A, S, S => IO[E1, S]) => IO[E1, S]): IO[E1, S] =
    this.flatMap { a =>
      f(a, seed, flatMapLoop[E1, S](_)(f))
    }

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  final def flatten[E1 >: E, B](implicit ev: A <:< IO[E1, B]): IO[E1, B] =
    flatMap(a => a)

  /** Returns a new task that upon evaluation will execute the given
    * function for the generated element, transforming the source into
    * a `IO[E, Unit]`.
    *
    * Similar in spirit with normal [[foreach]], but lazy, as
    * obviously nothing gets executed at this point.
    */
  final def foreachL(f: A => Unit): IO[E, Unit] =
    this.map { a =>
      f(a); ()
    }

  /** Triggers the evaluation of the source, executing the given
    * function for the generated element.
    *
    * The application of this function has strict behavior, as the
    * task is immediately executed.
    *
    * Exceptions in `f` are reported using provided (implicit) Scheduler
    */
  @UnsafeBecauseImpure
  final def foreach(f: Either[E, A] => Unit)(implicit s: Scheduler): Unit =
    attempt.runToFuture.foreach(f)

  /** Returns a new `Task` that repeatedly executes the source as long
    * as it continues to succeed. It never produces a terminal value.
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task.eval(println("Tick!"))
    *     .delayExecution(1.second)
    *     .loopForever
    * }}}
    */
  final def loopForever: IO[E, Nothing] =
    flatMap(_ => this.loopForever)

  /** Start asynchronous execution of the source suspended in the `IO` context,
    * running it in the background and discarding the result.
    *
    * Similar to [[start]] after mapping result to Unit. Below law holds:
    *
    * `bio.startAndForget <-> bio.start.map(_ => ())`
    */
  final def startAndForget: UIO[Unit] =
    IOStartAndForget(this)

  /** Returns a new `IO` in which `f` is scheduled to be run on
    * completion. This would typically be used to release any
    * resources acquired by this `IO`.
    *
    * The returned `IO` completes when both the source and the task
    * returned by `f` complete.
    *
    * NOTE: The given function is only called when the task is
    * complete.  However the function does not get called if the task
    * gets canceled.  Cancellation is a process that's concurrent with
    * the execution of a task and hence needs special handling.
    *
    * See [[doOnCancel]] for specifying a callback to call on
    * canceling a task.
    */
  final def doOnFinish(f: Option[Cause[E]] => UIO[Unit]): IO[E, A] =
    this.guaranteeCase {
      case ExitCase.Completed => f(None)
      case ExitCase.Canceled => IO.unit
      case ExitCase.Error(e) => f(Some(e))
    }

  /** Returns a new `Task` that will mirror the source, but that will
    * execute the given `callback` if the task gets canceled before
    * completion.
    *
    * This only works for premature cancellation. See [[doOnFinish]]
    * for triggering callbacks when the source finishes.
    *
    * @param callback is the callback to execute if the task gets
    *        canceled prematurely
    */
  final def doOnCancel(callback: UIO[Unit]): IO[E, A] =
    TaskDoOnCancel(this, callback)

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  final def materialize(implicit ev: E <:< Throwable): UIO[Try[A]] =
    FlatMap(this, MaterializeTask.asInstanceOf[A => UIO[Try[A]]], null)

  /** Dematerializes the source's result from a `Try`. */
  final def dematerialize[B](implicit evE: E <:< Nothing, evA: A <:< Try[B]): Task[B] =
    this.asInstanceOf[UIO[Try[B]]].flatMap(IO.fromTry)

  /** Returns a new task that mirrors the source task for normal termination,
    * but that triggers the given error on cancellation.
    *
    * Normally tasks that are cancelled become non-terminating.
    * Here's an example of a cancelable task:
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *   import java.util.concurrent.TimeoutException
    *
    *   val tenSecs = IO.sleep(10.seconds)
    *   val task1 = tenSecs.start.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => tenSecs)
    *   }
    *
    *   task1.timeoutWith(10.seconds, new TimeoutException())
    *   //=> TimeoutException
    * }}}
    *
    * In general you can expect cancelable tasks to become non-terminating on
    * cancellation.
    *
    * This `onCancelRaiseError` operator transforms a task that would yield
    * [[IO.never]] on cancellation into one that yields [[IO.raiseError]].
    *
    * Example:
    * {{{
    *   import java.util.concurrent.CancellationException
    *
    *   val anotherTenSecs = IO.sleep(10.seconds)
    *     .onCancelRaiseError(new CancellationException)
    *
    *   val task2 = anotherTenSecs.start.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => anotherTenSecs)
    *   }
    *
    *   task2
    *   // => CancellationException
    * }}}
    */
  final def onCancelRaiseError[E1 >: E](e: E1): IO[E1, A] =
    TaskCancellation.raiseError(this, e)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  final def onErrorRecoverWith[E1 >: E, B >: A](pf: PartialFunction[E, IO[E1, B]]): IO[E1, B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, raiseConstructor[E]))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  final def onErrorHandleWith[E1, B >: A](f: E => IO[E1, B]): IO[E1, B] =
    FlatMap(this, new StackFrame.ErrorHandler(f, nowConstructor), null)

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  final def onErrorFallbackTo[E1, B >: A](that: IO[E1, B]): IO[E1, B] =
    onErrorHandleWith(_ => that)

  /** Given a predicate function, keep retrying the
    * IO until the function returns true.
    */
  final def restartUntil(p: A => Boolean): IO[E, A] =
    this.flatMap(a => if (p(a)) now(a) else this.restartUntil(p))

  /** Returns a new `Task` that applies the mapping function to
    * the element emitted by the source.
    *
    * Can be used for specifying a (lazy) transformation to the result
    * of the source.
    *
    * This equivalence with [[flatMap]] always holds:
    *
    * `fa.map(f) <-> fa.flatMap(x => Task.pure(f(x)))`
    */
  final def map[B](f: A => B): IO[E, B] = {
    val trace = IOTracing.getTrace(f.getClass)
    Map(this, f, trace)
  }

  /** Creates a new task that will run a provided effect in case of a typed error
    * and raise the original error in case the provided function is successful.
    *
    * Example:
    *  {{{
    *    import monix.bio.IO
    *
    *    // will result in Left("Error") and print the error to console
    *    IO.raiseError("Error1").tapError(err => IO.evalTotal(println(err)))
    *  }}}
    *
    * If provided function returns an error then the resulting task will raise that error instead.
    *
    * Example:
    *  {{{
    *    // will result in Left("Error2")
    *    IO.raiseError("Error1").tapError(err => IO.raiseError("Error2"))
    *  }}}
    */
  final def tapError[E1 >: E, B](f: E => IO[E1, B]): IO[E1, A] =
    this.onErrorHandleWith(e => f(e).flatMap(_ => IO.raiseError(e)))

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be maxRetries + 1.
    */
  final def onErrorRestart(maxRetries: Long): IO[E, A] =
    this.onErrorHandleWith { error =>
      if (maxRetries > 0) this.onErrorRestart(maxRetries - 1)
      else raiseError(error)
    }

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds, or until the given
    * predicate returns `false`.
    *
    * In this sample we retry for as long as the error is a `TimeoutException`:
    * {{{
    *   import scala.concurrent.TimeoutException
    *
    *   Task("some long call that may timeout").onErrorRestartIf {
    *     case _: TimeoutException => true
    *     case _ => false
    *   }
    * }}}
    *
    * @param p is the predicate that is executed if an error is thrown and
    *        that keeps restarting the source for as long as it returns `true`
    */
  final def onErrorRestartIf(p: E => Boolean): IO[E, A] =
    this.onErrorHandleWith(ex => if (p(ex)) this.onErrorRestartIf(p) else raiseError(ex))

  /** On error restarts the source with a customizable restart loop.
    *
    * This operation keeps an internal `state`, with a start value, an internal
    * state that gets evolved and based on which the next step gets decided,
    * e.g. should it restart, maybe with a delay, or should it give up and
    * re-throw the current error.
    *
    * Example that implements a simple retry policy that retries for a maximum
    * of 10 times before giving up; also introduce a 1 second delay before
    * each retry is executed:
    *
    * {{{
    *   import scala.util.Random
    *   import scala.concurrent.duration._
    *
    *   val task = Task {
    *     if (Random.nextInt(20) > 10)
    *       throw new RuntimeException("boo")
    *     else 78
    *   }
    *
    *   task.onErrorRestartLoop(10) { (err, maxRetries, retry) =>
    *     if (maxRetries > 0)
    *       // Next retry please; but do a 1 second delay
    *       retry(maxRetries - 1).delayExecution(1.second)
    *     else
    *       // No retries left, rethrow the error
    *       Task.raiseError(err)
    *   }
    * }}}
    *
    * A more complex exponential back-off sample:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   // Keeps the current state, indicating the restart delay and the
    *   // maximum number of retries left
    *   final case class Backoff(maxRetries: Int, delay: FiniteDuration)
    *
    *   // Restarts for a maximum of 10 times, with an initial delay of 1 second,
    *   // a delay that keeps being multiplied by 2
    *   task.onErrorRestartLoop(Backoff(10, 1.second)) { (err, state, retry) =>
    *     val Backoff(maxRetries, delay) = state
    *     if (maxRetries > 0)
    *       retry(Backoff(maxRetries - 1, delay * 2)).delayExecution(delay)
    *     else
    *       // No retries left, rethrow the error
    *       Task.raiseError(err)
    *   }
    * }}}
    *
    * The given function injects the following parameters:
    *
    *  1. `error` reference that was thrown
    *  2. the current `state`, based on which a decision for the retry is made
    *  3. `retry: S => IO[E, B]` function that schedules the next retry
    *
    * @param initial is the initial state used to determine the next on error
    *        retry cycle
    * @param f is a function that injects the current error, state, a
    *        function that can signal a retry is to be made and returns
    *        the next task
    */
  final def onErrorRestartLoop[S, E1 >: E, B >: A](initial: S)(f: (E1, S, S => IO[E1, B]) => IO[E1, B]): IO[E1, B] =
    onErrorHandleWith(err => f(err, initial, state => (this: IO[E1, B]).onErrorRestartLoop(state)(f)))

  /** Returns a new `IO` that applies the mapping function `fa` to the
    * success channel and `fe` to the error channel.
    */
  final def bimap[E1, B](fe: E => E1, fa: A => B): IO[E1, B] = {
    val trace = IOTracing.getTrace(fa.getClass)
    IO.FlatMap(this, IO.Bimap(fe, fa), trace)
  }

  /** Creates a new task that will will transform errors
    * using supplied function `f`.
    *
    * Example:
    * {{{
    *   import java.time.Instant
    *
    *   case class ErrorA(i: Int)
    *   case class ErrorB(errA: ErrorA, createdAt: Instant)
    *
    *   val task1: IO[ErrorA, String] = IO.raiseError(ErrorA(10))
    *   val task2: IO[ErrorB, String] = task1.mapError(errA => ErrorB(errA, Instant.now()))
    * }}}
    */
  final def mapError[E1](f: E => E1): IO[E1, A] = {
    val trace = IOTracing.getTrace(f.getClass)
    IO.FlatMap(this, IO.MapError[E, E1, A](f), trace)
  }

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    *
    * See [[onErrorRecover]] for the version that takes a partial function.
    */
  final def onErrorHandle[U >: A](f: E => U): UIO[U] =
    onErrorHandleWith(f.andThen(nowConstructor))

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  final def onErrorRecover[E1 >: E, U >: A](pf: PartialFunction[E, U]): IO[E1, U] =
    onErrorRecoverWith(pf.andThen(nowConstructor))

  /** Start execution of the source suspended in the `Task` context.
    *
    * This can be used for non-deterministic / concurrent execution.
    * The following code is more or less equivalent with
    * [[IO.parMap2]] (minus the behavior on error handling and
    * cancellation):
    *
    * {{{
    *   def par2[A, B](ta: Task[A], tb: Task[B]): Task[(A, B)] =
    *     for {
    *       fa <- ta.start
    *       fb <- tb.start
    *        a <- fa.join
    *        b <- fb.join
    *     } yield (a, b)
    * }}}
    *
    * Note in such a case usage of doctodo Task.parMap2 parMap2
    * (and doctodo Task.parMap3 parMap3, etc.) is still recommended
    * because of behavior on error and cancellation — consider that
    * in the example above, if the first task finishes in error,
    * the second task doesn't get cancelled.
    *
    * This operation forces an asynchronous boundary before execution
    */
  final def start: UIO[Fiber[E @uV, A @uV]] =
    TaskStart.forked(this)

  /** Generic conversion of `Task` to any data type for which there's
    * a [[IOLift]] implementation available.
    *
    * Supported data types:
    *
    *  - [[https://typelevel.org/cats-effect/datatypes/io.html cats.effect.IO]]
    *  - any data type implementing [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]]
    *  - any data type implementing [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]]
    *  - any data type implementing [[https://typelevel.org/cats-effect/typeclasses/liftio.html cats.effect.LiftIO]]
    *  - `monix.reactive.Observable`
    *  - `monix.tail.Iterant`
    *
    * This conversion guarantees:
    *
    *  - referential transparency
    *  - similar runtime characteristics (e.g. if the source doesn't
    *    block threads on evaluation, then the result shouldn't block
    *    threads either)
    *  - interruptibility, if the target data type is cancelable
    *
    * Note that this method is only applicable when the typed error `E`
    * is also a `Throwable`, or when the source task is an unexceptional
    * one (i.e. it is a `UIO`). If you need a conversion from `E` into
    * a `Throwable`, take a look at [[mapError]] or [[onErrorHandleWith]].
    * If you need a conversion into a `UIO`, take a look at [[attempt]],
    * [[materialize]] or [[onErrorHandle]].
    *
    * Sample:
    *
    * {{{
    *   import cats.effect.{IO => CIO}
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   IO.eval(println("Hello!"))
    *     .delayExecution(5.seconds)
    *     .to[CIO]
    * }}}
    */
  final def to[F[_]](implicit F: IOLift[F], ev: E <:< Throwable): F[A @uV] =
    F(this.asInstanceOf[Task[A]])

  /** Converts the source task into any data type that implements
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html Concurrent]].
    *
    * Example:
    *
    * {{{
    *   import cats.effect.{IO => CIO}
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   implicit val cs = CIO.contextShift(global)
    *
    *   IO.eval(println("Hello!"))
    *     .delayExecution(5.seconds)
    *     .toConcurrent[CIO]
    * }}}
    *
    * A `ConcurrentEffect[Task]` instance is needed in scope, which itself
    * might need a [[monix.execution.Scheduler Scheduler]] to be available.
    * Such a requirement is needed because the `Task` has to be evaluated
    * in order to be converted.
    *
    * Note that this method is only applicable when the typed error `E`
    * is also a `Throwable`, or when the source task is an unexceptional
    * one (i.e. it is a `UIO`). If you need a conversion from `E` into
    * a `Throwable`, take a look at [[mapError]] or [[onErrorHandleWith]].
    * If you need a conversion into a `UIO`, take a look at [[attempt]],
    * [[materialize]] or [[onErrorHandle]].
    *
    * NOTE: the resulting value is cancelable, via usage of
    * `cats.effect.Concurrent`.
    *
    * @see [[to]] that is able to convert into any data type that has
    *      a [[IOLift]] implementation
    * @see [[toAsync]] that is able to convert into non-cancelable values via the
    *      [[https://typelevel.org/cats-effect/typeclasses/async.html Async]]
    *      type class.
    * @param F is the `cats.effect.Concurrent` instance required in
    *        order to perform the conversion
    * @param eff is the `ConcurrentEffect[Task]` instance needed to
    *        evaluate tasks; when evaluating tasks, this is the pure
    *        alternative to demanding a `Scheduler`
    */
  final def toConcurrent[F[_]](implicit F: Concurrent[F], eff: ConcurrentEffect[Task], ev: E <:< Throwable): F[A @uV] =
    TaskConversions.toConcurrent(this.asInstanceOf[Task[A]])(F, eff)

  /** Converts the source task into any data type that implements
    * [[https://typelevel.org/cats-effect/typeclasses/async.html Async]].
    *
    * Example:
    *
    * {{{
    *   import cats.effect.{IO => CIO}
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   IO.eval(println("Hello!"))
    *     .delayExecution(5.seconds)
    *     .toAsync[CIO]
    * }}}
    *
    * An `Effect[Task]` instance is needed in scope, which itself
    * might need a [[monix.execution.Scheduler Scheduler]] to
    * be available. Such requirement is needed because the `Task`
    * has to be evaluated in order to be converted.
    *
    * Note that this method is only applicable when the typed error `E`
    * is also a `Throwable`, or when the source task is an unexceptional
    * one (i.e. it is a `UIO`). If you need a conversion from `E` into
    * a `Throwable`, take a look at [[mapError]] or [[onErrorHandleWith]].
    * If you need a conversion into a `UIO`, take a look at [[attempt]],
    * [[materialize]] or [[onErrorHandle]].
    *
    * NOTE: the resulting instance will NOT be cancelable, as the
    * Task's cancelation token doesn't get carried over. This is
    * implicit in the usage of `cats.effect.Async` type class.
    * In the example above what this means is that the task will
    * still print `"Hello!"` after 5 seconds, even if the resulting
    * task gets cancelled.
    *
    * @see [[to]] that is able to convert to any data type that has
    *      a [[IOLift]] implementation
    * @see [[toConcurrent]] that is able to convert to cancelable values via the
    *      [[https://typelevel.org/cats-effect/typeclasses/concurrent.html Concurrent]]
    *      type class.
    * @param F is the `cats.effect.Async` instance required in
    *        order to perform the conversion
    * @param eff is the `Effect[Task]` instance needed to
    *        evaluate tasks; when evaluating tasks, this is the pure
    *        alternative to demanding a `Scheduler`
    */
  final def toAsync[F[_]](implicit F: Async[F], eff: Effect[Task], ev: E <:< Throwable): F[A @uV] =
    TaskConversions.toAsync(this.asInstanceOf[Task[A]])(F, eff)

  /** Converts the source task into an `org.reactivestreams.Publisher`
    * that emits a single item on success, or an error when there is
    * a typed or fatal failure.
    *
    * Note that this method is only applicable when the typed error `E`
    * is also a `Throwable`, or when the source task is an unexceptional
    * one (i.e. it is a `UIO`). If you need a conversion from `E` into
    * a `Throwable`, take a look at [[mapError]] or [[onErrorHandleWith]].
    * If you need a conversion into a `UIO`, take a look at [[attempt]],
    * [[materialize]] or [[onErrorHandle]].
    *
    * See [[http://www.reactive-streams.org/ reactive-streams.org]] for the
    * Reactive Streams specification.
    */
  final def toReactivePublisher(implicit s: Scheduler, ev: E <:< Throwable): Publisher[A @uV] =
    TaskToReactivePublisher(this.asInstanceOf[Task[A]])(s)

  /** Returns a string representation of this task meant for
    * debugging purposes only.
    */
  override def toString: String =
    this match {
      case Now(a) => s"IO.Now($a)"
      case Error(e) => s"IO.Error($e)"
      case Termination(e) => s"IO.Termination($e)"
      case _ =>
        val n = this.getClass.getName.replaceFirst("^monix\\.bio\\.IO[$.]", "")
        s"IO.$n$$${System.identityHashCode(this)}"
    }

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `map` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[map]],
    * this equivalence being true:
    *
    * `task.redeem(recover, map) <-> task.attempt.map(_.fold(recover, map))`
    *
    * Usage of `redeem` subsumes [[onErrorHandle]] because:
    *
    * `task.redeem(fe, id) <-> task.onErrorHandle(fe)`
    *
    * @param recover is a function used for error recover in case the
    *        source ends in error
    * @param map is a function used for mapping the result of the source
    *        in case it ends in success
    */
  def redeem[B](recover: E => B, map: A => B): UIO[B] =
    IO.FlatMap(this, new IO.Redeem(recover, map), null)

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `bind` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[flatMap]],
    * this equivalence being available:
    *
    * `task.redeemWith(recover, bind) <-> task.attempt.flatMap(_.fold(recover, bind))`
    *
    * Usage of `redeemWith` subsumes [[onErrorHandleWith]] because:
    *
    * `task.redeemWith(fe, F.pure) <-> task.onErrorHandleWith(fe)`
    *
    * Usage of `redeemWith` also subsumes [[flatMap]] because:
    *
    * `task.redeemWith(Task.raiseError, fs) <-> task.flatMap(fs)`
    *
    * @param recover is the function that gets called to recover the source
    *        in case of error
    * @param bind is the function that gets to transform the source
    *        in case of success
    */
  def redeemWith[E1, B](recover: E => IO[E1, B], bind: A => IO[E1, B]): IO[E1, B] =
    IO.FlatMap(this, new StackFrame.RedeemWith(recover, bind), null)

  /** Makes the source `Task` uninterruptible such that a `cancel` signal
    * (e.g. [[Fiber.cancel]]) has no effect.
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   val uncancelable = Task
    *     .eval(println("Hello!"))
    *     .delayExecution(10.seconds)
    *     .uncancelable
    *     .runToFuture
    *
    *   // No longer works
    *   uncancelable.cancel()
    *
    *   // After 10 seconds
    *   // => Hello!
    * }}}
    */
  final def uncancelable: IO[E, A] =
    TaskCancellation.uncancelable(this)

  /** Measures execution time of the source task and returns both its duration
    * and the computed value.
    *
    * Example:
    * {{{
    *   for {
    *     result <- IO(1 + 1).timed
    *     (duration, value) = result
    *     _ <- IO(println("Executed in " + duration.toMillis + " ms"))
    *   } yield value
    * }}}
    */
  final def timed: IO[E, (FiniteDuration, A)] =
    for {
      start <- IO.clock.monotonic(NANOSECONDS)
      a     <- this
      end   <- IO.clock.monotonic(NANOSECONDS)
    } yield (FiniteDuration(end - start, NANOSECONDS), a)

  /** Returns a Task that mirrors the source Task but returns `None`
    * in case the given duration passes without the
    * task emitting any item. Otherwise, returns `Some` of the resulting value.
    */
  final def timeout(after: FiniteDuration): IO[E, Option[A]] =
    timeoutL(now(after))

  /** Returns a Task that mirrors the source Task but that triggers a
    * specified error in case the given duration passes
    * without the task emitting any item.
    *
    * @param error `Error` raised after given duration passes
    */
  final def timeoutWith[E1 >: E, B >: A](after: FiniteDuration, error: E1): IO[E1, B] =
    timeoutTo(after, raiseError(error))

  /** Returns a Task that mirrors the source Task but switches to the
    * given backup Task in case the given duration passes without the
    * source emitting any item.
    */
  final def timeoutTo[E1 >: E, B >: A](after: FiniteDuration, backup: IO[E1, B]): IO[E1, B] =
    timeoutToL(now(after), backup)

  /** Returns a Task that mirrors the source Task but returns `None`
    * in case the given duration passes without the
    * task emitting any item. Otherwise, returns `Some` of the resulting value.
    */
  final def timeoutL(after: UIO[FiniteDuration]): IO[E, Option[A]] =
    this.map(Some(_)).timeoutToL(after, now(None))

  /** Returns a Task that mirrors the source Task but switches to the
    * given backup Task in case the given duration passes without the
    * source emitting any item.
    *
    * Useful when timeout is variable, e.g. when task is running in a loop
    * with deadline semantics.
    *
    * Example:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *   import java.util.concurrent.TimeoutException
    *
    *   val deadline = 10.seconds.fromNow
    *
    *   val singleCallTimeout = 2.seconds
    *
    *   // re-evaluate deadline time on every request
    *   val actualTimeout = UIO(singleCallTimeout.min(deadline.timeLeft))
    *   val error = IO.raiseError(new TimeoutException("Task timed-out"))
    *
    *   // expensive remote call
    *   def call(): Unit = ()
    *
    *   val remoteCall = IO(call())
    *     .timeoutToL(actualTimeout, error)
    *     .onErrorRestart(100)
    *     .timeout(deadline.time)
    * }}}
    * Note that this method respects the timeout task evaluation duration,
    * e.g. if it took 3 seconds to evaluate `after`
    * to a value of `5 seconds`, then this task will timeout
    * in exactly 5 seconds from the moment computation started,
    * which means in 2 seconds after the timeout task has been evaluated.
    */
  final def timeoutToL[E1 >: E, B >: A](after: UIO[FiniteDuration], backup: IO[E1, B]): IO[E1, B] = {
    val timeoutTask: UIO[Unit] =
      after.timed.flatMap { case (took, need) =>
        val left = need - took
        if (left.length <= 0) {
          UIO.unit
        } else {
          sleep(left)
        }
      }

    race(this, timeoutTask).flatMap {
      case Left(a) =>
        now(a)

      case Right(_) =>
        backup
    }
  }

  /** Hides all errors from the return type and raises them in the internal channel.
    *
    * Use if you have a method that returns a possible error but you can't recover
    * from it anyway and do not want to drag it everywhere.
    *
    * {{{
    *   import monix.execution.exceptions.DummyException
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: UIO[Int] = IO
    *     .raiseError(DummyException("boom!"))
    *     .hideErrors
    *     .map(_ => 10)
    *
    *   // Some(Failure(DummyException(boom!)))
    *   task.runToFuture.value
    * }}}
    */
  final def hideErrors(implicit E: E <:< Throwable): UIO[A] =
    onErrorHandleWith(ex => IO.terminate(E(ex)))

  /** Hides all errors from the return type and raises them in the internal channel,
    * using supplied function to transform `E` into `Throwable`.
    *
    * Use if you have a method that returns a possible error but you can't recover
    * from it anyway and do not want to drag it everywhere.
    *
    * {{{
    *   import monix.execution.exceptions.DummyException
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: UIO[Int] = IO
    *     .raiseError("boom!")
    *     .hideErrorsWith(e => DummyException(e))
    *     .map(_ => 10)
    *
    *   // Some(Failure(DummyException(boom!)))
    *   task.runToFuture.value
    * }}}
    */
  final def hideErrorsWith(f: E => Throwable): UIO[A] =
    onErrorHandleWith(ex => IO.terminate(f(ex)))

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `map` functions, which get executed depending
    * on whether the result is successful or if it ends in a fatal (untyped) error.
    *
    * @param recover is a function used for error recover in case the
    *        source ends in error
    * @param map is a function used for mapping the result of the source
    *        in case it ends in success
    * @see [[IO.redeem]] for a version which works on typed errors
    */
  final def redeemCause[B](recover: Cause[E] => B, map: A => B): UIO[B] = {
    val trace = IOTracing.getTrace(map.getClass)
    IO.FlatMap(this, new IO.RedeemFatal(recover, map), trace)
  }

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `bind` functions, which get executed depending
    * on whether the result is successful or if it ends in a fatal (untyped) error.
    *
    * Usage of `redeemWith` also subsumes [[flatMap]] because:
    *
    * `task.redeemCauseWith(IO.raiseError, fs) <-> task.flatMap(fs)`
    *
    * @param recover is the function that gets called to recover the source
    *        in case of error
    * @param bind is the function that gets to transform the source
    *        in case of success
    * @see [[IO.redeemWith]] for a version which only works on typed errors
    */
  final def redeemCauseWith[E1, B](recover: Cause[E] => IO[E1, B], bind: A => IO[E1, B]): IO[E1, B] = {
    val trace = IOTracing.getTrace(bind.getClass)
    IO.FlatMap(this, new StackFrame.RedeemFatalWith(recover, bind), trace)
  }

  /** Absorbs all unexpected errors to typed error channel.
    *
    * {{{
    *   import monix.execution.exceptions.DummyException
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: UIO[Either[Throwable, Int]] = IO
    *     .terminate(DummyException("boom!"))
    *     .absorb
    *     .attempt
    *
    *     // Some(Success(Left(DummyException(boom!))))
    *     task.runToFuture.value
    * }}}
    */
  final def absorb(implicit ev: E <:< Throwable): Task[A] =
    redeemCauseWith(c => IO.raiseError(c.toThrowable), x => IO.now(x))

  /** Absorbs all unexpected errors to typed error channel.
    *
    * {{{
    *   import monix.execution.exceptions.DummyException
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: UIO[Either[Throwable, Int]] = IO
    *     .raiseError("boom!")
    *     .absorbWith(e => DummyException(e))
    *     .attempt
    *
    *     // Some(Success(Left(DummyException(boom!))))
    *     task.runToFuture.value
    * }}}
    */
  final def absorbWith(f: E => Throwable): Task[A] =
    redeemCauseWith(c => IO.raiseError(c.fold(identity, e => f(e))), x => IO.now(x))

  /** Returns this task mapped to unit
    */
  final def void: IO[E, Unit] =
    this.map(_ => ())
}

/** Builders for [[IO]].
  *
  * @define registerParamDesc is a function that will be called when
  *         this `Task` is executed, receiving a callback as a
  *         parameter, a callback that the user is supposed to call in
  *         order to signal the desired outcome of this `Task`. This
  *         function also receives a [[monix.execution.Scheduler Scheduler]]
  *         that can be used for managing asynchronous boundaries, a
  *         scheduler being nothing more than an evolved `ExecutionContext`.
  *
  * @define shiftDesc For example we can introduce an
  *         asynchronous boundary in the `flatMap` chain before a
  *         certain task, this being literally the implementation of
  *         [[IO.executeAsync executeAsync]]:
  *
  *         {{{
  *           val task = IO.eval(35)
  *
  *           IO.shift.flatMap(_ => task)
  *         }}}
  *
  *         And this can also be described with `>>` from Cats:
  *
  *         {{{
  *           import cats.syntax.all._
  *
  *           IO.shift >> task
  *         }}}
  *
  *                           Or we can specify an asynchronous boundary ''after''
  *                           the evaluation of a certain task, this being literally
  *                           the implementation of
  *                           [[IO!.asyncBoundary:monix\.bio\.IO[E,A]* .asyncBoundary]]:
  *
  *         {{{
  *           task.flatMap(a => IO.shift.map(_ => a))
  *         }}}
  *
  *                           And again we can also describe this with `<*`
  *                           from Cats:
  *
  *         {{{
  *           task <* IO.shift
  *         }}}
  * @define parallelismNote   NOTE: the tasks get forked automatically so there's
  *                           no need to force asynchronous execution for immediate tasks,
  *                           parallelism being guaranteed when multi-threading is available!
  *
  *                           All specified tasks get evaluated in parallel, regardless of their
  *                           execution model ([[IO.eval]] vs [[IO.evalAsync]] doesn't matter).
  *                           Also the implementation tries to be smart about detecting forked
  *                           tasks so it can eliminate extraneous forks for the very obvious
  *                           cases.
  * @define parallelismAdvice ADVICE: In a real life scenario the tasks should
  *                           be expensive in order to warrant parallel execution. Parallelism
  *                           doesn't magically speed up the code - it's usually fine for I/O-bound
  *                           tasks, however for CPU-bound tasks it can make things worse.
  *                           Performance improvements need to be verified.
  */
object IO extends TaskInstancesLevel0 {

  /** Lifts the given thunk in the `IO` context, processing it synchronously
    * when the task gets evaluated.
    *
    * This is an alias for:
    *
    * {{{
    *   val thunk = () => 42
    *   IO.eval(thunk())
    * }}}
    *
    * WARN: behavior of `IO.apply` has changed since 3.0.0-RC2.
    * Before the change (during Monix 2.x series), this operation was forcing
    * a fork, being equivalent to the new [[IO.evalAsync]].
    *
    * Switch to [[IO.evalAsync]] if you wish the old behavior, or combine
    * [[IO.eval]] with [[IO.executeAsync]].
    */
  def apply[A](a: => A): Task[A] =
    eval(a)

  /** Returns a `IO` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[A](a: A): UIO[A] =
    IOTracing.decorateIfNeeded(IO.Now(a))

  /** Lifts a value into the task context. Alias for [[now]]. */
  def pure[A](a: A): UIO[A] = now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified value in a typed error channel.
    */
  def raiseError[E](ex: E): IO[E, Nothing] =
    IOTracing.decorateIfNeeded(Error(ex))

  /** Returns a task that on execution is always finishing in a fatal (unexpected) error
    * emitting the specified exception.
    *
    * This type of errors is not reflected in the type signature and it skips all regular
    * error handlers, except for [[IO.redeemCause]] and [[IO.redeemCauseWith]].
    */
  def terminate(ex: Throwable): UIO[Nothing] =
    IOTracing.decorateIfNeeded(Termination(ex))

  /** Defers the creation of a `Task` in case it is effectful.
    *
    * It will catch any exceptions thrown in `fa` and expose them as
    * a typed error.
    *
    * @see [[deferTotal]] if `fa` is not expected to throw any exceptions.
    */
  def defer[A](fa: => Task[A]): Task[A] =
    suspend(fa)

  /** Defers the creation of a `IO` in case it is effectful.
    *
    * @see [[defer]] if `fa` is expected to throw exceptions and you would
    *      like to expose them as typed errors.
    */
  def deferTotal[E, A](fa: => IO[E, A]): IO[E, A] =
    IOTracing.decorateIfNeeded(SuspendTotal(fa _))

  /** Defers the creation of a `Task` by using the provided
    * function, which has the ability to inject a needed
    * [[monix.execution.Scheduler Scheduler]].
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration.MILLISECONDS
    *
    *   def measureLatency[A](source: Task[A]): Task[(A, Long)] =
    *     Task.deferAction { implicit s =>
    *       // We have our Scheduler, which can inject time, we
    *       // can use it for side-effectful operations
    *       val start = s.clockRealTime(MILLISECONDS)
    *
    *       source.map { a =>
    *         val finish = s.clockRealTime(MILLISECONDS)
    *         (a, finish - start)
    *       }
    *     }
    * }}}
    *
    * @param f is the function that's going to be called when the
    *        resulting `Task` gets evaluated
    */
  def deferAction[E, A](f: Scheduler => IO[E, A]): IO[E, A] =
    TaskDeferAction(f)

  /** Promote a non-strict Scala `Future` to a `Task` of the same type.
    *
    * The equivalent of doing:
    * {{{
    *   import scala.concurrent.Future
    *   def mkFuture = Future.successful(27)
    *
    *   Task.defer(Task.fromFuture(mkFuture))
    * }}}
    */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /** Wraps calls that generate `Future` results into [[Task]], provided
    * a callback with an injected [[monix.execution.Scheduler Scheduler]]
    * to act as the necessary `ExecutionContext`.
    *
    * This builder helps with wrapping `Future`-enabled APIs that need
    * an implicit `ExecutionContext` to work. Consider this example:
    *
    * {{{
    *   import scala.concurrent.{ExecutionContext, Future}
    *
    *   def sumFuture(list: Seq[Int])(implicit ec: ExecutionContext): Future[Int] =
    *     Future(list.sum)
    * }}}
    *
    * We'd like to wrap this function into one that returns a lazy
    * `Task` that evaluates this sum every time it is called, because
    * that's how tasks work best. However in order to invoke this
    * function an `ExecutionContext` is needed:
    *
    * {{{
    *   def sumTask(list: Seq[Int])(implicit ec: ExecutionContext): Task[Int] =
    *     Task.deferFuture(sumFuture(list))
    * }}}
    *
    * But this is not only superfluous, but against the best practices
    * of using `Task`. The difference is that `Task` takes a
    * [[monix.execution.Scheduler Scheduler]] (inheriting from
    * `ExecutionContext`) only when [[IO.runAsync runAsync]] happens.
    * But with `deferFutureAction` we get to have an injected
    * `Scheduler` in the passed callback:
    *
    * {{{
    *   def sumTask2(list: Seq[Int]): Task[Int] =
    *     Task.deferFutureAction { implicit scheduler =>
    *       sumFuture(list)
    *     }
    * }}}
    *
    * @param f is the function that's going to be executed when the task
    *        gets evaluated, generating the wrapped `Future`
    */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /** Alias for [[defer]]. */
  def suspend[A](fa: => Task[A]): Task[A] =
    IOTracing.decorateIfNeeded(Suspend(() => fa))

  /** Alias for [[deferTotal]]. */
  def suspendTotal[E, A](fa: => IO[E, A]): IO[E, A] =
    IOTracing.decorateIfNeeded(SuspendTotal(fa _))

  /** Promote a non-strict value to a `IO` that is memoized on the first
    * evaluation, the result being then available on subsequent evaluations.
    */
  def evalOnce[A](a: => A): Task[A] =
    eval(a).memoize

  /** Promote a non-strict value, a thunk, to a `IO`, catching exceptions
    * in the process.
    *
    * Note that since `IO` is not memoized or strict, this will recompute the
    * value each time the `IO` is executed, behaving like a function.
    *
    * @param a is the thunk to process on evaluation
    *
    * @see [[evalTotal]] if `a` is not expected to throw any exceptions.
    */
  def eval[A](a: => A): Task[A] =
    IOTracing.decorateIfNeeded(Eval(() => a))

  /** Promote a non-strict value which does not throw any unexpected errors to `UIO`.
    *
    * Note that since `IO` is not memoized or strict, this will recompute the
    * value each time the `IO` is executed, behaving like a function.
    *
    * @param a is the thunk to process on evaluation
    *
    * @see [[eval]] if `a` is expected to throw exceptions and you want to expose them
    *      in a typed error channel.
    */
  def evalTotal[A](a: => A): UIO[A] =
    IOTracing.decorateIfNeeded(EvalTotal(a _))

  /** Lifts a non-strict value, a thunk, to a `Task` that will trigger a logical
    * fork before evaluation.
    *
    * Like [[eval]], but the provided `thunk` will not be evaluated immediately.
    * Equivalence:
    *
    * `Task.evalAsync(a) <-> Task.eval(a).executeAsync`
    *
    * @param a is the thunk to process on evaluation
    */
  def evalAsync[A](a: => A): Task[A] =
    IOTracing.decorateIfNeeded(TaskEvalAsync(() => a))

  /** Alias for [[eval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: UIO[A] = neverRef

  /** Converts into a `Task` from any `F[_]` for which there exists
    * a [[IOLike]] implementation.
    *
    * Supported types include, but are not necessarily limited to:
    *
    *  - [[https://typelevel.org/cats/datatypes/eval.html cats.Eval]]
    *  - [[https://typelevel.org/cats-effect/datatypes/io.html cats.effect.IO]]
    *  - [[https://typelevel.org/cats-effect/datatypes/syncio.html cats.effect.SyncIO]]
    *  - [[https://typelevel.org/cats-effect/typeclasses/effect.html cats.effect.Effect (Async)]]
    *  - [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.ConcurrentEffect]]
    *  - [[scala.Either]]
    *  - [[scala.util.Try]]
    *  - [[scala.concurrent.Future]]
    */
  def from[F[_], A](fa: F[A])(implicit F: IOLike[F]): Task[A] =
    F.apply(fa)

  /** Converts an `org.reactivestreams.Publisher` into a [[IO]].
    *
    * See [[http://www.reactive-streams.org/ reactive-streams.org]] for the
    * Reactive Streams specification.
    *
    * @see [[IO.toReactivePublisher]] for converting a [[IO]] into
    *      a reactive publisher.
    * @param source is the `org.reactivestreams.Publisher` reference to
    *               wrap into a [[IO]].
    */
  def fromReactivePublisher[A](source: Publisher[A]): Task[Option[A]] =
    TaskConversions.fromReactivePublisher(source)

  /** Builds a [[Task]] out of any data type that implements
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html Concurrent]] and
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html ConcurrentEffect]].
    *
    * Example:
    *
    * {{{
    *   import cats.effect.{IO => CIO, _}
    *   import cats.syntax.all._
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   implicit val timer = CIO.timer(global)
    *   implicit val cs = CIO.contextShift(global)
    *
    *   val cio = CIO.sleep(5.seconds) *> CIO(println("Hello!"))
    *
    *   // Resulting task is cancelable
    *   val task: Task[Unit] = IO.fromConcurrentEffect(cio)
    * }}}
    *
    * Cancellation / finalization behavior is carried over, so the
    * resulting task can be safely cancelled.
    *
    * @see [[IO.liftToConcurrent]] for its dual
    * @see [[IO.fromEffect]] for a version that works with simpler,
    *      non-cancelable `Async` data types
    * @see [[IO.from]] for a more generic version that works with
    *      any [[IOLike]] data type
    * @param F is the `cats.effect.Effect` type class instance necessary
    *        for converting to `Task`; this instance can also be a
    *        `cats.effect.Concurrent`, in which case the resulting
    *        `Task` value is cancelable if the source also is
    */
  def fromConcurrentEffect[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] =
    TaskConversions.fromConcurrentEffect(fa)(F)

  /** Builds a [[IO]] out of any data type that implements
    * [[https://typelevel.org/cats-effect/typeclasses/async.html Async]] and
    * [[https://typelevel.org/cats-effect/typeclasses/effect.html Effect]].
    *
    * Example:
    *
    * {{{
    *   import cats.effect.{IO => CIO}
    *
    *   val cio: CIO[Unit] = CIO(println("Hello!"))
    *   val task: Task[Unit] = IO.fromEffect(cio)
    * }}}
    *
    * WARNING: the resulting task might not carry the source's cancellation behavior
    * if the source is cancelable! This is implicit in the usage of `Effect`.
    *
    * @see [[IO.fromConcurrentEffect]] for a version that can use
    *      [[https://typelevel.org/cats-effect/typeclasses/concurrent.html Concurrent]]
    *      for converting cancelable tasks.
    * @see [[IO.from]] for a more generic version that works with
    *      any [[IOLike]] data type
    * @see [[IO.liftToAsync for]] its dual
    * @param F is the `cats.effect.Effect` type class instance necessary
    *        for converting to `Task`; this instance can also be a
    *        `cats.effect.Concurrent`, in which case the resulting
    *        `Task` value is cancelable if the source also is
    */
  def fromEffect[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    TaskConversions.fromEffect(fa)

  /** Builds a [[IO]] instance out of a Scala `Option`.
    * If the Option is empty, the task fails with Unit.
    *
    * Example:
    *
    * {{{
    *   IO.fromOption(Some(1)) // <-> IO.now(1))
    *   IO.fromOption(None)    // <-> IO.raiseError(())
    * }}}
    */
  def fromOption[A](opt: Option[A]): IO[Unit, A] =
    opt match {
      case None => Error(())
      case Some(v) => Now(v)
    }

  /** Builds a [[IO]] instance out of a Scala `Option`.
    * If the Option is empty, the task fails with the provided fallback.
    *
    * @see [[IO.fromOptionEval]] for a version that takes a `IO[E, Option[A]]`
    *
    * Example:
    *
    * {{{
    *   final case class NotFound()
    *
    *   IO.fromOption(Some(1), NotFound()) // <-> IO.now(1)
    *   IO.fromOption(None, NotFound())   // <-> IO.raiseError(NotFound())
    * }}}
    */
  def fromOption[E, A](opt: Option[A], ifEmpty: => E): IO[E, A] =
    opt match {
      case None => IO.suspendTotal(IO.raiseError(ifEmpty))
      case Some(v) => Now(v)
    }

  /** Builds a new [[IO]] instance out of a `IO[E, Option[A]]`.
    * If the inner Option is empty, the task fails with the provided fallback.
    *
    * Example:
    *
    * {{{
    *  type ErrorCode = Int
    *  final case class Item()
    *
    *  def findItem(id: Int): IO[ErrorCode, Option[Item]] =
    *    UIO.now(Some(Item()))
    *
    *  IO.fromOptionEval(findItem(1), 404)
    * }}}
    */
  def fromOptionEval[E, E1 >: E, A](opt: IO[E, Option[A]], ifEmpty: => E1): IO[E1, A] = {
    opt.flatMap {
      case None => Error(ifEmpty)
      case Some(v) => Now(v)
    }
  }

  /** Builds a [[Task]] instance out of a Scala `Try`. */
  def fromTry[A](a: Try[A]): Task[A] =
    a match {
      case Success(v) => Now(v)
      case Failure(ex) => Error(ex)
    }

  /** Builds a [[Task]] instance out of a Scala `Either`. */
  def fromEither[E, A](a: Either[E, A]): IO[E, A] =
    a match {
      case Right(v) => Now(v)
      case Left(ex) => Error(ex)
    }

  /** Builds a [[IO]] instance out of scala `Either` wrapped in scala `Try` */
  def fromTryEither[E, A](a: Try[Either[E, A]]): IO[E, A] =
    a match {
      case Success(Right(a)) => Now(a)
      case Success(Left(e)) => Error(e)
      case Failure(t) => Termination(t)
    }

  /** Keeps calling `f` until it returns a `Right` result.
    *
    * Based on Phil Freeman's
    * [[http://functorial.com/stack-safety-for-free/index.pdf Stack Safety for Free]].
    */
  def tailRecM[E, A, B](a: A)(f: A => IO[E, Either[A, B]]): IO[E, B] =
    IO.deferTotal(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b) => IO.now(b)
    }

  /** A `Task[Unit]` provided for convenience. */
  val unit: UIO[Unit] = pure(())

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[IO.cancelable[E,A](register* IO.cancelable]].
    * This can be used to translate from a callback-based API to pure
    * `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[IO.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.{Future, ExecutionContext}
    *   import scala.util._
    *
    *   def deferFuture[A](f: => Future[A])(implicit ec: ExecutionContext): Task[A] =
    *     Task.async { cb =>
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function needs an explicit `ExecutionContext` in order
    * to trigger `Future#complete`, however Monix's `Task` can inject
    * a [[monix.execution.Scheduler Scheduler]] for you, thus allowing you
    * to get rid of these pesky execution contexts being passed around explicitly.
    * See [[IO.async0]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.bio.BiCallback BiCallback]] can be
    *    called at most once, either with a successful result, or with
    *    an error; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *    and [[monix.bio.BiCallback.tryOnTermination]]
    *
    * @see [[IO.async0]] for a variant that also injects a
    *      [[monix.execution.Scheduler Scheduler]] into the provided callback,
    *      useful for forking, or delaying tasks or managing async boundaries
    * @see [[IO.cancelable[E,A](register* IO.cancelable]] and [[IO.cancelable0]]
    *      for creating cancelable tasks
    * @see [[IO.create]] for the builder that does it all
    */
  def async[E, A](register: BiCallback[E, A] => Unit): IO[E, A] =
    TaskCreate.async(register)

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[IO.cancelable0]]. It can be used to translate from a
    * callback-based API to pure `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[IO.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.Future
    *   import scala.util._
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.async0 { (scheduler, cb) =>
    *       // We are being given an ExecutionContext ;-)
    *       implicit val ec = scheduler
    *
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function doesn't need an implicit `ExecutionContext`.
    * Compared with usage of [[IO.async[E,A](register* IO.async]], this
    * function injects a [[monix.execution.Scheduler Scheduler]] for us to
    * use for managing async boundaries.
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.bio.BiCallback]] can be called at
    *    most once, either with a successful result, or with an error;
    *    calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *    and [[monix.bio.BiCallback.tryOnTermination BiCallback.tryOnTermination]]
    *
    * NOTES on the naming:
    *
    *  - `async` comes from `cats.effect.Async#async`
    *  - the `0` suffix is about overloading the simpler
    *    [[IO.async[E,A](register* IO.async]] builder
    *
    * @see [[IO.async]] for a simpler variant that doesn't inject a
    *      `Scheduler`, in case you don't need one
    * @see [[IO.cancelable[E,A](register* IO.cancelable]] and [[IO.cancelable0]]
    *      for creating cancelable tasks
    * @see [[IO.create]] for the builder that does it all
    */
  def async0[E, A](register: (Scheduler, BiCallback[E, A]) => Unit): IO[E, A] =
    TaskCreate.async0(register)

  /** Suspends an asynchronous side effect in `IO`, this being a
    * variant of [[async]] that takes a pure registration function.
    *
    * Implements `cats.effect.Async.asyncF`.
    *
    * The difference versus [[async]] is that this variant can suspend
    * side-effects via the provided function parameter. It's more relevant
    * in polymorphic code making use of the `cats.effect.Async`
    * type class, as it alleviates the need for `cats.effect.Effect`.
    *
    * Contract for the returned `IO[E, Unit]` in the provided function:
    *
    *  - can be asynchronous
    *  - can be cancelable, in which case it hooks into IO's cancelation
    *    mechanism such that the resulting task is cancelable
    *  - it should not end in error, because the provided callback
    *    is the only way to signal the final result and it can only
    *    be called once, so invoking it twice would be a contract
    *    violation; so on errors thrown in `IO`, the task can become
    *    non-terminating, with the error being printed via
    *    [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]]
    *
    * @see [[IO.async]] and [[IO.async0]] for a simpler variants
    * @see [[IO.cancelable[E,A](register* Task.cancelable]] and
    *      [[IO.cancelable0]] for creating cancelable tasks
    */
  def asyncF[E, A](register: BiCallback[E, A] => IO[E, Unit]): IO[E, A] =
    TaskCreate.asyncF(register)

  /** Create a cancelable `Task` from an asynchronous computation that
    * can be canceled, taking the form of a function with which we can
    * register a callback to execute upon completion.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based
    * API to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[IO.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import java.util.concurrent.ScheduledExecutorService
    *   import scala.concurrent.ExecutionContext
    *   import scala.concurrent.duration._
    *   import scala.util.control.NonFatal
    *
    *   def delayed[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A)
    *     (implicit ec: ExecutionContext): Task[A] = {
    *
    *     Task.cancelable { cb =>
    *       val future = sc.schedule(new Runnable { // scheduling delay
    *         def run() = ec.execute(new Runnable { // scheduling thunk execution
    *           def run() =
    *             try
    *               cb.onSuccess(thunk)
    *             catch { case NonFatal(e) =>
    *               cb.onError(e)
    *             }
    *           })
    *         },
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancelation token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Task(future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * Note in this sample we are passing an implicit `ExecutionContext`
    * in order to do the actual processing, the `ScheduledExecutorService`
    * being in charge just of scheduling. We don't need to do that, as `Task`
    * affords to have a [[monix.execution.Scheduler Scheduler]] injected
    * instead via [[IO.cancelable0]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.bio.BiCallback BiCallback]] can be
    *    called at most once, either with a successful result, or with
    *    an typed; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *    and [[monix.bio.BiCallback.tryOnTermination BiCallback.tryOnTermination]]
    *
    * @see [[IO.cancelable0]] for the version that also injects a
    *      [[monix.execution.Scheduler Scheduler]] in that callback
    * @see [[IO.async0]] and [[IO.async[E,A](register* Task.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    * @see [[IO.create]] for the builder that does it all
    * @param register $registerParamDesc
    */
  def cancelable[E, A](register: BiCallback[E, A] => CancelToken[IO[E, *]]): IO[E, A] =
    cancelable0[E, A]((_, cb) => register(cb))

  /** Create a cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based API
    * to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[IO.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import java.util.concurrent.ScheduledExecutorService
    *   import scala.concurrent.duration._
    *   import scala.util.control.NonFatal
    *
    *   def delayed1[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A): Task[A] = {
    *
    *     Task.cancelable0 { (scheduler, cb) =>
    *       val future = sc.schedule(new Runnable { // scheduling delay
    *         def run = scheduler.execute(new Runnable { // scheduling thunk execution
    *           def run() =
    *             try
    *               cb.onSuccess(thunk)
    *             catch { case NonFatal(e) =>
    *               cb.onError(e)
    *             }
    *           })
    *         },
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancel token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Task(future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * As can be seen, the passed function needs to pass a
    * [[monix.execution.Cancelable Cancelable]] in order to specify cancelation
    * logic.
    *
    * This is a sample given for didactic purposes. Our `cancelable0` is
    * being injected a [[monix.execution.Scheduler Scheduler]] and it is
    * perfectly capable of doing such delayed execution without help from
    * Java's standard library:
    *
    * {{{
    *   def delayed2[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.cancelable0 { (scheduler, cb) =>
    *       // N.B. this already returns the Cancelable that we need!
    *       val cancelable = scheduler.scheduleOnce(timespan) {
    *         try cb.onSuccess(thunk)
    *         catch { case NonFatal(e) => cb.onError(e) }
    *       }
    *       // `scheduleOnce` above returns a Cancelable, which
    *       // has to be converted into a Task[Unit]
    *       Task(cancelable.cancel())
    *     }
    * }}}
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.bio.BiCallback BiCallback]] can be
    *    called at most once, either with a successful result, or with
    *    an error; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *    and [[monix.bio.BiCallback.tryOnTermination BiCallback.tryOnTermination]]
    *
    * NOTES on the naming:
    *
    *  - `cancelable` comes from `cats.effect.Concurrent#cancelable`
    *  - the `0` suffix is about overloading the simpler
    *    [[IO.cancelable[E,A](register* IO.cancelable]] builder
    *
    * @see [[IO.cancelable[E,A](register* Task.cancelable]] for the simpler
    *      variant that doesn't inject the `Scheduler` in that callback
    * @see [[IO.async0]] and [[IO.async[E,A](register* IO.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    * @see [[IO.create]] for the builder that does it all
    * @param register $registerParamDesc
    */
  def cancelable0[E, A](register: (Scheduler, BiCallback[E, A]) => CancelToken[IO[E, *]]): IO[E, A] =
    TaskCreate.cancelable0(register)

  /** Returns a cancelable boundary — a `Task` that checks for the
    * cancellation status of the run-loop and does not allow for the
    * bind continuation to keep executing in case cancellation happened.
    *
    * This operation is very similar to `Task.shift`, as it can be dropped
    * in `flatMap` chains in order to make loops cancelable.
    *
    * Example:
    *
    * {{{
    *
    *  import cats.syntax.all._
    *
    *  def fib(n: Int, a: Long, b: Long): Task[Long] =
    *    Task.suspend {
    *      if (n <= 0) Task.pure(a) else {
    *        val next = fib(n - 1, b, a + b)
    *
    *        // Every 100-th cycle, check cancellation status
    *        if (n % 100 == 0)
    *          Task.cancelBoundary *> next
    *        else
    *          next
    *      }
    *    }
    * }}}
    *
    * NOTE: that by default `Task` is configured to be auto-cancelable
    * (see [[IO.Options]]), so this isn't strictly needed, unless you
    * want to fine tune the cancelation boundaries.
    */
  val cancelBoundary: UIO[Unit] =
    IO.Async[Nothing, Unit] { (ctx, cb) =>
      if (!ctx.connection.isCanceled) cb.onSuccess(())
    }

  /** Polymorphic `Task` builder that is able to describe asynchronous
    * tasks depending on the type of the given callback.
    *
    * Note that this function uses the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]].
    *
    * Calling `create` with a callback that returns `Unit` is
    * equivalent with [[IO.async0]]:
    *
    * `Task.async0(f) <-> Task.create(f)`
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.Future
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       f.onComplete(cb(_))(scheduler)
    *     }
    * }}}
    *
    * We could return a [[monix.execution.Cancelable Cancelable]]
    * reference and thus make a cancelable task. Example:
    *
    * {{{
    *   import monix.execution.Cancelable
    *   import scala.concurrent.duration.FiniteDuration
    *   import scala.util.Try
    *
    *   def delayResult1[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Cancelable(() => c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `cats.effect.IO[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   import cats.effect.{IO => CIO}
    *
    *   def delayResult2[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       CIO(c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `Task[Unit]` as a task that
    * describes a cancelation action, thus for an `f` that can be
    * passed to [[IO.cancelable0]], and this equivalence holds:
    *
    * `Task.cancelable(f) <-> Task.create(f)`
    *
    * {{{
    *   def delayResult3[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Task(c.cancel())
    *     }
    * }}}
    *
    * The supported types for the cancelation tokens are:
    *
    *  - `Unit`, yielding non-cancelable tasks
    *  - [[monix.execution.Cancelable Cancelable]], the Monix standard
    *  - [[monix.bio.Task Task[Unit]]]
    *  - `cats.effect.IO[Unit]`, see
    *    [[https://typelevel.org/cats-effect/datatypes/io.html IO docs]]
    *
    * Support for more might be added in the future.
    */
  def create[E, A]: AsyncBuilder.CreatePartiallyApplied[E, A] = new AsyncBuilder.CreatePartiallyApplied[E, A]

  /** Converts the given Scala `Future` into a `Task`.
    *
    * There is an async boundary inserted at the end to guarantee
    * that we stay on the main Scheduler.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    TaskFromFuture.strict(f)

  /** Wraps a [[monix.execution.CancelablePromise]] into `Task`. */
  def fromCancelablePromise[A](p: CancelablePromise[A]): Task[A] =
    TaskFromFuture.fromCancelablePromise(p)

  /** Wraps a [[monix.execution.CancelablePromise]] into `IO`. */
  def fromCancelablePromiseEither[E, A](p: CancelablePromise[Either[E, A]]): IO[E, A] =
    TaskFromFutureEither.fromCancelablePromise(p)

  /** Converts any Future-like data-type into a `Task`, via [[monix.catnap.FutureLift]].
    */
  def fromFutureLike[F[_], A](tfa: Task[F[A]])(implicit F: FutureLift[Task, F]): Task[A] =
    F.apply(tfa)

  /** Run two `Task` actions concurrently, and return the first to
    * finish, either in success or error. The loser of the race is
    * cancelled.
    *
    * The two tasks are executed in parallel, the winner being the
    * first that signals a result.
    *
    * As an example, this would be equivalent with [[IO.timeout]]:
    * {{{
    *   import scala.concurrent.duration._
    *   import scala.concurrent.TimeoutException
    *
    *   // some long running task
    *   val myTask = Task(42)
    *
    *   val timeoutError = Task
    *     .raiseError(new TimeoutException)
    *     .delayExecution(5.seconds)
    *
    *   Task.race(myTask, timeoutError)
    * }}}
    *
    * Similarly [[IO.timeoutTo]] is expressed in terms of `race`.
    *
    * $parallelismNote
    *
    * @see [[racePair]] for a version that does not cancel
    *     the loser automatically on successful results and doctodo raceMany
    *     for a version that races a whole list of tasks.
    */
  def race[E, A, B](fa: IO[E, A], fb: IO[E, B]): IO[E, Either[A, B]] =
    TaskRace(fa, fb)

  /** Runs multiple tasks in a concurrent way and returns the fastest
    * of them, regardless whether it's a success, a typed error or a
    * terminal error. Every task losing the race gets cancelled.
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val tasks: List[UIO[Int]] =
    *     List(1, 2, 3).map(i => IO.sleep(i.seconds).map(_ => i))
    *
    *   val winner: UIO[Int] = IO.raceMany(tasks)
    * }}}
    *
    * $parallelismNote
    *
    * @see [[race]] or [[racePair]] for racing two tasks, which might
    *      give you more control over the execution.
    */
  def raceMany[E, A](tasks: Iterable[IO[E, A]]): IO[E, A] =
    TaskRaceList(tasks)

  /** Run two `Task` actions concurrently, and returns a pair
    * containing both the winner's successful value and the loser
    * represented as a still-unfinished task.
    *
    * If the first task completes in error, then the result will
    * complete in error, the other task being cancelled.
    *
    * On usage the user has the option of cancelling the losing task,
    * this being equivalent with plain [[race]]:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val ta = Task.sleep(2.seconds).map(_ => "a")
    *   val tb = Task.sleep(3.seconds).map(_ => "b")
    *
    *   // `tb` is going to be cancelled as it returns 1 second after `ta`
    *   Task.racePair(ta, tb).flatMap {
    *     case Left((a, taskB)) =>
    *       taskB.cancel.map(_ => a)
    *     case Right((taskA, b)) =>
    *       taskA.cancel.map(_ => b)
    *   }
    * }}}
    *
    * $parallelismNote
    *
    * @see [[race]] for a simpler version that cancels the loser
    *      immediately or doctodo raceMany that races collections of tasks.
    */
  def racePair[E, A, B](fa: IO[E, A], fb: IO[E, B]): IO[E, Either[(A, Fiber[E, B]), (Fiber[E, A], B)]] =
    TaskRacePair(fa, fb)

  /** Inverse of `attempt`. Creates a new [[IO]] that absorbs `Either`.
    *
    * `IO.rethrow(IO.now(Right(42))) <-> IO.now(42)`
    *
    * `IO.rethrow(IO.now(Left("error"))) <-> IO.raiseError("error")`
    */
  def rethrow[E, A](fa: IO[E, Either[E, A]]): IO[E, A] =
    fa.rethrow

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another thread or call stack, managed by
    * the default [[monix.execution.Scheduler Scheduler]].
    *
    * This is the equivalent of `IO.shift`, except that Monix's `Task`
    * gets executed with an injected `Scheduler` in [[IO.runAsync]] or
    * in [[IO.runToFuture]] and that's going to be the `Scheduler`
    * responsible for the "shift".
    *
    * $shiftDesc
    *
    * @see [[IO.executeOn]] for a way to override the default `Scheduler`
    */
  val shift: UIO[Unit] =
    shift(null)

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another call stack or thread, managed by
    * the given execution context.
    *
    * This is the equivalent of `IO.shift`.
    *
    * $shiftDesc
    */
  def shift(ec: ExecutionContext): UIO[Unit] =
    TaskShift(ec)

  /** Returns an `IO` that on execution is always successful, emitting None. */
  def none[A]: UIO[Option[A]] = IO.pure(Option.empty[A])

  /** Returns an `IO` that on execution is always successful, emitting some value. */
  def some[A](a: A): UIO[Option[A]] = IO.pure(Some(a))

  /** Returns an `IO` that on execution is always successful, emitting left value. */
  def left[A, B](a: A): UIO[Either[A, B]] = IO.pure(Left(a))

  /** Returns an `IO` that on execution is always successful, emitting right value. */
  def right[A, B](b: B): UIO[Either[A, B]] = IO.pure(Right(b))

  /** Creates a new `Task` that will sleep for the given duration,
    * emitting a tick when that time span is over.
    *
    * As an example on evaluation this will print "Hello!" after
    * 3 seconds:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   IO.sleep(3.seconds).flatMap { _ =>
    *     IO.eval(println("Hello!"))
    *   }
    * }}}
    *
    * See [[IO.delayExecution]] for this operation described as
    * a method on `Task` references or [[IO.delayResult]] for the
    * helper that triggers the evaluation of the source on time, but
    * then delays the result.
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    TaskSleep.apply(timespan)

  /** Given a `Iterable` of tasks, transforms it to a task signaling
    * the collection, executing the tasks one by one and gathering their
    * results in the same collection.
    *
    * This operation will execute the tasks one by one, in order, which means that
    * both effects and results will be ordered. See [[parSequence]] and [[parSequenceUnordered]]
    * for unordered results or effects, and thus potential of running in parallel.
    *
    *  It's a simple version of [[traverse]].
    */
  def sequence[E, A](
    in: Iterable[IO[E, A]]
  ): IO[E, List[A]] =
    TaskSequence.list(in)

  /** Returns the accumulated trace of the currently active fiber.
    */
  val trace: UIO[IOTrace] =
    Async[Nothing, IOTrace] { (ctx, cb) =>
      cb.onSuccess(ctx.stackTracedContext.trace())
    }

  /** Given a `Iterable[A]` and a function `A => Task[B]`, sequentially
    * apply the function to each element of the collection and gather their
    * results in the same collection.
    *
    *  It's a generalized version of [[sequence]].
    */
  def traverse[E, A, B](
    in: Iterable[A]
  )(f: A => IO[E, B]): IO[E, List[B]] =
    TaskSequence.traverse(in, f)

  /** Returns the given argument if `cond` is true, otherwise `IO.Unit`
    *
    * @see [[IO.unless]] for the inverse
    * @see [[IO.raiseWhen]] for conditionally raising an error
    */
  def when[E](cond: Boolean)(action: => IO[E, Unit]): IO[E, Unit] = if (cond) action else IO.unit

  /** Returns the given argument if `cond` is false, otherwise `IO.Unit`
    *
    * @see [[IO.when]] for the inverse
    * @see [[IO.raiseWhen]] for conditionally raising an error
    */
  def unless[E](cond: Boolean)(action: => IO[E, Unit]): IO[E, Unit] = if (cond) IO.unit else action

  /** Returns `raiseError` when the `cond` is true, otherwise `IO.unit`
    *
    * @example {{{
    * val tooMany = 5
    * val x: Int = ???
    * IO.raiseWhen(x >= tooMany)(new IllegalArgumentException("Too many"))
    * }}}
    */
  def raiseWhen[E](cond: Boolean)(e: => E): IO[E, Unit] =
    IO.when(cond)(IO.raiseError(e))

  /** Returns `raiseError` when `cond` is false, otherwise IO.unit
    *
    * @example {{{
    * val tooMany = 5
    * val x: Int = ???
    * IO.raiseUnless(x < tooMany)(new IllegalArgumentException("Too many"))
    * }}}
    */
  def raiseUnless[E](cond: Boolean)(e: => E): IO[E, Unit] =
    IO.unless(cond)(IO.raiseError(e))

  /** Executes the given sequence of tasks in parallel, non-deterministically
    * gathering their results, returning a task that will signal the sequence
    * of results once all tasks are finished.
    *
    * This function is the nondeterministic analogue of `sequence` and should
    * behave identically to `sequence` so long as there is no interaction between
    * the effects being gathered. However, unlike `sequence`, which decides on
    * a total order of effects, the effects in a `parSequence` are unordered with
    * respect to each other, the tasks being execute in parallel, not in sequence.
    *
    * Although the effects are unordered, we ensure the order of results
    * matches the order of the input sequence. Also see [[parSequenceUnordered]]
    * for the more efficient alternative.
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6
    *   Task.parSequence(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[parSequenceN]] for a version that limits parallelism.
    */
  def parSequence[E, A](
    in: Iterable[IO[E, A]]
  ): IO[E, List[A]] =
    TaskParSequence[E, A](in)

  /** Given a `Iterable[A]` and a function `A => IO[E, B]`,
    * nondeterministically apply the function to each element of the collection
    * and return a task that will signal a collection of the results once all
    * tasks are finished.
    *
    * This function is the nondeterministic analogue of `traverse` and should
    * behave identically to `traverse` so long as there is no interaction between
    * the effects being gathered. However, unlike `traverse`, which decides on
    * a total order of effects, the effects in a `parTraverse` are unordered with
    * respect to each other.
    *
    * Although the effects are unordered, we ensure the order of results
    * matches the order of the input sequence. Also see [[parTraverseUnordered]]
    * for the more efficient alternative.
    *
    * It's a generalized version of [[parSequence]].
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[parTraverseN]] for a version that limits parallelism.
    */
  def parTraverse[E, A, B](in: Iterable[A])(f: A => IO[E, B]): IO[E, List[B]] =
    UIO.eval(in.map(f)).flatMap(col => TaskParSequence[E, B](col))

  /** Executes the given sequence of tasks in parallel, non-deterministically
    * gathering their results, returning a task that will signal the sequence
    * of results once all tasks are finished.
    *
    * Implementation ensure there are at most `n` (= `parallelism` parameter) tasks
    * running concurrently and the results are returned in order.
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val tasks = List(
    *     Task(1 + 1).delayExecution(1.second),
    *     Task(2 + 2).delayExecution(2.second),
    *     Task(3 + 3).delayExecution(3.second),
    *     Task(4 + 4).delayExecution(4.second)
    *    )
    *
    *   // Yields 2, 4, 6, 8 after around 6 seconds
    *   Task.parSequenceN(2)(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[parSequence]] for a version that does not limit parallelism.
    */
  def parSequenceN[E, A](parallelism: Int)(in: Iterable[IO[E, A]]): IO[E, List[A]] =
    TaskParSequenceN[E, A](parallelism, in)

  /** Applies the provided function in a non-deterministic way to each element
    * of the input collection. The result will be signalled once all tasks
    * are finished with a success, or as soon as some task finishes with a
    * typed or terminal error.
    *
    * Note that his method has a fail-fast semantics: as soon as one of the tasks
    * fails (either in a typed or terminal manner), no subsequent tasks will be
    * executed and they will be cancelled.
    *
    * The final result will be a collection of success values, or a typed/fatal
    * error if at least one of the tasks finished without a success.
    *
    * This method allows specifying the parallelism level of the execution, i.e.
    * the maximum number of how many tasks should be running concurrently.
    *
    * Although the execution of the effects is unordered and non-deterministic,
    * the collection of results will preserve the order of the input collection.
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val numbers = List(1, 2, 3, 4)
    *
    *   // Yields 2, 4, 6, 8 after around 6 seconds
    *   IO.parTraverseN(2)(numbers)(n => IO(n + n).delayExecution(n.second))
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[parTraverse]] for a version that does not limit parallelism.
    */
  def parTraverseN[E, A, B](parallelism: Int)(in: Iterable[A])(f: A => IO[E, B]): IO[E, List[B]] =
    deferTotal(TaskParSequenceN(parallelism, in.map(f)))

  /** Processes the given collection of tasks in parallel and
    * nondeterministically gather the results without keeping the original
    * ordering of the given tasks.
    *
    * This function is similar to [[parSequence]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[parSequence]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6 (but order is NOT guaranteed)
    *   Task.parSequenceUnordered(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @param in is a list of tasks to execute
    */
  def parSequenceUnordered[E, A](in: Iterable[IO[E, A]]): IO[E, List[A]] =
    TaskParSequenceUnordered(in)

  /** Given a `Iterable[A]` and a function `A => IO[E, B]`,
    * nondeterministically apply the function to each element of the collection
    * without keeping the original ordering of the results.
    *
    * This function is similar to [[parTraverse]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[parTraverse]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * It's a generalized version of [[parSequenceUnordered]].
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def parTraverseUnordered[E, A, B](in: Iterable[A])(f: A => IO[E, B]): IO[E, List[B]] =
    IO.evalTotal(in.map(f)).flatMap(parSequenceUnordered)

  /** Yields a task that on evaluation will process the given tasks
    * in parallel, then apply the given mapping function on their results.
    *
    * Example:
    * {{{
    *   val task1 = Task(1 + 1)
    *   val task2 = Task(2 + 2)
    *
    *   // Yields 6
    *   Task.mapBoth(task1, task2)((a, b) => a + b)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def mapBoth[E, A1, A2, R](fa1: IO[E, A1], fa2: IO[E, A2])(f: (A1, A2) => R): IO[E, R] =
    TaskMapBoth(fa1, fa2)(f)

  /** Pairs 2 `IO` values, applying the given mapping function.
    *
    * Returns a new `IO` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[IO.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[IO.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = IO(1)
    *   val fa2 = IO(2)
    *
    *   // Yields Success(3)
    *   IO.map2(fa1, fa2) { (a, b) =>
    *     a + b
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.map2(fa1, IO.raiseError(new RuntimeException("boo"))) { (a, b: Int) =>
    *     a + b
    *   }
    * }}}
    */
  def map2[E, A1, A2, R](fa1: IO[E, A1], fa2: IO[E, A2])(f: (A1, A2) => R): IO[E, R] =
    for (a1 <- fa1; a2 <- fa2)
      yield f(a1, a2)

  /** Pairs 3 `IO` values, applying the given mapping function.
    *
    * Returns a new `IO` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[IO.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[IO.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = IO(1)
    *   val fa2 = IO(2)
    *   val fa3 = IO(3)
    *
    *   // Yields Success(6)
    *   IO.map3(fa1, fa2, fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.map3(fa1, IO.raiseError(new RuntimeException("boo")), fa3) { (a, b: Int, c) =>
    *     a + b + c
    *   }
    * }}}
    */
  def map3[E, A1, A2, A3, R](fa1: IO[E, A1], fa2: IO[E, A2], fa3: IO[E, A3])(f: (A1, A2, A3) => R): IO[E, R] =
    for (a1 <- fa1; a2 <- fa2; a3 <- fa3)
      yield f(a1, a2, a3)

  /** Pairs 4 `IO` values, applying the given mapping function.
    *
    * Returns a new `IO` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[IO.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[IO.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = IO(1)
    *   val fa2 = IO(2)
    *   val fa3 = IO(3)
    *   val fa4 = IO(4)
    *
    *   // Yields Success(10)
    *   IO.map4(fa1, fa2, fa3, fa4) { (a, b, c, d) =>
    *     a + b + c + d
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.map4(fa1, IO.raiseError(new RuntimeException("boo")), fa3, fa4) {
    *     (a, b: Int, c, d) => a + b + c + d
    *   }
    * }}}
    */
  def map4[E, A1, A2, A3, A4, R](fa1: IO[E, A1], fa2: IO[E, A2], fa3: IO[E, A3], fa4: IO[E, A4])(
    f: (A1, A2, A3, A4) => R
  ): IO[E, R] =
    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4)
      yield f(a1, a2, a3, a4)

  /** Pairs 5 `IO` values, applying the given mapping function.
    *
    * Returns a new `IO` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[IO.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[IO.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = IO(1)
    *   val fa2 = IO(2)
    *   val fa3 = IO(3)
    *   val fa4 = IO(4)
    *   val fa5 = IO(5)
    *
    *   // Yields Success(15)
    *   IO.map5(fa1, fa2, fa3, fa4, fa5) { (a, b, c, d, e) =>
    *     a + b + c + d + e
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.map5(fa1, IO.raiseError(new RuntimeException("boo")), fa3, fa4, fa5) {
    *     (a, b: Int, c, d, e) => a + b + c + d + e
    *   }
    * }}}
    */
  def map5[E, A1, A2, A3, A4, A5, R](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5]
  )(f: (A1, A2, A3, A4, A5) => R): IO[E, R] =
    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4; a5 <- fa5)
      yield f(a1, a2, a3, a4, a5)

  /** Pairs 6 `IO` values, applying the given mapping function.
    *
    * Returns a new `IO` reference that completes with the result
    * of mapping that function to their successful results, or in
    * failure in case either of them fails.
    *
    * This is a specialized [[IO.sequence]] operation and as such
    * the tasks are evaluated in order, one after another, the
    * operation being described in terms of [[IO.flatMap .flatMap]].
    *
    * {{{
    *   val fa1 = IO(1)
    *   val fa2 = IO(2)
    *   val fa3 = IO(3)
    *   val fa4 = IO(4)
    *   val fa5 = IO(5)
    *   val fa6 = IO(6)
    *
    *   // Yields Success(21)
    *   IO.map6(fa1, fa2, fa3, fa4, fa5, fa6) { (a, b, c, d, e, f) =>
    *     a + b + c + d + e + f
    *   }
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.map6(fa1, IO.raiseError(new RuntimeException("boo")), fa3, fa4, fa5, fa6) {
    *     (a, b: Int, c, d, e, f) => a + b + c + d + e + f
    *   }
    * }}}
    */
  def map6[E, A1, A2, A3, A4, A5, A6, R](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5],
    fa6: IO[E, A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): IO[E, R] =
    for (a1 <- fa1; a2 <- fa2; a3 <- fa3; a4 <- fa4; a5 <- fa5; a6 <- fa6)
      yield f(a1, a2, a3, a4, a5, a6)

  /** Pairs 2 `IO` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel.
    *
    * This is a specialized [[IO.parSequence]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = UIO(1)
    *   val fa2 = UIO(2)
    *
    *   // Yields Success(3)
    *   IO.parMap2(fa1, fa2) { (a, b) =>
    *     a + b
    *   }
    *
    *   val ex: Task[Int] = IO.raiseError(new RuntimeException("boo"))
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.parMap2(fa1, ex) { (a, b) =>
    *     a + b
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[IO.map2]] for sequential processing.
    */
  def parMap2[E, A1, A2, R](fa1: IO[E, A1], fa2: IO[E, A2])(f: (A1, A2) => R): IO[E, R] =
    IO.mapBoth(fa1, fa2)(f)

  /** Pairs 3 `IO` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel.
    *
    * This is a specialized [[IO.parSequence]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = UIO(1)
    *   val fa2 = UIO(2)
    *   val fa3 = UIO(3)
    *
    *   // Yields Success(6)
    *   IO.parMap3(fa1, fa2, fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    *
    *   val ex: Task[Int] = IO.raiseError(new RuntimeException("boo"))
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.parMap3(fa1, ex, fa3) { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[IO.map3]] for sequential processing.
    */
  def parMap3[E, A1, A2, A3, R](fa1: IO[E, A1], fa2: IO[E, A2], fa3: IO[E, A3])(f: (A1, A2, A3) => R): IO[E, R] = {
    val fa12 = parZip2(fa1, fa2)
    parMap2(fa12, fa3) { case ((a1, a2), a3) => f(a1, a2, a3) }
  }

  /** Pairs 4 `IO` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[IO.parSequence]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = UIO(1)
    *   val fa2 = UIO(2)
    *   val fa3 = UIO(3)
    *   val fa4 = UIO(4)
    *
    *   // Yields Success(10)
    *   IO.parMap4(fa1, fa2, fa3, fa4) { (a, b, c, d) =>
    *     a + b + c + d
    *   }
    *
    *   val ex: Task[Int] = IO.raiseError(new RuntimeException("boo"))
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.parMap4(fa1, ex, fa3, fa4) {
    *     (a, b, c, d) => a + b + c + d
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[IO.map4]] for sequential processing.
    */
  def parMap4[E, A1, A2, A3, A4, R](fa1: IO[E, A1], fa2: IO[E, A2], fa3: IO[E, A3], fa4: IO[E, A4])(
    f: (A1, A2, A3, A4) => R
  ): IO[E, R] = {
    val fa123 = parZip3(fa1, fa2, fa3)
    parMap2(fa123, fa4) { case ((a1, a2, a3), a4) => f(a1, a2, a3, a4) }
  }

  /** Pairs 5 `IO` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[IO.parSequence]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = UIO(1)
    *   val fa2 = UIO(2)
    *   val fa3 = UIO(3)
    *   val fa4 = UIO(4)
    *   val fa5 = UIO(5)
    *
    *   // Yields Success(15)
    *   IO.parMap5(fa1, fa2, fa3, fa4, fa5) { (a, b, c, d, e) =>
    *     a + b + c + d + e
    *   }
    *
    *   val ex: Task[Int] = IO.raiseError(new RuntimeException("boo"))
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.parMap5(fa1, ex, fa3, fa4, fa5) {
    *     (a, b, c, d, e) => a + b + c + d + e
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[IO.map5]] for sequential processing.
    */
  def parMap5[E, A1, A2, A3, A4, A5, R](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5]
  )(f: (A1, A2, A3, A4, A5) => R): IO[E, R] = {
    val fa1234 = parZip4(fa1, fa2, fa3, fa4)
    parMap2(fa1234, fa5) { case ((a1, a2, a3, a4), a5) => f(a1, a2, a3, a4, a5) }
  }

  /** Pairs 6 `IO` values, applying the given mapping function,
    * ordering the results, but not the side effects, the evaluation
    * being done in parallel if the tasks are async.
    *
    * This is a specialized [[IO.parSequence]] operation and as such
    * the tasks are evaluated in parallel, ordering the results.
    * In case one of the tasks fails, then all other tasks get
    * cancelled and the final result will be a failure.
    *
    * {{{
    *   val fa1 = UIO(1)
    *   val fa2 = UIO(2)
    *   val fa3 = UIO(3)
    *   val fa4 = UIO(4)
    *   val fa5 = UIO(5)
    *   val fa6 = UIO(6)
    *
    *   // Yields Success(21)
    *   IO.parMap6(fa1, fa2, fa3, fa4, fa5, fa6) { (a, b, c, d, e, f) =>
    *     a + b + c + d + e + f
    *   }
    *
    *   val ex: Task[Int] = IO.raiseError(new RuntimeException("boo"))
    *
    *   // Yields Failure(e), because the second arg is a failure
    *   IO.parMap6(fa1, ex, fa3, fa4, fa5, fa6) {
    *     (a, b, c, d, e, f) => a + b + c + d + e + f
    *   }
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * See [[IO.map6]] for sequential processing.
    */
  def parMap6[E, A1, A2, A3, A4, A5, A6, R](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5],
    fa6: IO[E, A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): IO[E, R] = {
    val fa12345 = parZip5(fa1, fa2, fa3, fa4, fa5)
    parMap2(fa12345, fa6) { case ((a1, a2, a3, a4, a5), a6) => f(a1, a2, a3, a4, a5, a6) }
  }

  /** Pairs two [[IO]] instances using [[parMap2]]. */
  def parZip2[E, A1, A2, R](fa1: IO[E, A1], fa2: IO[E, A2]): IO[E, (A1, A2)] =
    IO.mapBoth(fa1, fa2)((_, _))

  /** Pairs three [[IO]] instances using [[parMap3]]. */
  def parZip3[E, A1, A2, A3](fa1: IO[E, A1], fa2: IO[E, A2], fa3: IO[E, A3]): IO[E, (A1, A2, A3)] =
    parMap3(fa1, fa2, fa3)((a1, a2, a3) => (a1, a2, a3))

  /** Pairs four [[IO]] instances using [[parMap4]]. */
  def parZip4[E, A1, A2, A3, A4](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4]
  ): IO[E, (A1, A2, A3, A4)] =
    parMap4(fa1, fa2, fa3, fa4)((a1, a2, a3, a4) => (a1, a2, a3, a4))

  /** Pairs five [[IO]] instances using [[parMap5]]. */
  def parZip5[E, A1, A2, A3, A4, A5](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5]
  ): IO[E, (A1, A2, A3, A4, A5)] =
    parMap5(fa1, fa2, fa3, fa4, fa5)((a1, a2, a3, a4, a5) => (a1, a2, a3, a4, a5))

  /** Pairs six [[IO]] instances using [[parMap6]]. */
  def parZip6[E, A1, A2, A3, A4, A5, A6](
    fa1: IO[E, A1],
    fa2: IO[E, A2],
    fa3: IO[E, A3],
    fa4: IO[E, A4],
    fa5: IO[E, A5],
    fa6: IO[E, A6]
  ): IO[E, (A1, A2, A3, A4, A5, A6)] =
    parMap6(fa1, fa2, fa3, fa4, fa5, fa6)((a1, a2, a3, a4, a5, a6) => (a1, a2, a3, a4, a5, a6))

  /** Generates `cats.FunctionK` values for converting from `Task` to
    * supporting types (for which we have a [[IOLift]] instance).
    *
    * See [[cats.arrow.FunctionK https://typelevel.org/cats/datatypes/functionk.html]].
    *
    * {{{
    *   import cats.effect.{IO => CIO, _}
    *   import monix.bio._
    *   import java.io._
    *
    *   // Needed for converting from Task to something else, because we need
    *   // ConcurrentEffect[Task] capabilities, also provided by [[BIOApp]]
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   def open(file: File) =
    *     Resource[Task, InputStream](Task {
    *       val in = new FileInputStream(file)
    *       (in, Task(in.close()))
    *     })
    *
    *   // Lifting to a Resource of cats.effect.IO
    *   val res: Resource[CIO, InputStream] =
    *     open(new File("sample")).mapK(Task.liftTo[CIO])
    *
    *   // This was needed in order to process the resource
    *   // with a Task, instead of a Coeval
    *   res.use { in =>
    *     CIO {
    *       in.read()
    *     }
    *   }
    * }}}
    */
  def liftTo[F[_]](implicit F: IOLift[F]): (Task ~> F) = F

  /** Generates `cats.FunctionK` values for converting from `Task` to
    * supporting types (for which we have a `cats.effect.Async`) instance.
    *
    * See [[https://typelevel.org/cats/datatypes/functionk.html]].
    *
    * Prefer to use [[liftTo]], this alternative is provided in order to force
    * the usage of `cats.effect.Async`, since [[IOLift]] is lawless.
    */
  def liftToAsync[F[_]](implicit F: cats.effect.Async[F], eff: cats.effect.Effect[Task]): (Task ~> F) =
    IOLift.toAsync[F]

  /** Generates `cats.FunctionK` values for converting from `Task` to
    * supporting types (for which we have a [[cats.effect.Concurrent]]) instance.
    *
    * See [[https://typelevel.org/cats/datatypes/functionk.html]].
    *
    * Prefer to use [[liftTo]], this alternative is provided in order to force
    * the usage of [[cats.effect.Concurrent]], since [[IOLift]] is lawless.
    */
  def liftToConcurrent[F[_]](implicit
    F: cats.effect.Concurrent[F],
    eff: cats.effect.ConcurrentEffect[Task]
  ): (Task ~> F) =
    IOLift.toConcurrent[F]

  /** Returns a `F ~> Task` (`FunctionK`) for transforming any
    * supported data-type into [[Task]].
    *
    * Useful for `mapK` transformations, for example when working
    * with `Resource` or `Iterant`:
    *
    * {{{
    *   import cats.effect.{IO => CIO, _}
    *   import monix.bio._
    *   import java.io._
    *
    *   def open(file: File) =
    *     Resource[CIO, InputStream](CIO {
    *       val in = new FileInputStream(file)
    *       (in, CIO(in.close()))
    *     })
    *
    *   // Lifting to a Resource of Task
    *   val res: Resource[Task, InputStream] =
    *     open(new File("sample")).mapK(Task.liftFrom[CIO])
    * }}}
    */
  def liftFrom[F[_]](implicit F: IOLike[F]): (F ~> Task) = F

  /** Returns a `F ~> Task` (`FunctionK`) for transforming any
    * supported data-type, that implements [[cats.effect.ConcurrentEffect]],
    * into [[Task]].
    *
    * Useful for `mapK` transformations, for example when working
    * with `Resource` or `Iterant`.
    *
    * This is the less generic [[liftFrom]] operation, supplied
    * in order order to force the usage of
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html ConcurrentEffect]]
    * for where it matters.
    */
  def liftFromConcurrentEffect[F[_]](implicit F: ConcurrentEffect[F]): (F ~> Task) =
    liftFrom[F]

  /** Returns a `F ~> Task` (`FunctionK`) for transforming any
    * supported data-type, that implements `Effect`, into [[Task]].
    *
    * Useful for `mapK` transformations, for example when working
    * with `Resource` or `Iterant`.
    *
    * This is the less generic [[liftFrom]] operation, supplied
    * in order order to force the usage of
    * [[https://typelevel.org/cats-effect/typeclasses/effect.html Effect]]
    * for where it matters.
    */
  def liftFromEffect[F[_]](implicit F: Effect[F]): (F ~> Task) =
    liftFrom[F]

  /** Returns the current [[IO.Options]] configuration, which determine the
    * task's run-loop behavior.
    *
    * @see [[IO.executeWithOptions]]
    */
  val readOptions: UIO[Options] =
    IO
      .Async[Nothing, Options]((ctx, cb) => cb.onSuccess(ctx.options), trampolineBefore = false, trampolineAfter = true)

  /** Set of options for customizing the task's behavior.
    *
    * See [[IO.defaultOptions]] for the default `Options` instance
    * used by [[IO.runAsync]] or [[IO.runToFuture]].
    *
    * @param autoCancelableRunLoops  should be set to `true` in
    *                                case you want `flatMap` driven loops to be
    *                                auto-cancelable. Defaults to `true`.
    * @param localContextPropagation should be set to `true` in
    *                                case you want the [[monix.execution.misc.Local Local]]
    *                                variables to be propagated on async boundaries.
    *                                Defaults to `false`.
    */
  final case class Options(
    autoCancelableRunLoops: Boolean,
    localContextPropagation: Boolean
  ) {

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `true`.
      */
    def enableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = true)

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `false`.
      */
    def disableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = false)

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `true`.
      */
    def enableLocalContextPropagation: Options =
      copy(localContextPropagation = true)

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `false`.
      */
    def disableLocalContextPropagation: Options =
      copy(localContextPropagation = false)

    /** Enhances the options set with the features of the underlying
      * [[monix.execution.Scheduler Scheduler]].
      *
      * This enables for example the [[Options.localContextPropagation]]
      * in case the `Scheduler` is a
      * [[monix.execution.schedulers.TracingScheduler TracingScheduler]].
      */
    def withSchedulerFeatures(implicit s: Scheduler): Options = {
      val wLocals = s.features.contains(Scheduler.TRACING)
      if (wLocals == localContextPropagation)
        this
      else
        copy(localContextPropagation = wLocals || localContextPropagation)
    }
  }

  /** Default [[Options]] to use for [[IO]] evaluation,
    * thus:
    *
    *  - `autoCancelableRunLoops` is `true` by default
    *  - `localContextPropagation` is `false` by default
    *
    * On top of the JVM the default can be overridden by
    * setting the following system properties:
    *
    *  - `monix.environment.autoCancelableRunLoops`
    *    (`false`, `no` or `0` for disabling)
    *
    *  - `monix.environment.localContextPropagation`
    *    (`true`, `yes` or `1` for enabling)
    *
    * @see [[IO.Options]]
    */
  val defaultOptions: Options =
    Options(
      autoCancelableRunLoops = Platform.autoCancelableRunLoops,
      localContextPropagation = Platform.localContextPropagation
    )

  /** The `AsyncBuilder` is a type used by the [[IO.create]] builder,
    * in order to change its behavior based on the type of the
    * cancelation token.
    *
    * In combination with the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]],
    * this ends up providing a polymorphic [[IO.create]] that can
    * support multiple cancelation tokens optimally, i.e. without
    * implicit conversions and that can be optimized depending on
    * the `CancelToken` used - for example if `Unit` is returned,
    * then the yielded task will not be cancelable and the internal
    * implementation will not have to worry about managing it, thus
    * increasing performance.
    */
  abstract class AsyncBuilder[CancelationToken] {
    def create[E, A](register: (Scheduler, BiCallback[E, A]) => CancelationToken): IO[E, A]
  }

  object AsyncBuilder extends AsyncBuilder0 {

    /** Returns the implicit `AsyncBuilder` available in scope for the
      * given `CancelToken` type.
      */
    def apply[CancelationToken](implicit ref: AsyncBuilder[CancelationToken]): AsyncBuilder[CancelationToken] = ref

    /** For partial application of type parameters in [[IO.create]].
      *
      * Read about the
      * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type Technique]].
      */
    private[bio] final class CreatePartiallyApplied[E, A](val dummy: Boolean = true) extends AnyVal {

      def apply[CancelationToken](
        register: (Scheduler, BiCallback[E, A]) => CancelationToken
      )(implicit B: AsyncBuilder[CancelationToken]): IO[E, A] =
        B.create(register)
    }

    /** Implicit `AsyncBuilder` for non-cancelable tasks. */
    implicit val forUnit: AsyncBuilder[Unit] =
      new AsyncBuilder[Unit] {

        def create[E, A](register: (Scheduler, BiCallback[E, A]) => Unit): IO[E, A] =
          TaskCreate.async0(register)
      }

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * `cats.effect.IO` values for specifying cancelation actions,
      * see [[https://typelevel.org/cats-effect/ Cats Effect]].
      */
    implicit val forIO: AsyncBuilder[CIO[Unit]] =
      new AsyncBuilder[CIO[Unit]] {
        override def create[E, A](register: (Scheduler, BiCallback[E, A]) => CIO[Unit]): IO[E, A] =
          TaskCreate.cancelableIO(register)
      }

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * [[IO]] values for specifying cancelation actions.
      */
    implicit def forBIO[E]: AsyncBuilder[IO[E, Unit]] =
      forIORef.asInstanceOf[AsyncBuilder[IO[E, Unit]]]

    private[this] val forIORef: AsyncBuilder[IO[Any, Unit]] =
      new AsyncBuilder[IO[Any, Unit]] {
        override def create[E, A](register: (Scheduler, BiCallback[E, A]) => IO[Any, Unit]): IO[E, A] =
          TaskCreate.cancelable0[E, A](register.asInstanceOf[(Scheduler, BiCallback[E, A]) => CancelToken[IO[E, *]]])
      }

    /** Implicit `AsyncBuilder` for non-cancelable tasks built by a function
      * returning a [[monix.execution.Cancelable.Empty Cancelable.Empty]].
      *
      * This is a case of applying a compile-time optimization trick,
      * completely ignoring the provided cancelable value, since we've got
      * a guarantee that it doesn't do anything.
      */
    implicit def forCancelableDummy[T <: Cancelable.Empty]: AsyncBuilder[T] =
      forCancelableDummyRef.asInstanceOf[AsyncBuilder[T]]

    private[this] val forCancelableDummyRef: AsyncBuilder[Cancelable.Empty] =
      new AsyncBuilder[Cancelable.Empty] {

        def create[E, A](register: (Scheduler, BiCallback[E, A]) => Cancelable.Empty): IO[E, A] =
          TaskCreate.async0(register)
      }
  }

  private[IO] abstract class AsyncBuilder0 {

    /** Implicit `AsyncBuilder` for cancelable tasks, using
      * [[monix.execution.Cancelable Cancelable]] values for
      * specifying cancelation actions.
      */
    implicit def forCancelable[T <: Cancelable]: AsyncBuilder[T] =
      forCancelableRef.asInstanceOf[AsyncBuilder[T]]

    private[this] val forCancelableRef =
      new AsyncBuilder[Cancelable] {

        def create[E, A](register: (Scheduler, BiCallback[E, A]) => Cancelable): IO[E, A] =
          TaskCreate.cancelableCancelable(register)
      }
  }

  /** Internal API — The `Context` under which [[Task]] is supposed to be executed.
    *
    * This has been hidden in version 3.0.0-RC2, becoming an internal
    * implementation detail. Soon to be removed or changed completely.
    */
  private[bio] final case class Context[E](
    private val schedulerRef: Scheduler,
    options: Options,
    connection: TaskConnection[E],
    frameRef: FrameIndexRef,
    stackTracedContext: StackTracedContext
  ) {

    val scheduler: Scheduler = {
      if (options.localContextPropagation && !schedulerRef.features.contains(Scheduler.TRACING))
        TracingScheduler(schedulerRef)
      else
        schedulerRef
    }

    def shouldCancel: Boolean =
      options.autoCancelableRunLoops &&
        connection.isCanceled

    def executionModel: ExecutionModel =
      schedulerRef.executionModel

    def withScheduler(s: Scheduler): Context[E] =
      new Context(s, options, connection, frameRef, stackTracedContext)

    def withExecutionModel(em: ExecutionModel): Context[E] =
      new Context(schedulerRef.withExecutionModel(em), options, connection, frameRef, stackTracedContext)

    def withOptions(opts: Options): Context[E] =
      new Context(schedulerRef, opts, connection, frameRef, stackTracedContext)

    def withConnection[E1 >: E](conn: TaskConnection[E1]): Context[E1] =
      new Context(schedulerRef, options, conn, frameRef, stackTracedContext)
  }

  private[bio] object Context {
    // TODO: Should Task.start / startAndForget start a new stack trace?

    def apply[E](scheduler: Scheduler, options: Options): Context[E] =
      apply(scheduler, options, TaskConnection(), new StackTracedContext)

    def apply[E](
      scheduler: Scheduler,
      options: Options,
      connection: TaskConnection[E],
      stackTracedContext: StackTracedContext
    ): Context[E] = {
      val em = scheduler.executionModel
      val frameRef = FrameIndexRef(em)
      new Context(scheduler, options, connection, frameRef, stackTracedContext)
    }
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[bio] final case class Now[+A](value: A) extends IO[Nothing, A] {

    // Optimization to avoid the run-loop
    override def runAsyncOptF[E](
      cb: Either[Cause[Nothing], A] => Unit
    )(implicit s: Scheduler, opts: IO.Options): CancelToken[UIO] = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callSuccess(cb, value)
        IO.unit
      } else {
        super.runAsyncOptF(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runToFutureOpt(implicit
      s: Scheduler,
      opts: Options,
      ev: Nothing <:< Throwable
    ): CancelableFuture[A] = {
      CancelableFuture.successful(value)
    }

    // Optimization to avoid the run-loop
    override def runAsyncOpt(
      cb: Either[Cause[Nothing], A] => Unit
    )(implicit s: Scheduler, opts: Options): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callSuccess(cb, value)
        Cancelable.empty
      } else {
        super.runAsyncOpt(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncUncancelableOpt(cb: Either[Cause[Nothing], A] => Unit)(implicit
      s: Scheduler,
      opts: Options
    ): Unit = {
      if (s.executionModel != AlwaysAsyncExecution)
        BiCallback.callSuccess(cb, value)
      else
        super.runAsyncUncancelableOpt(cb)(s, opts)
    }

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit =
      ()
  }

  /** [[Task]] state describing an immediate exception. */
  private[bio] final case class Error[E](e: E) extends IO[E, Nothing] {

    // Optimization to avoid the run-loop
    override def runAsyncOptF[E1 >: E](
      cb: Either[Cause[E], Nothing] => Unit
    )(implicit s: Scheduler, opts: IO.Options): CancelToken[UIO] = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callError(cb, e)
        IO.unit
      } else {
        super.runAsyncOptF(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runToFutureOpt(implicit
      s: Scheduler,
      opts: Options,
      ev: E <:< Throwable
    ): CancelableFuture[Nothing] = {
      CancelableFuture.failed(ev(e))
    }

    // Optimization to avoid the run-loop
    override def runAsyncOpt(
      cb: Either[Cause[E], Nothing] => Unit
    )(implicit s: Scheduler, opts: Options): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callError(cb, e)
        Cancelable.empty
      } else {
        super.runAsyncOpt(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit = {
      e match {
        case th: Throwable => s.reportFailure(th)
        case _ => s.reportFailure(UncaughtErrorException(e))
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncUncancelableOpt(cb: Either[Cause[E], Nothing] => Unit)(implicit
      s: Scheduler,
      opts: Options
    ): Unit = {
      if (s.executionModel != AlwaysAsyncExecution)
        BiCallback.callError(cb, e)
      else
        super.runAsyncUncancelableOpt(cb)(s, opts)
    }
  }

  /** [[Task]] state describing an immediate exception. */
  private[bio] final case class Termination(e: Throwable) extends IO[Nothing, Nothing] {

    // Optimization to avoid the run-loop
    override def runAsyncOptF[E1 >: Nothing](
      cb: Either[Cause[Nothing], Nothing] => Unit
    )(implicit s: Scheduler, opts: IO.Options): CancelToken[UIO] = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callTermination(cb, e)
        IO.unit
      } else {
        super.runAsyncOptF(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runToFutureOpt(implicit
      s: Scheduler,
      opts: Options,
      ev: Nothing <:< Throwable
    ): CancelableFuture[Nothing] = {
      CancelableFuture.failed(e)
    }

    // Optimization to avoid the run-loop
    override def runAsyncOpt(
      cb: Either[Cause[Nothing], Nothing] => Unit
    )(implicit s: Scheduler, opts: Options): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callTermination(cb, e)
        Cancelable.empty
      } else {
        super.runAsyncOpt(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit =
      s.reportFailure(e)

    // Optimization to avoid the run-loop
    override def runAsyncUncancelableOpt(cb: Either[Cause[Nothing], Nothing] => Unit)(implicit
      s: Scheduler,
      opts: Options
    ): Unit = {
      if (s.executionModel != AlwaysAsyncExecution)
        BiCallback.callTermination(cb, e)
      else
        super.runAsyncUncancelableOpt(cb)(s, opts)
    }
  }

  /** [[IO]] state describing an non-strict synchronous value. */
  private[bio] final case class Eval[+A](thunk: () => A) extends IO[Throwable, A]

  /** [[IO]] state describing an non-strict synchronous value which doesn't throw any expected errors. */
  private[bio] final case class EvalTotal[+A](thunk: () => A) extends IO[Nothing, A]

  /** Internal state, the result of [[IO.defer]] */
  private[bio] final case class Suspend[+A](thunk: () => IO[Throwable, A]) extends IO[Throwable, A]

  /** Internal state, the result of [[IO.deferTotal]] */
  private[bio] final case class SuspendTotal[+E, +A](thunk: () => IO[E, A]) extends IO[E, A]

  /** Internal [[IO]] state that is the result of applying `flatMap`. */
  private[bio] final case class FlatMap[E, E1, A, +B](source: IO[E, A], f: A => IO[E1, B], trace: AnyRef)
      extends IO[E1, B]

  /** Internal [[IO]] state that is the result of applying `map`. */
  private[bio] final case class Map[S, +E, +A](source: IO[E, S], f: S => A, trace: AnyRef)
      extends IO[E, A] with (S => IO[E, A]) {

    def apply(value: S): IO[E, A] =
      new Now[A](f(value))

    override def toString: String =
      super[IO].toString
  }

  /** Constructs a lazy [[IO]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[cancelable0]].
    *
    * @param register is the side-effecting, callback-enabled function
    *        that starts the asynchronous computation and registers
    *        the callback to be called on completion
    * @param trampolineBefore is an optimization that instructs the
    *        run-loop to insert a trampolined async boundary before
    *        evaluating the `register` function
    */
  private[monix] final case class Async[E, +A](
    register: (Context[E], BiCallback[E, A]) => Unit,
    trampolineBefore: Boolean = false,
    trampolineAfter: Boolean = true,
    restoreLocals: Boolean = true,
    trace: AnyRef = null
  ) extends IO[E, A]

  /** For changing the context for the rest of the run-loop.
    *
    * WARNING: this is entirely internal API and shouldn't be exposed.
    */
  private[monix] final case class ContextSwitch[E, A](
    source: IO[E, A],
    modify: Context[E] => Context[E],
    restore: (A, E, Context[E], Context[E]) => Context[E]
  ) extends IO[E, A]

  private[monix] final case class Trace[E, A](source: IO[E, A], trace: IOEvent) extends IO[E, A]

  /** Internal API — starts the execution of a Task with a guaranteed
    * asynchronous boundary.
    */
  private[monix] def unsafeStartAsync[E, A](source: IO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    TaskRunLoop.restartAsync(source, context, cb, null, null, null)

  /** Internal API — a variant of [[unsafeStartAsync]] that tries to
    * detect if the `source` is known to fork and in such a case it
    * avoids creating an extraneous async boundary.
    */
  private[monix] def unsafeStartEnsureAsync[E, A](
    source: IO[E, A],
    context: Context[E],
    cb: BiCallback[E, A]
  ): Unit = {
    if (ForkedRegister.detect(source))
      unsafeStartNow(source, context, cb)
    else
      unsafeStartAsync(source, context, cb)
  }

  /** Internal API — starts the execution of a Task with a guaranteed
    * trampolined async boundary.
    */
  private[monix] def unsafeStartTrampolined[E, A](source: IO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    context.scheduler.execute(new TrampolinedRunnable {

      def run(): Unit =
        TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())
    })

  /** Internal API - starts the immediate execution of a Task.
    */
  private[monix] def unsafeStartNow[E, A](source: IO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())

  /** Internal, reusable reference. */
  private[this] val neverRef: Async[Nothing, Nothing] =
    Async[Nothing, Nothing]((_, _) => (), trampolineBefore = false, trampolineAfter = false)

  /** Internal, reusable reference. */
  private val nowConstructor: Any => UIO[Nothing] =
    ((a: Any) => new Now(a)).asInstanceOf[Any => UIO[Nothing]]

  private def raiseConstructor[E]: E => IO[E, Nothing] =
    raiseConstructorRef.asInstanceOf[E => IO[E, Nothing]]

  /** Internal, reusable reference. */
  private val raiseConstructorRef: Any => IO[Any, Nothing] =
    e => new Error(e)

  /** Used as optimization by [[IO.failed]]. */
  private object Failed extends StackFrame[Any, Any, UIO[Any]] {

    def apply(a: Any): UIO[Any] =
      Termination(new NoSuchElementException("failed"))

    def recover(e: Any): UIO[Any] =
      Now(e)
  }

  /** Used as optimization by [[IO.bimap]]. */
  private final case class Bimap[E, E1, A, B](fe: E => E1, fa: A => B) extends StackFrame[E, A, IO[E1, B]] {
    def apply(a: A) = Now(fa(a))
    def recover(e: E) = Error(fe(e))
  }

  /** Used as optimization by [[IO.mapError]]. */
  private final case class MapError[E, E1, A](fe: E => E1) extends StackFrame[E, A, IO[E1, A]] {
    def apply(a: A) = Now(a)
    def recover(e: E) = Error(fe(e))
  }

  /** Used as optimization by [[IO.redeem]]. */
  private final class Redeem[E, A, B](fe: E => B, fs: A => B) extends StackFrame[E, A, UIO[B]] {
    def apply(a: A): UIO[B] = new Now(fs(a))
    def recover(e: E): UIO[B] = new Now(fe(e))
  }

  /** Used as optimization by [[IO.redeemCause]]. */
  private final class RedeemFatal[E, A, B](fe: Cause[E] => B, fs: A => B)
      extends StackFrame.FatalStackFrame[E, A, UIO[B]] {
    override def apply(a: A): UIO[B] = new Now(fs(a))
    override def recover(e: E): UIO[B] = new Now(fe(Cause.Error(e)))
    override def recoverFatal(e: Throwable): UIO[B] = new Now(fe(Cause.Termination(e)))
  }

  /** Used as optimization by [[IO.attempt]]. */
  private object AttemptTask extends StackFrame[Any, Any, UIO[Either[Any, Any]]] {

    override def apply(a: Any): UIO[Either[Any, Any]] =
      new Now(new Right(a))

    override def recover(e: Any): UIO[Either[Any, Any]] =
      new Now(new Left(e))
  }

  /** Used as optimization by [[IO.materialize]]. */
  private object MaterializeTask extends StackFrame[Throwable, Any, UIO[Try[Any]]] {
    override def apply(a: Any): UIO[Try[Any]] =
      new Now(new Success(a))

    override def recover(e: Throwable): UIO[Try[Any]] =
      new Now(new Failure(e))
  }
}

private[bio] abstract class TaskInstancesLevel0 extends TaskInstancesLevel1 {

  /** Global instance for `cats.effect.Async` and for `cats.effect.Concurrent`.
    *
    * Implied are also `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError` and `cats.effect.Sync`.
    *
    * As trivia, it's named "catsAsync" and not "catsConcurrent" because
    * it represents the `cats.effect.Async` lineage, up until
    * `cats.effect.Effect`, which imposes extra restrictions, in our case
    * the need for a `Scheduler` to be in scope (see [[IO.catsEffect]]).
    * So by naming the lineage, not the concrete sub-type implemented, we avoid
    * breaking compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsAsync: CatsConcurrentForTask =
    CatsConcurrentForTask

  /** Global instance for `cats.Parallel`.
    *
    * The `Parallel` type class is useful for processing
    * things in parallel in a generic way, usable with
    * Cats' utils and syntax:
    *
    * {{{
    *   import cats.syntax.all._
    *   import scala.concurrent.duration._
    *
    *   val taskA = Task.sleep(1.seconds).map(_ => "a")
    *   val taskB = Task.sleep(2.seconds).map(_ => "b")
    *   val taskC = Task.sleep(3.seconds).map(_ => "c")
    *
    *   // Returns "abc" after 3 seconds
    *   (taskA, taskB, taskC).parMapN { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsParallel[E]: Parallel.Aux[IO[E, *], IO.Par[E, *]] =
    catsParallelAny.asInstanceOf[Parallel.Aux[IO[E, *], IO.Par[E, *]]]

  private[this] final lazy val catsParallelAny: CatsParallelForTask[Any] =
    new CatsParallelForTask[Any]

  /** Global instance for `cats.CommutativeApplicative`
    */
  implicit def commutativeApplicative[E]: CommutativeApplicative[IO.Par[E, *]] =
    commutativeApplicativeAny.asInstanceOf[CommutativeApplicative[IO.Par[E, *]]]

  private[this] final lazy val commutativeApplicativeAny: CommutativeApplicative[IO.Par[Any, *]] =
    catsParallelAny.applicative

  /** Given an `A` type that has a `cats.Monoid[A]` implementation,
    * then this provides the evidence that `IO[E, A]` also has
    * a `Monoid[ IO[E, A] ]` implementation.
    */
  implicit def catsMonoid[E, A](implicit A: Monoid[A]): Monoid[IO[E, A]] =
    new CatsMonadToMonoid[IO[E, *], A]()(new CatsBaseForTask[E], A)
}

private[bio] abstract class TaskInstancesLevel1 extends TaskInstancesLevel2 {

  /** Global instance for `cats.effect.Effect` and for
    * `cats.effect.ConcurrentEffect`.
    *
    * Implied are `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError`, `cats.effect.Sync` and `cats.effect.Async`.
    *
    * Note this is different from
    * [[monix.bio.IO.catsAsync IO.catsAsync]] because we need an
    * implicit [[monix.execution.Scheduler Scheduler]] in scope in
    * order to trigger the execution of a `Task`. It's also lower
    * priority in order to not trigger conflicts, because
    * `Effect <: Async` and `ConcurrentEffect <: Concurrent with Effect`.
    *
    * As trivia, it's named "catsEffect" and not "catsConcurrentEffect"
    * because it represents the `cats.effect.Effect` lineage, as in the
    * minimum that this value will support in the future. So by naming the
    * lineage, not the concrete sub-type implemented, we avoid breaking
    * compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    *
    * @param s is a [[monix.execution.Scheduler Scheduler]] that needs
    *        to be available in scope
    */
  implicit def catsEffect(implicit
    s: Scheduler,
    opts: IO.Options = IO.defaultOptions
  ): CatsConcurrentEffectForTask = {
    new CatsConcurrentEffectForTask
  }

  /** Given an `A` type that has a `cats.Semigroup[A]` implementation,
    * then this provides the evidence that `IO[E, A]` also has
    * a `Semigroup[ IO[E, A] ]` implementation.
    *
    * This has a lower-level priority than [[IO.catsMonoid]]
    * in order to avoid conflicts.
    */
  implicit def catsSemigroup[E, A](implicit A: Semigroup[A]): Semigroup[IO[E, A]] =
    new CatsMonadToSemigroup[IO[E, *], A]()(monadError[E], A)
}

private[bio] abstract class TaskInstancesLevel2 extends TaskParallelNewtype {

  implicit def monadError[E]: CatsBaseForTask[E] =
    monadErrorAny.asInstanceOf[CatsBaseForTask[E]]

  private[this] final lazy val monadErrorAny: CatsBaseForTask[Any] =
    new CatsBaseForTask[Any]
}

private[bio] abstract class TaskParallelNewtype extends TaskContextShift {

  /** Newtype encoding for a `Task` data type that has a [[cats.Applicative]]
    * capable of doing parallel processing in `ap` and `map2`, needed
    * for implementing `cats.Parallel`.
    *
    * Helpers are provided for converting back and forth in `Par.apply`
    * for wrapping any `Task` value and `Par.unwrap` for unwrapping.
    *
    * The encoding is based on the "newtypes" project by
    * Alexander Konovalov, chosen because it's devoid of boxing issues and
    * a good choice until opaque types will land in Scala.
    */
  type Par[+E, +A] = Par.Type[E, A]

  /** Newtype encoding, see the [[IO.Par]] type alias
    * for more details.
    */
  object Par extends Newtype2[IO]
}

private[bio] abstract class TaskContextShift extends TaskTimers {

  /** Default, pure, globally visible `cats.effect.ContextShift`
    * implementation that shifts the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[IO.runToFuture]]).
    */
  implicit def contextShift[E]: ContextShift[IO[E, *]] =
    contextShiftAny.asInstanceOf[ContextShift[IO[E, *]]]

  private[this] final val contextShiftAny: ContextShift[IO[Any, *]] =
    new ContextShift[IO[Any, *]] {

      override def shift: IO[Any, Unit] =
        IO.shift

      override def evalOn[A](ec: ExecutionContext)(fa: IO[Any, A]): IO[Any, A] =
        ec match {
          case ref: Scheduler => fa.executeOn(ref, forceAsync = true)
          case _ => fa.executeOn(Scheduler(ec), forceAsync = true)
        }

    }

  /** Builds a `cats.effect.ContextShift` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def contextShift[E](s: Scheduler): ContextShift[IO[E, *]] =
    new ContextShift[IO[E, *]] {

      override def shift: IO[E, Unit] =
        IO.shift(s)

      override def evalOn[A](ec: ExecutionContext)(fa: IO[E, A]): IO[E, A] =
        ec match {
          case ref: Scheduler => fa.executeOn(ref, forceAsync = true)
          case _ => fa.executeOn(Scheduler(ec), forceAsync = true)
        }

    }
}

private[bio] abstract class TaskTimers extends TaskClocks {

  /** Default, pure, globally visible `cats.effect.Timer`
    * implementation that defers the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[IO.runToFuture]]).
    */
  implicit def timer[E]: Timer[IO[E, *]] =
    timerAny.asInstanceOf[Timer[IO[E, *]]]

  private[this] final val timerAny: Timer[IO[Any, *]] =
    new Timer[IO[Any, *]] {

      override def sleep(duration: FiniteDuration): IO[Any, Unit] =
        IO.sleep(duration)

      override def clock: Clock[IO[Any, *]] =
        IO.clock
    }

  /** Builds a `cats.effect.Timer` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def timer[E](s: Scheduler): Timer[IO[E, *]] =
    new Timer[IO[E, *]] {

      override def sleep(duration: FiniteDuration): IO[E, Unit] =
        IO.sleep(duration).executeOn(s)

      override def clock: Clock[IO[E, *]] =
        IO.clock(s)
    }
}

private[bio] abstract class TaskClocks extends IODeprecated.Companion {

  /** Default, pure, globally visible `cats.effect.Clock`
    * implementation that defers the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[IO.runToFuture]]).
    */
  def clock[E]: Clock[IO[E, *]] =
    clockAny.asInstanceOf[Clock[IO[E, *]]]

  private[this] final val clockAny: Clock[IO[Any, *]] =
    new Clock[IO[Any, *]] {

      override def realTime(unit: TimeUnit): IO[Any, Long] =
        IO.deferAction(sc => IO.now(sc.clockRealTime(unit)))

      override def monotonic(unit: TimeUnit): IO[Any, Long] =
        IO.deferAction(sc => IO.now(sc.clockMonotonic(unit)))
    }

  /** Builds a `cats.effect.Clock` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def clock[E](s: Scheduler): Clock[IO[E, *]] =
    new Clock[IO[E, *]] {

      override def realTime(unit: TimeUnit): IO[E, Long] =
        IO.evalTotal(s.clockRealTime(unit))

      override def monotonic(unit: TimeUnit): IO[E, Long] =
        IO.evalTotal(s.clockMonotonic(unit))
    }
}
