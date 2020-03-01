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

package monix.bio

package internal

import cats.effect.{CancelToken, IO, SyncIO}
import monix.execution.internal.AttemptCallback.noop
import monix.execution.{Callback, Scheduler}

import scala.util.control.NonFatal

/** INTERNAL API
  *
  * `Task` integration utilities for the `cats.effect.ConcurrentEffect`
  * instance, provided in `monix.bio.instances`.
  */
private[bio] object TaskEffect {

  /**
    * `cats.effect.Effect#runAsync`
    */
  def runAsync[A](fa: Task[A])(cb: Either[Throwable, A] => IO[Unit])(
    implicit s: Scheduler,
    opts: BIO.Options
  ): SyncIO[Unit] = SyncIO {
    execute(fa, cb); ()
  }

  /**
    * `cats.effect.ConcurrentEffect#runCancelable`
    */
  def runCancelable[A](fa: Task[A])(cb: Either[Throwable, A] => IO[Unit])(
    implicit s: Scheduler,
    opts: BIO.Options
  ): SyncIO[CancelToken[Task]] = SyncIO {
    execute(fa, cb)
  }

  private def execute[A](
    fa: Task[A],
    cb: Either[Throwable, A] => IO[Unit]
  )(implicit s: Scheduler, opts: BIO.Options) = {

    fa.runAsyncOptF(new Callback[Cause[Throwable], A] {
      private def signal(value: Either[Throwable, A]): Unit =
        try cb(value).unsafeRunAsync(noop)
        catch { case NonFatal(e) => s.reportFailure(e) }

      override def onSuccess(value: A): Unit =
        signal(Right(value))
      override def onError(e: Cause[Throwable]): Unit = {
        signal(Left(e.toThrowable))
      }
    })
  }
}
