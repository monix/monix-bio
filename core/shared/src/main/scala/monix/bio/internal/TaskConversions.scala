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

import cats.effect.{Async, Concurrent, ConcurrentEffect, Effect, IO}
import monix.bio.BIO.Context
import monix.bio.{BIO, Task}
import monix.execution.rstreams.SingleAssignSubscription
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Callback, Scheduler}
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.util.control.NonFatal

private[bio] object TaskConversions {

  /**
    * Implementation for `BIO.toIO`.
    */
  def toIO[A](source: Task[A])(implicit eff: ConcurrentEffect[Task]): IO[A] =
    source match {
      case BIO.Now(value) => IO.pure(value)
      case BIO.Error(e) => IO.raiseError(e)
      case BIO.Termination(e) => IO.raiseError(e)
      case BIO.Eval(thunk) => IO(thunk())
      case BIO.EvalTotal(thunk) => IO(thunk())
      case _ =>
        IO.cancelable { cb =>
          toIO(eff.runCancelable(source)(r => { cb(r); IO.unit }).unsafeRunSync())
        }
    }

  /**
    * Implementation for `BIO.toConcurrent`.
    */
  def toConcurrent[F[_], A](source: Task[A])(implicit F: Concurrent[F], eff: ConcurrentEffect[Task]): F[A] =
    source match {
      case BIO.Now(value) => F.pure(value)
      case BIO.Error(e) => F.raiseError(e)
      case BIO.Termination(e) => F.raiseError(e)
      case BIO.Eval(thunk) => F.delay(thunk())
      case BIO.EvalTotal(thunk) => F.delay(thunk())
      case _ =>
        F.cancelable { cb =>
          val token = eff.runCancelable(source)(r => { cb(r); IO.unit }).unsafeRunSync()
          toConcurrent(token)(F, eff)
        }
    }

  /**
    * Implementation for `BIO.toAsync`.
    */
  def toAsync[F[_], A](source: Task[A])(implicit F: Async[F], eff: Effect[Task]): F[A] =
    source match {
      case BIO.Now(value) => F.pure(value)
      case BIO.Error(e) => F.raiseError(e)
      case BIO.Termination(e) => F.raiseError(e)
      case BIO.Eval(thunk) => F.delay(thunk())
      case BIO.EvalTotal(thunk) => F.delay(thunk())
      case _ => F.async(cb => eff.runAsync(source)(r => { cb(r); IO.unit }).unsafeRunSync())
    }

  /**
    * Implementation for `BIO.fromEffect`.
    */
  def fromEffect[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    fa.asInstanceOf[AnyRef] match {
      case task: Task[A] @unchecked => task
      case io: IO[A] @unchecked => io.to[Task]
      case _ => fromEffect0(fa)
    }

  private def fromEffect0[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] = {
    def start(ctx: Context[Throwable], cb: Callback[Throwable, A]): Unit = {
      try {
        implicit val sc: Scheduler = ctx.scheduler
        val io = F.runAsync(fa)(new CreateCallback(null, cb))
        io.unsafeRunSync()
      } catch {
        case NonFatal(e) => ctx.scheduler.reportFailure(e)
      }
    }

    BIO.Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  /**
    * Implementation for `BIO.fromConcurrentEffect`.
    */
  def fromConcurrentEffect[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] =
    fa.asInstanceOf[AnyRef] match {
      case task: Task[A] @unchecked => task
      case io: IO[A] @unchecked => io.to[Task]
      case _ => fromConcurrentEffect0(fa)
    }

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

  private def fromConcurrentEffect0[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] = {
    def start(ctx: Context[Throwable], cb: Callback[Throwable, A]): Unit = {
      try {
        implicit val sc: Scheduler = ctx.scheduler

        val conn = ctx.connection
        val cancelable = TaskConnectionRef[Throwable]()
        conn.push(cancelable.cancel)

        val syncIO = F.runCancelable(fa)(new CreateCallback[A](conn, cb))
        cancelable := fromEffect(syncIO.unsafeRunSync())
      } catch {
        case e if NonFatal(e) => ctx.scheduler.reportFailure(e)
      }
    }

    BIO.Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  private final class CreateCallback[A](
    conn: TaskConnection[Throwable],
    cb: Callback[Throwable, A]
  )(implicit s: Scheduler)
      extends (Either[Throwable, A] => IO[Unit]) with TrampolinedRunnable {

    private[this] var canCall = true
    private[this] var value: Either[Throwable, A] = _

    override def run(): Unit = {
      if (canCall) {
        canCall = false
        if (conn ne null) conn.pop()
        cb(value)
        value = null
      }
    }

    override def apply(value: Either[Throwable, A]): IO[Unit] = {
      this.value = value
      s.execute(this)
      IO.unit
    }

  }

}
