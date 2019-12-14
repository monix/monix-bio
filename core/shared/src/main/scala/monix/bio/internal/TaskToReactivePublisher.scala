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

import monix.bio.BIO
import monix.execution.rstreams.Subscription
import monix.execution.{Scheduler, UncaughtExceptionReporter}
import org.reactivestreams.Subscriber

private[bio] object TaskToReactivePublisher {

  /**
    * Implementation for `BIO.toReactivePublisher`
    */
  def apply[E, A](self: BIO[E, A])(implicit s: Scheduler, ev: E <:< Throwable): org.reactivestreams.Publisher[A] =
    new org.reactivestreams.Publisher[A] {

      def subscribe(out: Subscriber[_ >: A]): Unit = {
        out.onSubscribe {
          new Subscription {
            private[this] var isActive = true
            private[this] val conn = TaskConnection[E]()
            private[this] val context = BIO.Context(s, BIO.defaultOptions.withSchedulerFeatures, conn)

            def request(n: Long): Unit = {
              require(n > 0, "n must be strictly positive, according to the Reactive Streams contract, rule 3.9")
              if (isActive) {
                BIO.unsafeStartEnsureAsync(self, context, new PublisherCallback[E, A](out))
              }
            }

            def cancel(): Unit = {
              isActive = false
              conn.cancel.runAsyncAndForget
            }
          }
        }
      }

    }

  private final class PublisherCallback[E, A](
    out: Subscriber[_ >: A]
  )(implicit logger: UncaughtExceptionReporter, ev: E <:< Throwable)
      extends BiCallback[E, A] {

    private[this] var isActive = true

    override def onFatalError(e: Throwable): Unit =
      if (isActive) {
        isActive = false
        out.onError(e)
      } else {
        logger.reportFailure(e)
      }

    override def onError(e: E): Unit =
      onFatalError(e)

    override def onSuccess(value: A): Unit =
      if (isActive) {
        isActive = false
        out.onNext(value)
        out.onComplete()
      }

  }

}
