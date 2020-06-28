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
import monix.bio
import monix.bio.{BiCallback, Cause, Task, UIO}
import monix.execution.Scheduler
import monix.execution.atomic.{Atomic, PaddingStrategy}
import monix.execution.exceptions.{CompositeException, UncaughtErrorException}

private[bio] object TaskRaceList {

  /**
    * Implementation for `Task.raceMany`
    */
  def apply[E, A](tasks: Iterable[Task[E, A]]): Task[E, A] =
    Task.Async(new Register(tasks), trampolineBefore = true, trampolineAfter = true)

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A](tasks: Iterable[Task[E, A]]) extends ForkedRegister[E, A] {
    def apply(context: bio.Task.Context[E], callback: BiCallback[E, A]): Unit = {
      implicit val s: Scheduler = context.scheduler

      val isActive = Atomic.withPadding(true, PaddingStrategy.LeftRight128)
      val taskArray = tasks.toArray
      val cancelableArray = buildCancelableArray[E](taskArray.length)

      val conn = context.connection
      conn.pushConnections(cancelableArray.toIndexedSeq: _*)

      var index = 0
      while (index < taskArray.length) {
        val task = taskArray(index)
        val taskCancelable = cancelableArray(index)
        val taskContext = context.withConnection(taskCancelable)
        index += 1

        Task.unsafeStartEnsureAsync(
          task,
          taskContext,
          new BiCallback[E, A] {
            def onSuccess(value: A): Unit =
              if (isActive.getAndSet(false)) {
                popAndCancelRest().map(_ => callback.onSuccess(value)).runAsyncAndForget
              }

            override def onError(e: E): Unit =
              if (isActive.getAndSet(false)) {
                popAndCancelRest().map(_ => callback.onError(e)).runAsyncAndForget
              } else {
                s.reportFailure(UncaughtErrorException.wrap(e))
              }

            override def onTermination(ex: Throwable): Unit =
              if (isActive.getAndSet(false)) {
                popAndCancelRest().map(_ => callback.onTermination(ex)).runAsyncAndForget
              } else {
                s.reportFailure(ex)
              }

            private def popAndCancelRest(): CancelToken[UIO] = {
              conn.pop()
              val tokens = cancelableArray.collect {
                case cancelable if cancelable ne taskCancelable =>
                  cancelable.cancel
              }
              batchCancel(tokens)
            }
          }
        )
      }
    }
  }

  private def batchCancel(tokens: Array[CancelToken[UIO]]): CancelToken[UIO] = {
    def loop(idx: Int, exceptions: List[Throwable]): CancelToken[UIO] = {
      if (idx < tokens.length) {
        tokens(idx).redeemCauseWith(
          {
            case Cause.Error(_) => loop(idx + 1, exceptions)
            case Cause.Termination(ex) => loop(idx + 1, ex :: exceptions)
          },
          _ => loop(idx + 1, exceptions)
        )
      } else if (exceptions.isEmpty) UIO.unit
      else if (exceptions.size == 1) UIO.terminate(exceptions.head)
      else UIO.terminate(CompositeException(exceptions))
    }

    loop(idx = 0, exceptions = List.empty)
  }

  private def buildCancelableArray[E](length: Int): Array[TaskConnection[E]] = {
    val array = new Array[TaskConnection[E]](length)
    var i = 0

    while (i < length) {
      array(i) = TaskConnection()
      i += 1
    }

    array
  }

}
