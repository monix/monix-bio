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

package monix.bio.internal

import cats.effect.ExitCase
import cats.effect.ExitCase.{Canceled, Completed, Error}
import monix.bio.Task.{Context, ContextSwitch}
import monix.bio.internal.StackFrame.FatalStackFrame
import monix.execution.exceptions.UncaughtErrorException
import monix.bio.{BiCallback, Cause, Task, UIO}
import monix.execution.atomic.Atomic
import monix.execution.internal.Platform
import scala.concurrent.Promise
import scala.util.control.NonFatal

private[monix] object TaskBracket {

  // -----------------------------------------------------------------
  // Task.guaranteeCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  def guaranteeCase[E, A](task: Task[E, A], finalizer: ExitCase[Cause[E]] => UIO[Unit]): Task[E, A] =
    Task.Async(
      new ReleaseStart(task, finalizer),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class ReleaseStart[E, A](source: Task[E, A], release: ExitCase[Cause[E]] => UIO[Unit])
      extends ((Context[E], BiCallback[E, A]) => Unit) {

    def apply(ctx: Context[E], cb: BiCallback[E, A]): Unit = {
      implicit val s = ctx.scheduler

      val conn = ctx.connection
      val frame = new EnsureReleaseFrame[E, A](ctx, release)
      val onNext = source.flatMap(frame)

      // Registering our cancelable token ensures that in case
      // cancellation is detected, `release` gets called
      conn.push(frame.cancel)

      // Race condition check, avoiding starting `source` in case
      // the connection was already cancelled — n.b. we don't need
      // to trigger `release` otherwise, because it already happened
      if (!conn.isCanceled) {
        Task.unsafeStartNow(onNext, ctx, cb)
      }
    }
  }

  private final class EnsureReleaseFrame[E, A](ctx: Context[E], releaseFn: ExitCase[Cause[E]] => UIO[Unit])
      extends BaseReleaseFrame[E, Unit, A](ctx, ()) {

    def releaseOnSuccess(a: Unit, b: A): UIO[Unit] =
      releaseFn(ExitCase.Completed)

    def releaseOnError(a: Unit, e: Cause[E]): UIO[Unit] =
      releaseFn(ExitCase.Error(e))

    def releaseOnCancel(a: Unit): UIO[Unit] =
      releaseFn(ExitCase.Canceled)
  }

  // -----------------------------------------------------------------
  // Task.bracketE
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.bio.Task.bracket]] and [[monix.bio.Task.bracketCase]]
    */
  def either[E, A, B](
    acquire: Task[E, A],
    use: A => Task[E, B],
    release: (A, Either[Option[Cause[E]], B]) => UIO[Unit]
  ): Task[E, B] = {

    Task.Async(
      new StartE(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )
  }

  private final class StartE[E, A, B](
    acquire: Task[E, A],
    use: A => Task[E, B],
    release: (A, Either[Option[Cause[E]], B]) => UIO[Unit]
  ) extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context[E], value: A) =
      new ReleaseFrameE(ctx, value, release)
  }

  private final class ReleaseFrameE[E, A, B](
    ctx: Context[E],
    a: A,
    release: (A, Either[Option[Cause[E]], B]) => UIO[Unit]
  ) extends BaseReleaseFrame[E, A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): UIO[Unit] =
      release(a, Right(b))

    def releaseOnError(a: A, e: Cause[E]): UIO[Unit] =
      release(a, Left(Some(e)))

    def releaseOnCancel(a: A): UIO[Unit] =
      release(a, leftNone)
  }

  // -----------------------------------------------------------------
  // Task.bracketCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.bio.Task.bracketE]]
    */
  def exitCase[E, A, B](
    acquire: Task[E, A],
    use: A => Task[E, B],
    release: (A, ExitCase[Cause[E]]) => UIO[Unit]
  ): Task[E, B] =
    Task.Async(
      new StartCase(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class StartCase[E, A, B](
    acquire: Task[E, A],
    use: A => Task[E, B],
    release: (A, ExitCase[Cause[E]]) => UIO[Unit]
  ) extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context[E], value: A) =
      new ReleaseFrameCase(ctx, value, release)
  }

  private final class ReleaseFrameCase[E, A, B](ctx: Context[E], a: A, release: (A, ExitCase[Cause[E]]) => UIO[Unit])
      extends BaseReleaseFrame[E, A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): UIO[Unit] =
      release(a, Completed)

    def releaseOnError(a: A, e: Cause[E]): UIO[Unit] =
      release(a, Error(e))

    def releaseOnCancel(a: A): UIO[Unit] =
      release(a, Canceled)
  }

  // -----------------------------------------------------------------
  // Base Implementation
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  private abstract class BaseStart[E, A, B](acquire: Task[E, A], use: A => Task[E, B])
      extends ((Context[E], BiCallback[E, B]) => Unit) {

    protected def makeReleaseFrame(ctx: Context[E], value: A): BaseReleaseFrame[E, A, B]

    final def apply(ctx: Context[E], cb: BiCallback[E, B]): Unit = {
      // Placeholder for the future finalizer
      val deferredRelease = ForwardCancelable()
      ctx.connection.push(deferredRelease.cancel)(ctx.scheduler)
      // Async boundary needed, but it is guaranteed via Task.Async below;
      Task.unsafeStartNow(
        acquire,
        ctx.withConnection(TaskConnection.uncancelable[E]),
        new BiCallback[E, A] {
          def onSuccess(value: A): Unit = {
            implicit val sc = ctx.scheduler
            val conn = ctx.connection

            val releaseFrame = makeReleaseFrame(ctx, value)
            deferredRelease.complete(releaseFrame.cancel)

            // Check if Task wasn't already cancelled in acquire
            if (!conn.isCanceled) {
              val onNext = {
                val fb =
                  try use(value)
                  catch { case NonFatal(e) => Task.terminate(e) }
                fb.flatMap(releaseFrame)
              }

              Task.unsafeStartNow(onNext, ctx, cb)
            }
          }

          def onError(ex: E): Unit = {
            deferredRelease.complete(Task.unit)(ctx.scheduler)
            cb.onError(ex)
          }

          override def onTermination(e: Throwable): Unit = {
            deferredRelease.complete(Task.unit)(ctx.scheduler)
            cb.onTermination(e)
          }
        }
      )
    }
  }

  private abstract class BaseReleaseFrame[E, A, B](ctx: Context[E], a: A) extends FatalStackFrame[E, B, Task[E, B]] {
    private[this] val waitsForResult = Atomic(true)
    private[this] val p: Promise[Unit] = Promise()
    protected def releaseOnSuccess(a: A, b: B): UIO[Unit]
    protected def releaseOnError(a: A, e: Cause[E]): UIO[Unit]
    protected def releaseOnCancel(a: A): UIO[Unit]

    final def apply(b: B): Task[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          Task.suspendTotal(unsafeApply(b))
        else
          unsafeApply(b)

      makeUncancelable(task)
    }

    final def recover(e: E): Task[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          Task.suspendTotal(unsafeRecover(e))
        else
          unsafeRecover(e)

      makeUncancelable(task)
    }

    override final def recoverFatal(e: Throwable): Task[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          Task.suspendTotal(unsafeRecoverFatal(e))
        else
          unsafeRecoverFatal(e)

      makeUncancelable(task)
    }

    final def cancel: UIO[Unit] =
      UIO.suspend {
        if (waitsForResult.compareAndSet(expect = true, update = false))
          releaseOnCancel(a).redeemCauseWith(
            c => UIO.suspend { p.success(()); c.fold(Task.terminate, _ => Task.unit) },
            _ => UIO(p.success(()))
          )
        else {
          TaskFromFuture.strict(p.future).hideErrors
        }
      }

    private final def unsafeApply(b: B): UIO[B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        releaseOnSuccess(a, b).redeemCauseWith(
          c => UIO.suspend { p.success(()); c.fold(Task.terminate, _ => Task.now(b)) },
          _ => UIO { p.success(()); b }
        )
      } else {
        UIO.never
      }
    }

    private final def unsafeRecover(e: E): Task[E, B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        val cause = Cause.Error(e)

        releaseOnError(a, cause)
          .redeemCauseWith(
            ex => UIO.suspend { p.success(()); ex.fold(Task.terminate, _ => Task.unit) },
            _ => UIO { p.success(()); () }
          )
          .flatMap(new ReleaseRecover(cause))

      } else {
        Task.never
      }
    }

    private final def unsafeRecoverFatal(e: Throwable): Task[E, B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        val cause = Cause.Termination(e)

        releaseOnError(a, cause)
          .redeemCauseWith(
            ex => UIO.suspend { p.success(()); ex.fold(Task.terminate, _ => Task.unit) },
            _ => UIO { p.success(()); () }
          )
          .flatMap(new ReleaseRecover(cause))
      } else {
        Task.never
      }
    }

    private final def makeUncancelable(task: Task[E, B]): Task[E, B] = {
      // NOTE: the "restore" part of this is `null` because we don't need to restore
      // the original connection. The original connection gets restored automatically
      // via how "TaskRestartCallback" works. This is risky!
      ContextSwitch[E, B](task, withConnectionUncancelable.asInstanceOf[Context[E] => Context[E]], null)

    }
  }

  private final class ReleaseRecover[E](e: Cause[E]) extends FatalStackFrame[Nothing, Unit, Task[E, Nothing]] {

    def apply(a: Unit): Task[E, Nothing] = {
      e.fold(Task.terminate, Task.raiseError)
    }

    def recover(e2: Nothing): Task[E, Nothing] = {
      e.fold(Task.terminate, Task.raiseError)
    }

    override def recoverFatal(e2: Throwable): Task[Nothing, Nothing] = {
      Task.terminate(Platform.composeErrors(e.fold(identity, UncaughtErrorException.wrap), e2))
    }
  }

  private val leftNone = Left(None)

  private[this] val withConnectionUncancelable: Context[Any] => Context[Any] =
    _.withConnection(TaskConnection.uncancelable)
}
