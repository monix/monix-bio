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

import cats.effect.ExitCase
import cats.effect.concurrent.Deferred
import monix.bio.{BIO, Cause, Task, UIO}
import monix.catnap.ConcurrentQueue
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.{BufferCapacity, ChannelType}

private[bio] object TaskGatherN {

  def apply[E, A](
    parallelism: Int,
    in: Iterable[BIO[E, A]]
  ): BIO[E, List[A]] = {
    val itemSize = in.size

    if (itemSize == 0) {
      BIO.pure(List.empty)
    } else if (itemSize == 1) {
      in.head.map(List(_))
    } else {
      for {
        error <- Deferred[Task, Cause[E]].hideErrors
        queue <- ConcurrentQueue
          .withConfig[Task, (Deferred[Task, A], BIO[E, A])](BufferCapacity.Bounded(itemSize), ChannelType.SPMC)
          .hideErrors
        pairs <- BIO.traverse(in.toList)(task => Deferred[Task, A].map(p => (p, task)).hideErrors)
        _     <- queue.offerMany(pairs).hideErrors
        workers = UIO.gather(List.fill(parallelism.min(itemSize)) {
          queue.poll.hideErrors.flatMap {
            case (p, task) =>
              task.redeemCauseWith(
                err =>
                  error
                    .complete(err)
                    // error already registered, needs to report others so they are not lost
                    .onErrorHandleWith(_ =>
                      BIO.deferAction(s => UIO(s.reportFailure(err.fold(identity, UncaughtErrorException.wrap))))
                    ),
                a => p.complete(a).hideErrors
              )
          }.loopForever.start
        })
        res <- workers.bracketCase { _ =>
          BIO
            .race(
              error.get,
              BIO.sequence(pairs.map(_._1.get))
            )
            .hideErrors
            .flatMap {
              case Left(Cause.Error(err)) =>
                BIO.raiseError(err)
              case Left(Cause.Termination(err)) =>
                BIO.terminate(err)
              case Right(values) =>
                BIO.pure(values)
            }
        } {
          case (fiber, exit) =>
            exit match {
              case ExitCase.Completed => UIO.unit
              case _ => BIO.traverse(fiber)(_.cancel).void
            }
        }
      } yield res
    }
  }
}
