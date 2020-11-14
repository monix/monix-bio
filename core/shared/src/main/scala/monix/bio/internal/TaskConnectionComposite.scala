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
import monix.bio.{IO, UIO}
import monix.bio.internal.TaskConnectionComposite.{Active, Cancelled, State}
import monix.catnap.CancelableF
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.{Cancelable, Scheduler}

import scala.annotation.tailrec

private[bio] final class TaskConnectionComposite[E] private (stateRef: AtomicAny[State]) {

  val cancel: CancelToken[UIO] =
    IO.suspendTotal {
      stateRef.getAndSet(Cancelled) match {
        case Cancelled => IO.unit
        case Active(set) =>
          UnsafeCancelUtils.cancelAllUnsafe(set)
      }
    }

  /** Adds a cancelation token to the underlying collection, if
    * this connection hasn't been cancelled yet, otherwise it
    * cancels the given token.
    */
  def add(token: CancelToken[UIO])(implicit s: Scheduler): Unit =
    addAny(token)

  /** Alias for [[add(token* add]]. */
  def `+=`(token: CancelToken[UIO])(implicit s: Scheduler): Unit =
    add(token)

  /** Adds a [[monix.execution.Cancelable]] to the underlying
    * collection, if this connection hasn't been cancelled yet,
    * otherwise it cancels the given cancelable.
    */
  def add(cancelable: Cancelable)(implicit s: Scheduler): Unit =
    addAny(cancelable)

  /** Alias for [[add(cancelable* add]]. */
  def `+=`(cancelable: Cancelable)(implicit s: Scheduler): Unit =
    add(cancelable)

  /** Adds a [[monix.catnap.CancelableF]] to the underlying
    * collection, if this connection hasn't been cancelled yet,
    * otherwise it cancels the given cancelable.
    */
  def add(conn: CancelableF[UIO])(implicit s: Scheduler): Unit =
    addAny(conn)

  /** Alias for [[add(conn* add]]. */
  def `+=`(conn: CancelableF[UIO])(implicit s: Scheduler): Unit =
    add(conn)

  @tailrec
  private def addAny(
    ref: AnyRef /* CancelToken[Task] | CancelableF[Task] | Cancelable */
  )(implicit s: Scheduler): Unit = {

    stateRef.get() match {
      case Cancelled =>
        UnsafeCancelUtils.triggerCancel(ref)
      case current @ Active(set) =>
        if (!stateRef.compareAndSet(current, Active(set + ref))) {
          // $COVERAGE-OFF$
          addAny(ref)
          // $COVERAGE-ON$
        }
    }
  }

  /** Adds a whole collection of cancellation tokens, if the
    * connection is still active, or cancels the whole collection
    * otherwise.
    */
  def addAll(that: Iterable[CancelToken[UIO]])(implicit s: Scheduler): Unit = {

    @tailrec def loop(that: Iterable[CancelToken[UIO]]): Unit =
      stateRef.get() match {
        case Cancelled =>
          UnsafeCancelUtils.cancelAllUnsafe(that).runAsyncAndForget
        case current @ Active(set) =>
          if (!stateRef.compareAndSet(current, Active(set ++ that))) {
            // $COVERAGE-OFF$
            loop(that)
            // $COVERAGE-ON$
          }
      }

    loop(that.toSeq)
  }

  /** Removes the given token reference from the underlying collection.
    */
  def remove(token: CancelToken[UIO]): Unit =
    removeAny(token)

  /** Removes a specific [[monix.execution.Cancelable]] reference
    * from the underlying collection.
    */
  def remove(cancelable: Cancelable): Unit =
    removeAny(cancelable)

  /** Removes a specific [[monix.catnap.CancelableF]] reference
    * from the underlying collection.
    */
  def remove(conn: CancelableF[UIO]): Unit =
    removeAny(conn)

  @tailrec
  private def removeAny(ref: AnyRef): Unit =
    stateRef.get() match {
      case Cancelled => ()
      case current @ Active(set) =>
        if (!stateRef.compareAndSet(current, Active(set - ref))) {
          // $COVERAGE-OFF$
          removeAny(ref)
          // $COVERAGE-ON$
        }
    }
}

private[bio] object TaskConnectionComposite {

  /** Builder for [[TaskConnectionComposite]].
    */
  def apply[E](initial: CancelToken[UIO]*): TaskConnectionComposite[E] =
    new TaskConnectionComposite(Atomic.withPadding(Active(Set(initial: _*)): State, LeftRight128))

  private sealed abstract class State

  private final case class Active(set: Set[AnyRef /* CancelToken[UIO] | CancelableF[UIO] | Cancelable */ ])
      extends State
  private case object Cancelled extends State
}
