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

package monix.bio

import cats.laws._
import cats.laws.discipline._
import monix.execution.exceptions.DummyException
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskConversionsSuite extends BaseTestSuite {

  test("BIO.fromReactivePublisher converts `.onNext` callbacks") { implicit s =>
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onNext(123)
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Right(Some(123)))))
  }

  test("BIO.fromReactivePublisher converts `.onComplete` callbacks") { implicit s =>
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Right(None))))
  }

  test("BIO.fromReactivePublisher converts `.onError` callbacks") { implicit s =>
    val dummy = DummyException("Error")
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                s.onError(dummy)
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromReactivePublisher protects against user errors") { implicit s =>
    val dummy = DummyException("Error")
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            def request(n: Long): Unit = throw dummy
            def cancel(): Unit = throw dummy
          }
        }
      }
    }

    assertEquals(BIO.fromReactivePublisher(pub).runToFuture.value, Some(Failure(dummy)))
  }

  test("BIO.fromReactivePublisher is cancelable") { implicit s =>
    var wasRequested = false
    val pub = new Publisher[Int] {
      def subscribe(s: Subscriber[_ >: Int]): Unit = {
        s.onSubscribe {
          new Subscription {
            var isActive = true

            def request(n: Long): Unit =
              if (n > 0 && isActive) {
                isActive = false
                wasRequested = true
                s.onNext(123)
                s.onComplete()
              }

            def cancel(): Unit =
              isActive = false
          }
        }
      }
    }

    val bio = BIO.fromReactivePublisher(pub).delayExecution(1.second)
    val f = bio.runToFuture
    f.cancel()

    s.tick()
    assert(!wasRequested, "nothing should be requested")
    assert(s.state.tasks.isEmpty, "task should be canceled")
    assertEquals(f.value, None)

    s.tick(1.second)
    assert(!wasRequested, "nothing should be requested")
    assert(s.state.tasks.isEmpty, "task should be canceled")
    assertEquals(f.value, None)
  }

  test("BIO.fromReactivePublisher <-> Task") { implicit s =>
    check1 { task: Task[Int] =>
      BIO.fromReactivePublisher(task.toReactivePublisher) <-> task.map(Some(_))
    }
  }

}
