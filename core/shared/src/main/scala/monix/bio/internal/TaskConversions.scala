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

import monix.bio.Task
import monix.execution.rstreams.SingleAssignSubscription
import org.reactivestreams.{Publisher, Subscriber, Subscription}

private[bio] object TaskConversions {

  /**
    * Implementation for `BIO.fromReactivePublisher`.
    */
  def fromReactivePublisher[A](source: Publisher[A]): Task[Option[A]] =
    Task.cancelable0 { (scheduler, cb) =>
      val sub = SingleAssignSubscription()

      source.subscribe {
        new Subscriber[A] {
          private[this] var isActive = true

          override def onSubscribe(s: Subscription): Unit = {
            sub := s
            sub.request(10)
          }

          override def onNext(a: A): Unit = {
            if (isActive) {
              isActive = false
              sub.cancel()
              cb.onSuccess(Some(a))
            }
          }

          override def onError(e: Throwable): Unit = {
            if (isActive) {
              isActive = false
              cb.onError(e)
            } else {
              scheduler.reportFailure(e)
            }
          }

          override def onComplete(): Unit = {
            if (isActive) {
              isActive = false
              cb.onSuccess(None)
            }
          }
        }
      }

      Task(sub.cancel())
    }

}
