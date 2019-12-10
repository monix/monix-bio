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

import cats.effect.CancelToken
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.bio.{Task, UIO, BIO}
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
  private[internal] def cancelAllUnsafe[E](
    cursor: Iterable[AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ]): CancelToken[BIO[E, ?]] = {

    if (cursor.isEmpty)
      BIO.unit
    else
      BIO.suspend {
        val frame = new CancelAllFrame(cursor.iterator)
        frame.loop()
      }
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def unsafeCancel[E](
    task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ): CancelToken[BIO[E, ?]] = {

    task match {
      case ref: BIO[E, Unit] @unchecked =>
        ref
      case ref: CancelableF[BIO[E, ?]] @unchecked =>
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
  private[internal] def getToken[E](
    task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ): CancelToken[BIO[E, ?]] =
    task match {
      case ref: BIO[E, Unit] @unchecked =>
        ref
      case ref: CancelableF[BIO[E, ?]] @unchecked =>
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
  private[internal] def triggerCancel(task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ )(
    implicit s: Scheduler): Unit = {

    task match {
      case ref: BIO[Any, Unit] @unchecked =>
        ref.runAsyncAndForget
      case ref: CancelableF[Task] @unchecked =>
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
  private final class CancelAllFrame[E](cursor: Iterator[AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ])
      extends StackFrame[E, Unit, BIO[E, Unit]] {

    private[this] val errors = ListBuffer.empty[E]
    private[this] val fatalErrors = ListBuffer.empty[Throwable]

    def loop(): CancelToken[BIO[E, ?]] = {
      var task: BIO[E, Unit] = null

      while ((task eq null) && cursor.hasNext) {
        cursor.next() match {
          case ref: BIO[E, Unit] @unchecked =>
            task = ref
          case ref: CancelableF[BIO[E, ?]] @unchecked =>
            task = ref.cancel
          case ref: Cancelable =>
            try {
              ref.cancel()
            } catch {
              case NonFatal(e) =>
                fatalErrors += e
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
        (fatalErrors.toList, errors.toList) match {
          case (first :: rest, rest2) =>
            BIO.raiseFatalError(Platform.composeErrors(first, rest ++ rest2.map(WrappedException.wrap): _*))
          case (Nil, first :: rest) =>
            (first, rest) match {
              case (th: Throwable, restTh: List[Throwable]) =>
                BIO.raiseError(Platform.composeErrors(th, restTh: _*).asInstanceOf[E])
              case _ =>
                BIO.deferAction(s =>
                  UIO(rest.foreach { e =>
                    s.reportFailure(WrappedException.wrap(e))
                  })) >> BIO.raiseError(first)
            }
            BIO.raiseError(first)

          case (Nil, Nil) =>
            BIO.unit

        }
      }
    }

    def apply(a: Unit): BIO[E, Unit] =
      loop()

    def recover(e: E): BIO[E, Unit] = {
      errors += e
      loop()
    }
  }

  private def reject(other: AnyRef): Nothing = {
    // $COVERAGE-OFF$
    throw new IllegalArgumentException(s"Don't know how to cancel: $other")
    // $COVERAGE-ON$
  }
}
