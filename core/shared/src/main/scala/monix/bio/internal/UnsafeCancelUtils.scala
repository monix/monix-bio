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
import monix.bio.{Task, UIO, WRYYY}
import monix.catnap.CancelableF
import monix.execution.internal.Platform
import monix.execution.{Cancelable, Scheduler}

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

private[bio] object UnsafeCancelUtils {

  /**
    * Internal API.
    */
  def taskToCancelable(task: WRYYY[Any, Unit])(implicit s: Scheduler): Cancelable = {
    if (task == WRYYY.unit) Cancelable.empty
    else Cancelable(() => task.runAsyncAndForget(s))
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def cancelAllUnsafe[E](
    cursor: Iterable[AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ]): CancelToken[WRYYY[E, ?]] = {

    if (cursor.isEmpty)
      WRYYY.unit
    else
      WRYYY.suspend {
        val frame = new CancelAllFrame(cursor.iterator)
        frame.loop()
      }
  }

  /**
    * Internal API — very unsafe!
    */
  private[internal] def unsafeCancel[E](
    task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ): CancelToken[WRYYY[E, ?]] = {

    task match {
      case ref: WRYYY[E, Unit] @unchecked =>
        ref
      case ref: CancelableF[WRYYY[E, ?]] @unchecked =>
        ref.cancel
      case ref: Cancelable =>
        ref.cancel()
        WRYYY.unit
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
    task: AnyRef /* Cancelable | Task[Unit] | CancelableF[Task] */ ): CancelToken[WRYYY[E, ?]] =
    task match {
      case ref: WRYYY[E, Unit] @unchecked =>
        ref
      case ref: CancelableF[WRYYY[E, ?]] @unchecked =>
        ref.cancel
      case ref: Cancelable =>
        WRYYY.delay(ref.cancel()).hideErrors
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
      case ref: WRYYY[Any, Unit] @unchecked =>
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
      extends StackFrame[E, Unit, WRYYY[E, Unit]] {

    private[this] val errors = ListBuffer.empty[E]
    private[this] val fatalErrors = ListBuffer.empty[Throwable]

    def loop(): CancelToken[WRYYY[E, ?]] = {
      var task: WRYYY[E, Unit] = null

      while ((task eq null) && cursor.hasNext) {
        cursor.next() match {
          case ref: WRYYY[E, Unit] @unchecked =>
            task = ref
          case ref: CancelableF[WRYYY[E, ?]] @unchecked =>
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
              WRYYY.raiseFatalError(Platform.composeErrors(first, rest ++ rest2.map(WrappedException.wrap): _*))
          case (Nil, first :: rest) =>
            (first, rest) match {
              case (th: Throwable, restTh: List[Throwable]) =>
                WRYYY.raiseError(Platform.composeErrors(th, restTh: _*).asInstanceOf[E])
              case _ =>
                WRYYY.deferAction(s => UIO(rest.foreach{e => s.reportFailure(WrappedException.wrap(e))})) >> WRYYY.raiseError(first)
            }
            WRYYY.raiseError(first)

          case (Nil, Nil) =>
            WRYYY.unit

        }
      }
    }

    def apply(a: Unit): WRYYY[E, Unit] =
      loop()

    def recover(e: E): WRYYY[E, Unit] = {
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
