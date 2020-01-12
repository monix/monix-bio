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

package internal

import cats.effect.CancelToken
import monix.catnap.CancelableF
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, Scheduler}

import scala.annotation.tailrec

private[bio] final class TaskConnectionRef[E] extends CancelableF[BIO[E, ?]] {
  import TaskConnectionRef._

  @throws(classOf[IllegalStateException])
  def `:=`(token: CancelToken[BIO[E, ?]])(implicit s: Scheduler): Unit =
    unsafeSet(token)

  @throws(classOf[IllegalStateException])
  def `:=`(cancelable: Cancelable)(implicit s: Scheduler): Unit =
    unsafeSet(cancelable)

  @throws(classOf[IllegalStateException])
  def `:=`(conn: CancelableF[BIO[E, ?]])(implicit s: Scheduler): Unit =
    unsafeSet(conn.cancel)

  @tailrec
  private def unsafeSet(
    ref: AnyRef /* CancelToken[Task] | CancelableF[Task] | Cancelable */
  )(implicit s: Scheduler): Unit = {

    if (!state.compareAndSet(Empty, IsActive(ref))) {
      state.get() match {
        case IsEmptyCanceled =>
          state.getAndSet(IsCanceled) match {
            case IsEmptyCanceled =>
              UnsafeCancelUtils.triggerCancel(ref)
            case _ =>
              UnsafeCancelUtils.triggerCancel(ref)
              raiseError()
          }
        case IsCanceled | IsActive(_) =>
          UnsafeCancelUtils.triggerCancel(ref)
          raiseError()
        case Empty =>
          // $COVERAGE-OFF$
          unsafeSet(ref)
        // $COVERAGE-ON$
      }
    }
  }

  val cancel: CancelToken[BIO[E, ?]] = {
    @tailrec def loop(): CancelToken[BIO[E, ?]] =
      state.get() match {
        case IsCanceled | IsEmptyCanceled =>
          BIO.unit
        case IsActive(task) =>
          state.set(IsCanceled)
          UnsafeCancelUtils.unsafeCancel(task)
        case Empty =>
          if (state.compareAndSet(Empty, IsEmptyCanceled)) {
            BIO.unit
          } else {
            // $COVERAGE-OFF$
            loop() // retry
            // $COVERAGE-ON$
          }
      }
    BIO.suspendTotal(loop())
  }

  private def raiseError(): Nothing = {
    throw new IllegalStateException(
      "Cannot assign to SingleAssignmentCancelable, " +
        "as it was already assigned once"
    )
  }

  private[this] val state = Atomic(Empty: State)
}

private[bio] object TaskConnectionRef {

  /**
    * Returns a new `TaskForwardConnection` reference.
    */
  def apply[E](): TaskConnectionRef[E] = new TaskConnectionRef()

  private sealed trait State
  private case object Empty extends State

  private final case class IsActive(token: AnyRef /* CancelToken[Task] | CancelableF[Task] | Cancelable */ )
      extends State
  private case object IsCanceled extends State
  private case object IsEmptyCanceled extends State
}
