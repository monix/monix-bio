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
import monix.bio.IO.{Async, Context}
import monix.bio.compat.internal.toIterator
import monix.bio.{BiCallback, IO, UIO}
import monix.execution.Scheduler
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.exceptions.UncaughtErrorException

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

private[bio] object TaskParSequenceUnordered {

  /** Implementation for `Task.gatherUnordered`
    */
  def apply[E, A](in: Iterable[IO[E, A]]): IO[E, List[A]] = {
    Async(
      new Register(in),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )
  }

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A](in: Iterable[IO[E, A]]) extends ForkedRegister[E, List[A]] {

    def maybeSignalFinal(
      ref: AtomicAny[State[A]],
      currentState: State[A],
      mainConn: TaskConnection[E],
      finalCallback: BiCallback[E, List[A]]
    )(implicit s: Scheduler): Unit = {

      currentState match {
        case State.Active(list, 0) =>
          ref.lazySet(State.Complete)
          mainConn.pop()
          if (list ne Nil)
            finalCallback.onSuccess(list)
          else {
            // Needs to force async execution in case we had no tasks,
            // due to the contract of ForkedStart
            s.execute(() => finalCallback.onSuccess(list))
          }
        case _ =>
          () // invalid state
      }
    }

    def reportError(
      stateRef: AtomicAny[State[A]],
      mainConn: TaskConnection[E],
      ex: E,
      finalCallback: BiCallback[E, List[A]]
    )(implicit s: Scheduler): Unit = {

      val currentState = stateRef.getAndSet(State.Complete)
      if (currentState != State.Complete) {
        mainConn.pop().runAsyncAndForget
        finalCallback.onError(ex)
      } else {
        s.reportFailure(UncaughtErrorException.wrap(ex))
      }
    }

    def reportTermination(
      stateRef: AtomicAny[State[A]],
      mainConn: TaskConnection[E],
      ex: Throwable,
      finalCallback: BiCallback[E, List[A]]
    )(implicit s: Scheduler): Unit = {

      val currentState = stateRef.getAndSet(State.Complete)
      if (currentState != State.Complete) {
        mainConn.pop().runAsyncAndForget
        finalCallback.onTermination(ex)
      } else {
        s.reportFailure(ex)
      }
    }

    def apply(context: Context[E], finalCallback: BiCallback[E, List[A]]): Unit = {
      @tailrec def activate(
        stateRef: AtomicAny[State[A]],
        count: Int,
        conn: TaskConnection[E],
        finalCallback: BiCallback[E, List[A]]
      )(implicit s: Scheduler): Unit = {

        stateRef.get() match {
          case current @ State.Initializing(_, _) =>
            val update = current.activate(count)
            if (!stateRef.compareAndSet(current, update))
              activate(stateRef, count, conn, finalCallback)(s)
            else
              maybeSignalFinal(stateRef, update, conn, finalCallback)(s)

          case _ =>
            () // do nothing
        }
      }

      implicit val s = context.scheduler
      // Shared state for synchronization
      val stateRef = Atomic.withPadding(State.empty[A], LeftRight128)

      try {
        // Represents the collection of cancelables for all started tasks
        val composite = TaskConnectionComposite[E]()
        val mainConn = context.connection
        mainConn.push(composite.cancel)

        // Collecting all cancelables in a buffer, because adding
        // cancelables one by one in our `CompositeCancelable` is
        // expensive, so we do it at the end
        val allCancelables = ListBuffer.empty[CancelToken[UIO]]
        val batchSize = s.executionModel.recommendedBatchSize
        val cursor = toIterator(in)

        var continue = true
        var count = 0

        // The `isActive` check short-circuits the process in case
        // we have a synchronous task that just completed in error
        while (cursor.hasNext && continue) {
          val task = cursor.next()
          count += 1
          continue = count % batchSize != 0 || stateRef.get().isActive

          val stacked = TaskConnection[E]()
          val childCtx = context.withConnection(stacked)
          allCancelables += stacked.cancel

          // Light asynchronous boundary
          IO.unsafeStartEnsureAsync(
            task,
            childCtx,
            new BiCallback[E, A] {
              @tailrec
              def onSuccess(value: A): Unit = {
                val current = stateRef.get()
                if (current.isActive) {
                  val update = current.enqueue(value)
                  if (!stateRef.compareAndSet(current, update))
                    onSuccess(value) // retry
                  else
                    maybeSignalFinal(stateRef, update, context.connection, finalCallback)
                }
              }

              def onError(ex: E): Unit =
                reportError(stateRef, mainConn, ex, finalCallback)

              override def onTermination(e: Throwable): Unit =
                reportTermination(stateRef, mainConn, e, finalCallback)
            }
          )
        }

        // Note that if an error happened, this should cancel all
        // other active tasks.
        composite.addAll(allCancelables)
        // We are done triggering tasks, now we can allow the final
        // callback to be triggered
        activate(stateRef, count, mainConn, finalCallback)(s)
      } catch {
        case ex if NonFatal(ex) =>
          reportTermination(stateRef, context.connection, ex, finalCallback)
      }
    }
  }

  private sealed abstract class State[+A] {
    def isActive: Boolean
    def enqueue[B >: A](value: B): State[B]
  }

  private object State {

    def empty[A]: State[A] =
      Initializing(List.empty, 0)

    case object Complete extends State[Nothing] {
      def isActive = false

      override def enqueue[B >: Nothing](value: B): State[B] =
        this
    }

    final case class Initializing[+A](list: List[A], remaining: Int) extends State[A] {

      def isActive = true

      def enqueue[B >: A](value: B): Initializing[B] =
        Initializing(value :: list, remaining - 1)

      def activate(totalCount: Int): Active[A] =
        Active(list, remaining + totalCount)
    }

    final case class Active[+A](list: List[A], remaining: Int) extends State[A] {

      def isActive = true

      def enqueue[B >: A](value: B): Active[B] =
        Active(value :: list, remaining - 1)
    }
  }
}
