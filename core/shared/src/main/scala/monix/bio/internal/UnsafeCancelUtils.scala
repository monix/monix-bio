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

import cats.effect.CancelToken
import monix.bio.internal.StackFrame.FatalStackFrame
import monix.bio.{BIO, UIO}
import monix.catnap.CancelableF
import monix.execution.internal.Platform
import monix.execution.{Cancelable, Scheduler}

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

private[bio] object UnsafeCancelUtils {

  /**
    * Internal API.
    */
  def taskToCancelable(task: BIO[Any, Unit])(implicit s: Scheduler): Cancelable = {
    if (task == BIO.unit) Cancelable.empty
    else Cancelable(() => task.runAsyncAndForget(s))
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def cancelAllUnsafe(
    cursor: Iterable[AnyRef /* Cancelable | UIO[Unit] | CancelableF[UIO] */ ]
  ): CancelToken[UIO] = {

    if (cursor.isEmpty)
      BIO.unit
    else
      BIO.suspendTotal {
        val frame = new CancelAllFrame(cursor.iterator)
        frame.loop()
      }
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def unsafeCancel(
    task: AnyRef /* Cancelable | UIO[Unit] | CancelableF[UIO] */
  ): CancelToken[UIO] = {

    task match {
      case ref: UIO[Unit] @unchecked =>
        ref
      case ref: CancelableF[UIO] @unchecked =>
        ref.cancel
      case ref: Cancelable =>
        ref.cancel()
        BIO.unit
      case other =>
        // $COVERAGE-OFF$
        reject(other)
      // $COVERAGE-ON$
    }
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def getToken(
    task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */
  ): CancelToken[UIO] =
    task match {
      case ref: UIO[Unit] @unchecked =>
        ref
      case ref: CancelableF[UIO] @unchecked =>
        ref.cancel
      case ref: Cancelable =>
        BIO.delay(ref.cancel()).hideErrors
      case other =>
        // $COVERAGE-OFF$
        reject(other)
      // $COVERAGE-ON$
    }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def triggerCancel(
    task: AnyRef /* Cancelable | UIO[Unit] | CancelableF[UIO] */
  )(implicit s: Scheduler): Unit = {

    task match {
      case ref: BIO[Any, Unit] @unchecked =>
        ref.runAsyncAndForget
      case ref: CancelableF[UIO] @unchecked =>
        ref.cancel.runAsyncAndForget
      case ref: Cancelable =>
        try ref.cancel()
        catch {
          case NonFatal(e) => s.reportFailure(e)
        }
      case other =>
        // $COVERAGE-OFF$
        reject(other)
      // $COVERAGE-ON$
    }
  }

  // Optimization for `cancelAll`
  private final class CancelAllFrame(cursor: Iterator[AnyRef /* Cancelable | UIO[Unit] | CancelableF[UIO] */ ])
      extends FatalStackFrame[Nothing, Unit, UIO[Unit]] {

    private[this] val terminalErrors = ListBuffer.empty[Throwable]

    def loop(): CancelToken[UIO] = {
      var task: UIO[Unit] = null

      while ((task eq null) && cursor.hasNext) {
        cursor.next() match {
          case ref: UIO[Unit] @unchecked =>
            task = ref
          case ref: CancelableF[UIO] @unchecked =>
            task = ref.cancel
          case ref: Cancelable =>
            try {
              ref.cancel()
            } catch {
              case NonFatal(e) =>
                terminalErrors += e
            }
          case other =>
            // $COVERAGE-OFF$
            reject(other)
          // $COVERAGE-ON$
        }
      }

      if (task ne null) {
        task.flatMap(this)
      } else {
        terminalErrors.toList match {
          case Nil =>
            UIO.unit
          case first :: rest =>
            BIO.terminate(Platform.composeErrors(first, rest: _*))
        }
      }
    }

    def apply(a: Unit): UIO[Unit] =
      loop()

    def recover(e: Nothing): UIO[Unit] = {
      loop()
    }

    def recoverFatal(e: Throwable): UIO[Unit] = {
      terminalErrors += e
      loop()
    }
  }

  private def reject(other: AnyRef): Nothing = {
    // $COVERAGE-OFF$
    throw new IllegalArgumentException(s"Don't know how to cancel: $other")
    // $COVERAGE-ON$
  }
}
