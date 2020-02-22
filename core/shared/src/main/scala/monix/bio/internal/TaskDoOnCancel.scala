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

import monix.bio.BIO.{Async, Context}
import monix.bio.{BIO, UIO}
import monix.execution.exceptions.CallbackCalledMultipleTimesException
import monix.execution.schedulers.TrampolinedRunnable

private[bio] object TaskDoOnCancel {

  /**
    * Implementation for `Task.doOnCancel`
    */
  def apply[E, A](self: BIO[E, A], callback: UIO[Unit]): BIO[E, A] = {
    if (callback eq BIO.unit) {
      self
    } else {
      val start = (context: Context[E], onFinish: BiCallback[E, A]) => {
        implicit val s = context.scheduler
        implicit val o = context.options

        context.connection.push(callback)
        BIO.unsafeStartNow(self, context, new CallbackThatPops(context, onFinish))
      }
      Async(start, trampolineBefore = false, trampolineAfter = false, restoreLocals = false)
    }
  }

  private final class CallbackThatPops[E, A](ctx: BIO.Context[E], cb: BiCallback[E, A])
      extends BiCallback[E, A] with TrampolinedRunnable {

    private[this] var isActive = true
    private[this] var value: A = _
    private[this] var error: E = _
    private[this] var terminalError: Throwable = _

    override def onSuccess(value: A): Unit =
      if (!tryOnSuccess(value)) {
        throw new CallbackCalledMultipleTimesException("onSuccess")
      }

    override def onError(e: E): Unit =
      if (!tryOnError(e)) {
        throw new CallbackCalledMultipleTimesException("onError")
      }

    override def onTermination(e: Throwable): Unit =
      if (!tryOnTermination(e)) {
        throw new CallbackCalledMultipleTimesException("onTermination")
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

    override def tryOnTermination(e: Throwable): Boolean = {
      if (isActive) {
        isActive = false
        ctx.connection.pop()
        this.terminalError = e
        ctx.scheduler.execute(this)
        true
      } else {
        false
      }
    }

    override def run(): Unit = {
      if (terminalError != null) {
        val e = terminalError
        terminalError = null
        cb.onTermination(e)
      } else if (error != null) {
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
