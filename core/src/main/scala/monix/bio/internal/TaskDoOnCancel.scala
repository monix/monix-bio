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

import monix.bio.WRYYY.{Async, Context}
import monix.bio.{UIO, WRYYY}
import monix.execution.Callback
import monix.execution.exceptions.CallbackCalledMultipleTimesException
import monix.execution.schedulers.TrampolinedRunnable

private[bio] object TaskDoOnCancel {
  /**
    * Implementation for `Task.doOnCancel`
    */
  def apply[E, A](self: WRYYY[E, A], callback: UIO[Unit]): WRYYY[E, A] = {
    if (callback eq WRYYY.unit) {
      self
    } else {
      val start = (context: Context, onFinish: Callback[E, A]) => {
        implicit val s = context.scheduler
        implicit val o = context.options

        context.connection.push(callback)
        WRYYY.unsafeStartNow(self, context, new CallbackThatPops(context, onFinish))
      }
      Async(start, trampolineBefore = false, trampolineAfter = false, restoreLocals = false)
    }
  }

  private final class CallbackThatPops[E, A](ctx: WRYYY.Context, cb: Callback[E, A])
    extends Callback[E, A] with TrampolinedRunnable {

    private[this] var isActive = true
    private[this] var value: A = _
    private[this] var error: E = _

    override def onSuccess(value: A): Unit =
      if (!tryOnSuccess(value)) {
        throw new CallbackCalledMultipleTimesException("onSuccess")
      }

    override def onError(e: E): Unit =
      if (!tryOnError(e)) {
        throw new CallbackCalledMultipleTimesException("onError")
      }

    override def tryOnSuccess(value: A): Boolean = {
      if (isActive) {
        isActive = false
        ctx.connection.pop()
        this.value = value
        ctx.scheduler.execute(this)
        true
      } else {
        false
      }
    }

    override def tryOnError(e: E): Boolean = {
      if (isActive) {
        isActive = false
        ctx.connection.pop()
        this.error = e
        ctx.scheduler.execute(this)
        true
      } else {
        false
      }
    }

    override def run(): Unit = {
      if (error != null) {
        val e = error
        error = null.asInstanceOf[E]
        cb.onError(e)
      } else {
        val v = value
        value = null.asInstanceOf[A]
        cb.onSuccess(v)
      }
    }
  }
}
