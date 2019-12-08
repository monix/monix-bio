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

package monix.bio.internal

import cats.effect.ExitCase
import cats.effect.ExitCase.{Canceled, Completed, Error}
import monix.bio.WRYYY.{Context, ContextSwitch}
import monix.bio.internal.StackFrame.FatalStackFrame
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.bio.{UIO, WRYYY}
import monix.execution.atomic.Atomic
import monix.execution.internal.Platform

import scala.util.control.NonFatal

private[monix] object TaskBracket {

  // -----------------------------------------------------------------
  // Task.guaranteeCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  def guaranteeCase[E, A](task: WRYYY[E, A], finalizer: ExitCase[Either[Throwable, E]] => UIO[Unit]): WRYYY[E, A] =
    WRYYY.Async(
      new ReleaseStart(task, finalizer),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class ReleaseStart[E, A](source: WRYYY[E, A], release: ExitCase[Either[Throwable, E]] => UIO[Unit])
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
        WRYYY.unsafeStartNow(onNext, ctx, cb)
      }
    }
  }

  private final class EnsureReleaseFrame[E, A](ctx: Context[E], releaseFn: ExitCase[Either[Throwable, E]] => UIO[Unit])
      extends BaseReleaseFrame[E, Unit, A](ctx, ()) {

    def releaseOnSuccess(a: Unit, b: A): UIO[Unit] =
      releaseFn(ExitCase.Completed)

    def releaseOnError(a: Unit, e: Either[Throwable, E]): UIO[Unit] =
      releaseFn(ExitCase.Error(e))

    def releaseOnCancel(a: Unit): UIO[Unit] =
      releaseFn(ExitCase.Canceled)
  }

  // -----------------------------------------------------------------
  // Task.bracketE
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.bio.WRYYY.bracket]] and [[monix.bio.WRYYY.bracketCase]]
    */
  def either[E, A, B](
    acquire: WRYYY[E, A],
    use: A => WRYYY[E, B],
    release: (A, Either[Option[Either[Throwable, E]], B]) => UIO[Unit]): WRYYY[E, B] = {

    WRYYY.Async(
      new StartE(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )
  }

  private final class StartE[E, A, B](
    acquire: WRYYY[E, A],
    use: A => WRYYY[E, B],
    release: (A, Either[Option[Either[Throwable, E]], B]) => UIO[Unit])
      extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context[E], value: A) =
      new ReleaseFrameE(ctx, value, release)
  }

  private final class ReleaseFrameE[E, A, B](
    ctx: Context[E],
    a: A,
    release: (A, Either[Option[Either[Throwable, E]], B]) => UIO[Unit])
      extends BaseReleaseFrame[E, A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): UIO[Unit] =
      release(a, Right(b))

    def releaseOnError(a: A, e: Either[Throwable, E]): UIO[Unit] =
      release(a, Left(Some(e)))

    def releaseOnCancel(a: A): UIO[Unit] =
      release(a, leftNone)
  }

  // -----------------------------------------------------------------
  // Task.bracketCase
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  /**
    * [[monix.bio.WRYYY.bracketE]]
    */
  def exitCase[E, A, B](
    acquire: WRYYY[E, A],
    use: A => WRYYY[E, B],
    release: (A, ExitCase[Either[Throwable, E]]) => UIO[Unit]): WRYYY[E, B] =
    WRYYY.Async(
      new StartCase(acquire, use, release),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )

  private final class StartCase[E, A, B](
    acquire: WRYYY[E, A],
    use: A => WRYYY[E, B],
    release: (A, ExitCase[Either[Throwable, E]]) => UIO[Unit])
      extends BaseStart(acquire, use) {

    def makeReleaseFrame(ctx: Context[E], value: A) =
      new ReleaseFrameCase(ctx, value, release)
  }

  private final class ReleaseFrameCase[E, A, B](
    ctx: Context[E],
    a: A,
    release: (A, ExitCase[Either[Throwable, E]]) => UIO[Unit])
      extends BaseReleaseFrame[E, A, B](ctx, a) {

    def releaseOnSuccess(a: A, b: B): UIO[Unit] =
      release(a, Completed)

    def releaseOnError(a: A, e: Either[Throwable, E]): UIO[Unit] =
      release(a, Error(e))

    def releaseOnCancel(a: A): UIO[Unit] =
      release(a, Canceled)
  }

  // -----------------------------------------------------------------
  // Base Implementation
  // =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=

  private abstract class BaseStart[E, A, B](acquire: WRYYY[E, A], use: A => WRYYY[E, B])
      extends ((Context[E], BiCallback[E, B]) => Unit) {

    protected def makeReleaseFrame(ctx: Context[E], value: A): BaseReleaseFrame[E, A, B]

    final def apply(ctx: Context[E], cb: BiCallback[E, B]): Unit = {
      // Async boundary needed, but it is guaranteed via Task.Async below;
      WRYYY.unsafeStartNow(
        acquire,
        ctx.withConnection(TaskConnection.uncancelable[E]),
        new BiCallback[E, A] {
          def onSuccess(value: A): Unit = {
            implicit val sc = ctx.scheduler
            val conn = ctx.connection

            val releaseFrame = makeReleaseFrame(ctx, value)
            conn.push(releaseFrame.cancel)

            // Check if Task wasn't already cancelled in acquire
            if (!conn.isCanceled) {
              val onNext = {
                val fb =
                  try use(value)
                  catch { case NonFatal(e) => WRYYY.raiseFatalError(e) }
                fb.flatMap(releaseFrame)
              }

              WRYYY.unsafeStartNow(onNext, ctx, cb)
            }
          }

          def onError(ex: E): Unit =
            cb.onError(ex)

          override def onFatalError(e: Throwable): Unit = {
            cb.onFatalError(e)
          }
        }
      )
    }
  }

  private abstract class BaseReleaseFrame[E, A, B](ctx: Context[E], a: A) extends FatalStackFrame[E, B, WRYYY[E, B]] {
    private[this] val waitsForResult = Atomic(true)
    protected def releaseOnSuccess(a: A, b: B): UIO[Unit]
    protected def releaseOnError(a: A, e: Either[Throwable, E]): UIO[Unit]
    protected def releaseOnCancel(a: A): UIO[Unit]

    final def apply(b: B): WRYYY[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          WRYYY.suspend(unsafeApply(b))
        else
          unsafeApply(b)

      makeUncancelable(task)
    }

    final def recover(e: E): WRYYY[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          WRYYY.suspend(unsafeRecover(e))
        else
          unsafeRecover(e)

      makeUncancelable(task)
    }

    override final def recoverFatal(e: Throwable): WRYYY[E, B] = {
      // In case auto-cancelable run-loops are enabled, then the function
      // call needs to be suspended, because we might evaluate the effect before
      // the connection is actually made uncancelable
      val task =
        if (ctx.options.autoCancelableRunLoops)
          WRYYY.suspend(unsafeRecoverFatal(e))
        else
          unsafeRecoverFatal(e)

      makeUncancelable(task)
    }

    final def cancel: UIO[Unit] =
      UIO.suspend {
        if (waitsForResult.compareAndSet(expect = true, update = false))
          releaseOnCancel(a)
        else
          WRYYY.unit
      }

    private final def unsafeApply(b: B): UIO[B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        ctx.connection.pop()
        releaseOnSuccess(a, b).map(_ => b)
      } else {
        UIO.never
      }
    }

    private final def unsafeRecover(e: E): WRYYY[E, B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        ctx.connection.pop()
        val re = Right(e)
        releaseOnError(a, re).flatMap[E, B](new ReleaseRecover(re))
      } else {
        WRYYY.never
      }
    }

    private final def unsafeRecoverFatal(e: Throwable): WRYYY[E, B] = {
      if (waitsForResult.compareAndSet(expect = true, update = false)) {
        ctx.connection.pop()
        val le = Left(e)
        releaseOnError(a, le).flatMap[E, B](new ReleaseRecover(le))
      } else {
        WRYYY.never
      }
    }

    private final def makeUncancelable(task: WRYYY[E, B]): WRYYY[E, B] = {
      // NOTE: the "restore" part of this is `null` because we don't need to restore
      // the original connection. The original connection gets restored automatically
      // via how "TaskRestartCallback" works. This is risky!
      ContextSwitch[E, B](task, withConnectionUncancelable.asInstanceOf[Context[E] => Context[E]], null)

    }
  }

  private final class ReleaseRecover[E](e: Either[Throwable, E])
      extends FatalStackFrame[Throwable, Unit, WRYYY[E, Nothing]] {

    def apply(a: Unit): WRYYY[E, Nothing] = {
      e.fold(WRYYY.raiseFatalError, WRYYY.raiseError)
    }

    def recover(e2: Throwable): WRYYY[E, Nothing] = {
      WRYYY.raiseFatalError(Platform.composeErrors(e.fold(identity, WrappedException.wrap), e2))
    }

    override def recoverFatal(e2: Throwable): WRYYY[E, Nothing] = {
      WRYYY.raiseFatalError(Platform.composeErrors(e.fold(identity, WrappedException.wrap), e2))
    }
  }

  private val leftNone = Left(None)

  private[this] val withConnectionUncancelable: Context[Any] => Context[Any] =
    _.withConnection(TaskConnection.uncancelable)
}
