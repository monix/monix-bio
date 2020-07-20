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

import monix.bio.{BiCallback, IO}
import monix.bio.IO.Context
import monix.execution.cancelables.SingleAssignCancelable
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Cancelable, CancelableFuture, CancelablePromise}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

private[bio] object TaskFromFutureEither {
  /** Implementation for `IO.fromFutureEither`. */
  def strict[E, A](f: Future[Either[E, A]]): IO[E, A] = {
    f.value match {
      case None =>
        f match {
          // Do we have a CancelableFuture?
          case cf: CancelableFuture[Either[E, A]] @unchecked =>
            // Cancelable future, needs canceling
            rawAsync[E, A](startCancelable(_, _, cf, cf.cancelable))
          case _ =>
            // Simple future, convert directly
            rawAsync[E, A](startSimple(_, _, f))
        }
      case Some(value) =>
        IO.fromTryEither(value)
    }
  }

  /** Implementation for `IO.fromCancelablePromiseEither`. */
  def fromCancelablePromise[E, A](p: CancelablePromise[Either[E, A]]): IO[E, A] = {
    val start: Start[E, A] = (ctx, cb) => {
      implicit val ec = ctx.scheduler
      if (p.isCompleted) {
        p.subscribe(trampolinedCB(cb, null))
      } else {
        val conn = ctx.connection
        val ref = SingleAssignCancelable()
        conn.push(ref)
        ref := p.subscribe(trampolinedCB(cb, conn))
      }
    }

    IO.Async(
      start,
      trampolineBefore = false,
      trampolineAfter = false,
      restoreLocals = true
    )
  }

  private def startSimple[E, A](ctx: IO.Context[E], cb: BiCallback[E, A], f: Future[Either[E, A]]) = {

    f.value match {
      case Some(value) =>
        cb(value)
      case None =>
        f.onComplete { result =>
          cb(result)
        }(ctx.scheduler)
    }
  }

  private def startCancelable[E, A](
    ctx: IO.Context[E],
    cb: BiCallback[E, A],
    f: Future[Either[E, A]],
    c: Cancelable
  ): Unit = {

    f.value match {
      case Some(value) =>
        cb(value)
      case None =>
        // Given a cancelable future, we should use it
        val conn = ctx.connection
        conn.push(c)(ctx.scheduler)
        // Async boundary
        f.onComplete { result =>
          conn.pop()
          cb(result)
        }(ctx.scheduler)
    }
  }

  private def rawAsync[E, A](start: (Context[E], BiCallback[E, A]) => Unit): IO[E, A] =
    IO.Async(
      start,
      trampolineBefore = true,
      trampolineAfter = false,
      restoreLocals = true
    )

  private def trampolinedCB[E, A](cb: BiCallback[E, A], conn: TaskConnection[E])(implicit
    ec: ExecutionContext
  ): Try[Either[E, A]] => Unit = {

    new (Try[Either[E, A]] => Unit) with TrampolinedRunnable {
      private[this] var value: Try[Either[E, A]] = _

      def apply(value: Try[Either[E, A]]): Unit = {
        this.value = value
        ec.execute(this)
      }

      def run(): Unit = {
        if (conn ne null) conn.pop()
        val v = value
        value = null
        cb(v)
      }
    }
  }
}
