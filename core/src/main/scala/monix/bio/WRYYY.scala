/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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

import java.util.concurrent.RejectedExecutionException

import cats.effect.{CancelToken, Fiber => _}
import monix.bio.internal.{FrameIndexRef, StackFrame, TaskConnection, TaskDoOnCancel, TaskEvalAsync, TaskExecuteWithModel, TaskExecuteWithOptions, TaskRunLoop, TaskShift, UnsafeCancelUtils}
import monix.execution.annotations.UnsafeBecauseImpure
import monix.execution.internal.Platform
import monix.execution.misc.Local
import monix.execution.schedulers.TracingScheduler
import monix.execution.{Callback, Scheduler, _}
import Platform.fusionMaxStackDepth
import monix.bio

import scala.concurrent.ExecutionContext

sealed abstract class WRYYY[+E, +A] extends Serializable {
  import WRYYY._

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
    *   def increment1(sample: Task[Int]): CancelableFuture[Int] = {
    *     // No reason to trigger `runAsync` for this operation
    *     sample.runToFuture.map(_ + 1)
    *   }
    *
    *   // ANTI-PATTERN 2: blocking threads makes it worse than (1)
    *   def increment2(sample: Task[Int]): Int = {
    *     // Blocking threads is totally unnecessary
    *     val x = Await.result(sample.runToFuture, 5.seconds)
    *     x + 1
    *   }
    *
    *   // ANTI-PATTERN 3: this is even WORSE than (2)!
    *   def increment3(sample: Task[Int]): Task[Int] = {
    *     // Triggering side-effects, but misleading users/readers
    *     // into thinking this function is pure via the return type
    *     Task.fromFuture(sample.runToFuture.map(_ + 1))
    *   }
    * }}}
    *
    * Instead prefer the pure versions. `Task` has its own [[map]],
    * [[flatMap]], [[onErrorHandleWith]] or [[bracketCase]], which
    * are really powerful and can allow you to operate on a task
    * in however way you like without escaping Task's context and
    * triggering unwanted side-effects.
    *
    * @param s $schedulerDesc
    * @return $runAsyncToFutureReturn
    */
  @UnsafeBecauseImpure
  final def runToFuture(implicit s: Scheduler): CancelableFuture[A] =
    runToFutureOpt(s, WRYYY.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runToFuture]],
    * but includes the ability to specify [[monix.bio.WRYYY.Options Options]]
    * that can modify the behavior of the run-loop.
    *
    * This is the configurable version of [[runToFuture]].
    * It allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See [[WRYYY.Options]]. Example:
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- TaskLocal(0)
    *       _     <- local.write(100)
    *       _     <- Task.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of TaskLocal via:
    *   implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    *   // Actual execution that depends on these custom options:
    *   task.runToFutureOpt
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
  def runToFutureOpt(implicit s: Scheduler, opts: Options): CancelableFuture[A] = {
    val opts2 = opts.withSchedulerFeatures
    Local
      .bindCurrentIf(opts2.localContextPropagation) {
        TaskRunLoop.startFuture(this, s, opts2)
      }
  }

  /** Triggers the asynchronous execution, with a provided callback
    * that's going to be called at some point in the future with
    * the final result.
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
    *
    *   // Triggering the task's execution:
    *   val f = task.runAsync {
    *     case Right(str: String) =>
    *       println(s"Received: $$str")
    *     case Left(e) =>
    *       global.reportFailure(e)
    *   }
    *
    *   // Or in case we change our mind
    *   f.cancel()
    * }}}
    *
    * $callbackDesc
    *
    * Example, equivalent to the above:
    *
    * {{{
    *   import monix.execution.Callback
    *
    *   task.runAsync(new Callback[Throwable, String] {
    *     def onSuccess(str: String) =
    *       println(s"Received: $$str")
    *     def onError(e: Throwable) =
    *       global.reportFailure(e)
    *   })
    * }}}
    *
    * Example equivalent with [[runAsyncAndForget]]:
    *
    * {{{
    *   task.runAsync(Callback.empty)
    * }}}
    *
    * Completing a [[scala.concurrent.Promise]]:
    *
    * {{{
    *   import scala.concurrent.Promise
    *
    *   val p = Promise[String]()
    *   task.runAsync(Callback.fromPromise(p))
    * }}}
    *
    * $unsafeRun
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @return $cancelableDesc
    */
  @UnsafeBecauseImpure
  final def runAsync(cb: Either[E, A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsyncOpt(cb)(s, WRYYY.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsync]], but
    * includes the ability to specify [[monix.bio.WRYYY.Options WRYYY.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * Example:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- TaskLocal(0)
    *       _     <- local.write(100)
    *       _     <- Task.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of TaskLocal via:
    *   implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    *
    *   // Actual execution that depends on these custom options:
    *   task.runAsyncOpt {
    *     case Right(value) =>
    *       println(s"Received: $$value")
    *     case Left(e) =>
    *       global.reportFailure(e)
    *   }
    * }}}
    *
    * See [[WRYYY.Options]].
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
  def runAsyncOpt(cb: Either[E, A] => Unit)(implicit s: Scheduler, opts: Options): Cancelable = {
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
    *     Task(println(s"Finished via exit code: $$exitCode"))
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
  final def runAsyncF(cb: Either[E, A] => Unit)(implicit s: Scheduler): CancelToken[Task] =
    runAsyncOptF(cb)(s, WRYYY.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsyncF]], but
    * includes the ability to specify [[monix.bio.Task.Options Task.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
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
  def runAsyncOptF(cb: Either[E, A] => Unit)(implicit s: Scheduler, opts: Options): CancelToken[Task] = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop.startLight(this, s, opts2, Callback.fromAttempt(cb).asInstanceOf[Callback[Any, A]]) // TODO: should it be E,A?
    }
  }

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  final def flatMap[E1 >: E, B](f: A => WRYYY[E1, B]): WRYYY[E1, B] =
    FlatMap(this, f)

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
  final def map[B](f: A => B): WRYYY[E, B] =
    this match {
      case Map(source, g, index) =>
        // Allowed to do a fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `monix.execution.internal.Platform` for details.
        if (index != fusionMaxStackDepth) Map(source, g.andThen(f), index + 1)
        else Map(this, f, 0)
      case _ =>
        Map(this, f, 0)
    }

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in
    * [[Task.runAsync]] or [[Task.runToFuture]].
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
  final def executeAsync: WRYYY[E, A] =
    WRYYY.shift.flatMap(_ => this)

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
  final def executeWithModel(em: ExecutionModel): WRYYY[E, A] =
    TaskExecuteWithModel(this, em)

  /** Returns a new task that will execute the source with a different
    * set of [[WRYYY.Options Options]].
    *
    * This allows fine-tuning the default options. Example:
    *
    * {{{
    *   Task(1 + 1).executeWithOptions(_.enableAutoCancelableRunLoops)
    * }}}
    *
    * @param f is a function that takes the source's current set of
    *        [[WRYYY.Options options]] and returns a modified set of
    *        options that will be used to execute the source
    *        upon `runAsync`
    */
  final def executeWithOptions(f: Options => Options): WRYYY[E, A] =
    TaskExecuteWithOptions(this, f)

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
    runAsyncAndForgetOpt(s, WRYYY.defaultOptions)

  /** Triggers the asynchronous execution in a "fire and forget"
    * fashion, like normal [[runAsyncAndForget]], but includes the
    * ability to specify [[monix.bio.WRYYY.Options TasWRYYY.Options]] that
    * can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[WRYYY.Options]].
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
  def runAsyncAndForgetOpt(implicit s: Scheduler, opts: WRYYY.Options): Unit =
    runAsyncUncancelableOpt(Callback.empty)(s, opts)

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
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: Task[String] =
    *     for {
    *       _ <- Task.sleep(3.seconds)
    *       r <- Task { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *   // Triggering the task's execution, without receiving any
    *   // cancelation tokens
    *   task.runAsyncUncancelable {
    *     case Right(str) =>
    *       println(s"Received: $$str")
    *     case Left(e) =>
    *       global.reportFailure(e)
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
  final def runAsyncUncancelable(cb: Either[E, A] => Unit)(implicit s: Scheduler): Unit =
    runAsyncUncancelableOpt(cb)(s, WRYYY.defaultOptions)

  /** Triggers the asynchronous execution in uncancelable mode,
    * like [[runAsyncUncancelable]], but includes the ability to
    * specify [[monix.bio.WRYYY.Options WRYYY.Options]] that can modify
    * the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[WRYYY.Options]].
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
  def runAsyncUncancelableOpt(cb: Either[E, A] => Unit)(implicit s: Scheduler, opts: WRYYY.Options): Unit = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop.startLight(this, s, opts2, Callback.fromAttempt(cb).asInstanceOf[Callback[Any, A]], isCancelable = false)
    }
  }

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
  final def >>[E1 >: E, B](tb: => WRYYY[E1, B]): WRYYY[E1, B] =
    this.flatMap(_ => tb)

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
  final def doOnCancel(callback: UIO[Unit]): WRYYY[E, A] =
    TaskDoOnCancel(this, callback)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  final def onErrorRecoverWith[E1 >: E, B >: A](pf: PartialFunction[E, WRYYY[E1, B]]): WRYYY[E1, B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, raiseConstructor[E]))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  final def onErrorHandleWith[E1, B >: A](f: E => WRYYY[E1, B]): WRYYY[E1, B] =
    FlatMap(this, new StackFrame.ErrorHandler(f, nowConstructor))

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  final def onErrorFallbackTo[E1, B >: A](that: WRYYY[E1, B]): WRYYY[E1, B] =
    onErrorHandleWith(_ => that)

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
  final def onErrorRecover[E1 >: E, U >: A](pf: PartialFunction[E, U]): WRYYY[E1, U] =
    onErrorRecoverWith(pf.andThen(nowConstructor))

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
    WRYYY.FlatMap(this, new WRYYY.Redeem(recover, map))

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
  def redeemWith[E1, B](recover: E => WRYYY[E1, B], bind: A => WRYYY[E1, B]): WRYYY[E1, B] =
    WRYYY.FlatMap(this, new StackFrame.RedeemWith(recover, bind))
}

object WRYYY {

  /** Lifts the given thunk in the `Task` context, processing it synchronously
    * when the task gets evaluated.
    *
    * This is an alias for:
    *
    * {{{
    *   val thunk = () => 42
    *   Task.eval(thunk())
    * }}}
    *
    * WARN: behavior of `Task.apply` has changed since 3.0.0-RC2.
    * Before the change (during Monix 2.x series), this operation was forcing
    * a fork, being equivalent to the new [[WRYYY.evalAsync]].
    *
    * Switch to [[WRYYY.evalAsync]] if you wish the old behavior, or combine
    * [[WRYYY.eval]] with [[WRYYY.executeAsync]].
    */
  def apply[A](a: => A): Task[A] =
    eval(a)

  /** Returns a `Task` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[A](a: A): UIO[A] =
    WRYYY.Now(a)

  /** Lifts a value into the task context. Alias for [[now]]. */
  def pure[A](a: A): UIO[A] = now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def raiseError[E](ex: E): WRYYY[E, Nothing] =
    Error(ex)

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[E, A](fa: => WRYYY[E, A]): WRYYY[E, A] =
    Suspend(fa _)

  /** Alias for [[defer]]. */
  def suspend[E, A](fa: => WRYYY[E, A]): WRYYY[E, A] =
    Suspend(fa _)

  /** Promote a non-strict value, a thunk, to a `Task`, catching exceptions
    * in the process.
    *
    * Note that since `Task` is not memoized or strict, this will recompute the
    * value each time the `Task` is executed, behaving like a function.
    *
    * @param a is the thunk to process on evaluation
    */
  def eval[A](a: => A): Task[A] =
    Eval(a _)

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
    TaskEvalAsync(a _)

  /** Alias for [[eval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: UIO[A] = neverRef

  /** A `Task[Unit]` provided for convenience. */
  val unit: UIO[Unit] = Now(())

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
    * (see [[WRYYY.Options]]), so this isn't strictly needed, unless you
    * want to fine tune the cancelation boundaries.
    */
  val cancelBoundary: UIO[Unit] =
    WRYYY.Async { (ctx, cb) =>
      if (!ctx.connection.isCanceled) cb.onSuccess(())
    }

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another thread or call stack, managed by
    * the default [[monix.execution.Scheduler Scheduler]].
    *
    * This is the equivalent of `IO.shift`, except that Monix's `Task`
    * gets executed with an injected `Scheduler` in [[WRYYY.runAsync]] or
    * in [[WRYYY.runToFuture]] and that's going to be the `Scheduler`
    * responsible for the "shift".
    *
    * $shiftDesc
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

  /** Returns the current [[WRYYY.Options]] configuration, which determine the
    * task's run-loop behavior.
    *
    * @see [[WRYYY.executeWithOptions]]
    */
  val readOptions: Task[Options] =
    WRYYY.Async((ctx, cb) => cb.onSuccess(ctx.options), trampolineBefore = false, trampolineAfter = true)

  /** Internal API — The `Context` under which [[Task]] is supposed to be executed.
    *
    * This has been hidden in version 3.0.0-RC2, becoming an internal
    * implementation detail. Soon to be removed or changed completely.
    */
  private[bio] final case class Context(
    private val schedulerRef: Scheduler,
    options: Options,
    connection: TaskConnection,
    frameRef: FrameIndexRef) {

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

    def withScheduler(s: Scheduler): Context =
      new Context(s, options, connection, frameRef)

    def withExecutionModel(em: ExecutionModel): Context =
      new Context(schedulerRef.withExecutionModel(em), options, connection, frameRef)

    def withOptions(opts: Options): Context =
      new Context(schedulerRef, opts, connection, frameRef)

    def withConnection(conn: TaskConnection): Context =
      new Context(schedulerRef, options, conn, frameRef)
  }

  private[bio] object Context {

    def apply(scheduler: Scheduler, options: Options): Context =
      apply(scheduler, options, TaskConnection())

    def apply(scheduler: Scheduler, options: Options, connection: TaskConnection): Context = {
      val em = scheduler.executionModel
      val frameRef = FrameIndexRef(em)
      new Context(scheduler, options, connection, frameRef)
    }
  }

  /** Set of options for customizing the task's behavior.
    *
    * See [[WRYYY.defaultOptions]] for the default `Options` instance
    * used by [[WRYYY.runAsync]] or [[WRYYY.runToFuture]].
    *
    * @param autoCancelableRunLoops should be set to `true` in
    *        case you want `flatMap` driven loops to be
    *        auto-cancelable. Defaults to `true`.
    *
    * @param localContextPropagation should be set to `true` in
    *        case you want the [[monix.execution.misc.Local Local]]
    *        variables to be propagated on async boundaries.
    *        Defaults to `false`.
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

    /**
      * Enhances the options set with the features of the underlying
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

  /** Default [[Options]] to use for [[WRYYY]] evaluation,
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
    * @see [[WRYYY.Options]]
    */
  val defaultOptions: Options =
    Options(
      autoCancelableRunLoops = Platform.autoCancelableRunLoops,
      localContextPropagation = Platform.localContextPropagation
    )

  // TODO: add runloop optimizations

  /** [[Task]] state describing an immediate synchronous value. */
  private[bio] final case class Now[+A](value: A) extends WRYYY[Nothing, A]

  // TODO: add runloop optimizations

  /** [[Task]] state describing an immediate exception. */
  private[bio] final case class Error[E](e: E) extends WRYYY[E, Nothing]

  /** [[WRYYY]] state describing an non-strict synchronous value. */
  private[bio] final case class Eval[+A](thunk: () => A) extends WRYYY[Throwable, A]

  /** Internal state, the result of [[WRYYY.defer]] */
  private[bio] final case class Suspend[+E, +A](thunk: () => WRYYY[E, A]) extends WRYYY[E, A]

  /** Internal [[WRYYY]] state that is the result of applying `flatMap`. */
  private[bio] final case class FlatMap[E, E1, A, +B](source: WRYYY[E, A], f: A => WRYYY[E1, B]) extends WRYYY[E1, B]

  /** Internal [[WRYYY]] state that is the result of applying `map`. */
  private[bio] final case class Map[S, +E, +A](source: WRYYY[E, S], f: S => A, index: Int)
      extends WRYYY[E, A] with (S => WRYYY[E, A]) {

    def apply(value: S): WRYYY[E, A] =
      new Now[A](f(value))

    override def toString: String =
      super[WRYYY].toString
  }

  /** Constructs a lazy [[WRYYY]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[cancelable0]].
    *
    * @param register is the side-effecting, callback-enabled function
    *        that starts the asynchronous computation and registers
    *        the callback to be called on completion
    *
    * @param trampolineBefore is an optimization that instructs the
    *        run-loop to insert a trampolined async boundary before
    *        evaluating the `register` function
    */
  private[monix] final case class Async[E, +A](
    register: (Context, Callback[E, A]) => Unit,
    trampolineBefore: Boolean = false,
    trampolineAfter: Boolean = true,
    restoreLocals: Boolean = true)
      extends WRYYY[E, A]

  /** For changing the context for the rest of the run-loop.
    *
    * WARNING: this is entirely internal API and shouldn't be exposed.
    */
  private[monix] final case class ContextSwitch[E, A](
    source: WRYYY[E, A],
    modify: Context => Context,
    restore: (A, E, Context, Context) => Context)
      extends WRYYY[E, A]

  /**
    * Internal API - starts the immediate execution of a Task.
    */
  private[monix] def unsafeStartNow[E, A](source: WRYYY[E, A], context: Context, cb: Callback[E, A]): Unit =
    TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())

  /** Internal, reusable reference. */
  private[this] val neverRef: Async[Nothing, Nothing] =
    Async((_, _) => (), trampolineBefore = false, trampolineAfter = false)

  /** Internal, reusable reference. */
  private val nowConstructor: Any => UIO[Nothing] =
    ((a: Any) => new Now(a)).asInstanceOf[Any => UIO[Nothing]]

  /** Internal, reusable reference. */
  private def raiseConstructor[E]: E => WRYYY[E, Nothing] =
    (e: E) => new Error(e) // TODO: reuse it

  /** Used as optimization by [[WRYYY.redeem]]. */
  private final class Redeem[E, A, B](fe: E => B, fs: A => B) extends StackFrame[E, A, UIO[B]] {
    def apply(a: A): UIO[B] = new Now(fs(a))
    def recover(e: E): UIO[B] = new Now(fe(e))
  }
}
