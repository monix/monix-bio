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

import java.util.concurrent.atomic.AtomicReference

import cats.effect.CancelToken
import monix.bio.{BiCallback, IO, UIO}
import monix.execution.schedulers.TrampolineExecutionContext
import monix.execution.Scheduler

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
  * A placeholder for a [[cats.effect.CancelToken]] that will be set at a later time,
  * the equivalent of a `Deferred[Task, CancelToken]`.
  *
  * Used in the implementation of `bracket`, see [[TaskBracket]].
  */
final private[internal] class ForwardCancelable private () {
  import ForwardCancelable._

  private[this] val state = new AtomicReference[State](init)

  val cancel: CancelToken[UIO] = {
    @tailrec def loop(ctx: IO.Context[Nothing], cb: BiCallback[Nothing, Unit]): Unit =
      state.get() match {
        case current @ Empty(list) =>
          if (!state.compareAndSet(current, Empty(cb :: list)))
            loop(ctx, cb)

        case Active(token) =>
          state.lazySet(finished) // GC purposes
          context.execute(new Runnable {
            def run() =
              IO.unsafeStartNow(token, ctx, cb)
          })
      }

    IO.Async[Nothing, Unit](loop)
  }

  def complete(value: CancelToken[UIO])(implicit s: Scheduler): Unit =
    state.get() match {
      case current @ Active(_) =>
        value.runAsyncAndForget
        throw new IllegalStateException(current.toString)

      case current @ Empty(stack) =>
        if (current eq init) {
          // If `init`, then `cancel` was not triggered yet
          if (!state.compareAndSet(current, Active(value)))
            complete(value)
        } else {
          if (!state.compareAndSet(current, finished))
            complete(value)
          else {
            execute(value, stack)
          }
        }
    }
}

private[internal] object ForwardCancelable {
  /**
    * Builds reference.
    */
  def apply(): ForwardCancelable =
    new ForwardCancelable

  /**
    * Models the internal state of [[ForwardCancelable]]:
    *
    *  - on start, the state is [[Empty]] of `Nil`, aka [[init]]
    *  - on `cancel`, if no token was assigned yet, then the state will
    *    remain [[Empty]] with a non-nil `List[Callback]`
    *  - if a `CancelToken` is provided without `cancel` happening,
    *    then the state transitions to [[Active]] mode
    *  - on `cancel`, if the state was [[Active]], or if it was [[Empty]],
    *    regardless, the state transitions to `Active(IO.unit)`, aka [[finished]]
    */
  sealed abstract private class State

  final private case class Empty(stack: List[BiCallback[Nothing, Unit]]) extends State
  final private case class Active(token: CancelToken[UIO]) extends State

  private val init: State = Empty(Nil)
  private val finished: State = Active(UIO.unit)
  private val context: ExecutionContext = TrampolineExecutionContext.immediate

  private def execute(token: CancelToken[UIO], stack: List[BiCallback[Nothing, Unit]])(implicit s: Scheduler): Unit =
    context.execute(new Runnable {
      def run(): Unit = {
        token.runAsync { r =>
          for (cb <- stack)
            try {
              cb(r)
            } catch {
              // $COVERAGE-OFF$
              case NonFatal(e) => s.reportFailure(e)
              // $COVERAGE-ON$
            }
        }
      }
    })
}
