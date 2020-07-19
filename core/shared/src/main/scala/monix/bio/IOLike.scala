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

import cats.effect.{IO => CIO, _}
import cats.{~>, Eval}
import monix.catnap.FutureLift
import monix.execution.CancelablePromise

import scala.annotation.implicitNotFound
import scala.concurrent.Future
import scala.util.Try

/** A lawless type class that provides conversions into a [[IO]].
  *
  * Sample:
  * {{{
  *   // Conversion from cats.Eval
  *   import cats.Eval
  *
  *   val source0 = Eval.always(1 + 1)
  *   val task0 = IOLike[Eval].apply(source0)
  *
  *   // Conversion from Future
  *   import scala.concurrent.Future
  *
  *   val source1 = Future.successful(1 + 1)
  *   val task1 = IOLike[Future].apply(source1)
  *
  *   // Conversion from IO
  *   import cats.effect.{IO => CIO}
  *
  *   val source2 = CIO(1 + 1)
  *   val task2 = IOLike[CIO].apply(source2)
  * }}}
  *
  * This is an alternative to the usage of [[cats.effect.Effect]],
  * where the internals are specialized to `IO` anyway, like for
  * example the implementation of `monix.reactive.Observable`.
  */
@implicitNotFound("""Cannot find implicit value for IOLike[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait IOLike[F[_]] extends (F ~> Task) {

  /**
    * Converts `F[A]` into a `Task[A]`, preserving referential
    * transparency if `F[_]` is a pure data type and preserving
    * interruptibility if the source is cancelable.
    */
  def apply[A](fa: F[A]): Task[A]

}

object IOLike extends IOLikeImplicits0 {

  /**
    * Returns the available instance for `F`.
    */
  def apply[F[_]](implicit F: IOLike[F]): IOLike[F] = F

  /**
    * Instance for `Task`, returning the same reference.
    */
  implicit val fromTask: IOLike[Task] =
    new IOLike[Task] {
      def apply[A](fa: Task[A]): Task[A] =
        fa
    }

  /**
    * Converts `scala.concurrent.Future` into a `monix.bio.Task`.
    */
  implicit val fromFuture: IOLike[Future] =
    new IOLike[Future] {
      def apply[A](fa: Future[A]): Task[A] =
        IO.fromFuture(fa)
    }

  /**
    * Converts `cats.Eval` into a `Task`.
    */
  implicit val fromEval: IOLike[Eval] =
    new IOLike[Eval] {
      def apply[A](fa: Eval[A]): Task[A] =
        Concurrent.liftIO[Task, A](CIO.eval(fa))
    }

  /**
    * Converts [[https://typelevel.org/cats-effect/datatypes/io.html cats.effect.IO]]
    * into a `Task`.
    */
  implicit val fromIO: IOLike[CIO] =
    new IOLike[CIO] {
      def apply[A](fa: CIO[A]): Task[A] =
        Concurrent.liftIO[Task, A](fa)
    }

  /**
    * Converts [[cats.effect.SyncIO]] into a `Task`.
    */
  implicit val fromSyncIO: IOLike[SyncIO] =
    new IOLike[SyncIO] {
      def apply[A](fa: SyncIO[A]): Task[A] =
        Concurrent.liftIO[Task, A](fa.toIO)
    }

  /**
    * Converts [[scala.util.Try]] into a `Task`.
    */
  implicit val fromTry: IOLike[Try] =
    new IOLike[Try] {
      def apply[A](fa: Try[A]): Task[A] =
        IO.fromTry(fa)
    }

  /**
    * Converts [[monix.execution.CancelablePromise]] into a `Task`.
    */
  implicit val fromCancelablePromise: IOLike[CancelablePromise] =
    new IOLike[CancelablePromise] {
      def apply[A](p: CancelablePromise[A]): Task[A] =
        IO.fromCancelablePromise(p)
    }

  /**
    * Converts `Function0` (parameter-less function, also called a thunk)
    * into a `Task`.
    */
  implicit val fromFunction0: IOLike[Function0] =
    new IOLike[Function0] {
      def apply[A](thunk: () => A): Task[A] =
        IO.Eval(thunk)
    }

  /**
    * Converts `scala.util.Either` into a `Task`.
    */
  implicit def fromEither[E <: Throwable]: IOLike[Either[E, *]] =
    new IOLike[Either[E, *]] {
      def apply[A](fa: Either[E, A]): Task[A] =
        IO.fromEither(fa)
    }

}

private[bio] abstract class IOLikeImplicits0 extends IOLikeImplicits1 {

  /**
    * Converts [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.ConcurrentEffect]]
    * into a `Task`.
    */
  implicit def fromConcurrentEffect[F[_]](implicit F: ConcurrentEffect[F]): IOLike[F] =
    new IOLike[F] {
      def apply[A](fa: F[A]): Task[A] =
        IO.fromConcurrentEffect(fa)
    }

}

private[bio] abstract class IOLikeImplicits1 extends IOLikeImplicits2 {

  /**
    * Converts [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.Async]]
    * into a `Task`.
    */
  implicit def fromEffect[F[_]](implicit F: Effect[F]): IOLike[F] =
    new IOLike[F] {
      def apply[A](fa: F[A]): Task[A] =
        IO.fromEffect(fa)
    }

}

private[bio] abstract class IOLikeImplicits2 {

  /**
    * Converts any Future-like datatype into a `Task`, via [[monix.catnap.FutureLift]].
    */
  implicit def fromFutureLift[F[_]](implicit F: FutureLift[Task, F]): IOLike[F] =
    new IOLike[F] {
      def apply[A](fa: F[A]): Task[A] =
        IO.fromFutureLike(IO.now(fa))
    }

}
