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

import cats.effect._
import cats.{~>, Eval}
import monix.catnap.FutureLift
import monix.execution.CancelablePromise

import scala.annotation.implicitNotFound
import scala.concurrent.Future
import scala.util.Try

/** A lawless type class that provides conversions into a [[BIO.Unsafe]].
  *
  * Sample:
  * {{{
  *   // Conversion from cats.Eval
  *   import cats.Eval
  *
  *   val source0 = Eval.always(1 + 1)
  *   val task0 = TaskLike[Eval].apply(source0)
  *
  *   // Conversion from Future
  *   import scala.concurrent.Future
  *
  *   val source1 = Future.successful(1 + 1)
  *   val task1 = TaskLike[Future].apply(source1)
  *
  *   // Conversion from IO
  *   import cats.effect.IO
  *
  *   val source2 = IO(1 + 1)
  *   val task2 = TaskLike[IO].apply(source2)
  * }}}
  *
  * This is an alternative to the usage of [[cats.effect.Effect]],
  * where the internals are specialized to `Task` anyway, like for
  * example the implementation of `monix.reactive.Observable`.
  */
@implicitNotFound("""Cannot find implicit value for TaskLike[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait TaskLike[F[_]] extends (F ~> BIO.Unsafe) {

  /**
    * Converts `F[A]` into a `BIO.Unsafe[A]`, preserving referential
    * transparency if `F[_]` is a pure data type and preserving
    * interruptibility if the source is cancelable.
    */
  def apply[A](fa: F[A]): BIO.Unsafe[A]

}

object TaskLike extends TaskLikeImplicits0 {

  /**
    * Returns the available instance for `F`.
    */
  def apply[F[_]](implicit F: TaskLike[F]): TaskLike[F] = F

  /**
    * Instance for `Task`, returning the same reference.
    */
  implicit val fromTask: TaskLike[BIO.Unsafe] =
    new TaskLike[BIO.Unsafe] {
      def apply[A](fa: BIO.Unsafe[A]): BIO.Unsafe[A] =
        fa
    }

  /**
    * Converts `scala.concurrent.Future` into a `Task`.
    */
  implicit val fromFuture: TaskLike[Future] =
    new TaskLike[Future] {
      def apply[A](fa: Future[A]): BIO.Unsafe[A] =
        BIO.fromFuture(fa)
    }

  /**
    * Converts `cats.Eval` into a `Task`.
    */
  implicit val fromEval: TaskLike[Eval] =
    new TaskLike[Eval] {
      def apply[A](fa: Eval[A]): BIO.Unsafe[A] =
        Concurrent.liftIO[BIO.Unsafe, A](IO.eval(fa))
    }

  /**
    * Converts [[https://typelevel.org/cats-effect/datatypes/io.html cats.effect.IO]]
    * into a `Task`.
    */
  implicit val fromIO: TaskLike[IO] =
    new TaskLike[IO] {
      def apply[A](fa: IO[A]): BIO.Unsafe[A] =
        Concurrent.liftIO[BIO.Unsafe, A](fa)
    }

  /**
    * Converts [[cats.effect.SyncIO]] into a `Task`.
    */
  implicit val fromSyncIO: TaskLike[SyncIO] =
    new TaskLike[SyncIO] {
      def apply[A](fa: SyncIO[A]): BIO.Unsafe[A] =
        Concurrent.liftIO[BIO.Unsafe, A](fa.toIO)
    }

  /**
    * Converts [[scala.util.Try]] into a `Task`.
    */
  implicit val fromTry: TaskLike[Try] =
    new TaskLike[Try] {
      def apply[A](fa: Try[A]): BIO.Unsafe[A] =
        BIO.fromTry(fa)
    }

  /**
    * Converts [[monix.execution.CancelablePromise]] into a `Task`.
    */
  implicit val fromCancelablePromise: TaskLike[CancelablePromise] =
    new TaskLike[CancelablePromise] {
      def apply[A](p: CancelablePromise[A]): BIO.Unsafe[A] =
        BIO.fromCancelablePromise(p)
    }

  /**
    * Converts `Function0` (parameter-less function, also called a thunk)
    * into a `Task`.
    */
  implicit val fromFunction0: TaskLike[Function0] =
    new TaskLike[Function0] {
      def apply[A](thunk: () => A): BIO.Unsafe[A] =
        BIO.Eval(thunk)
    }

  /**
    * Converts `scala.util.Either` into a `Task`.
    */
  implicit def fromEither[E <: Throwable]: TaskLike[Either[E, *]] =
    new TaskLike[Either[E, *]] {
      def apply[A](fa: Either[E, A]): BIO.Unsafe[A] =
        BIO.fromEither(fa)
    }

}

private[bio] abstract class TaskLikeImplicits0 extends TaskLikeImplicits1 {

  /**
    * Converts [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.ConcurrentEffect]]
    * into a `Task`.
    */
  implicit def fromConcurrentEffect[F[_]](implicit F: ConcurrentEffect[F]): TaskLike[F] =
    new TaskLike[F] {
      def apply[A](fa: F[A]): BIO.Unsafe[A] =
        BIO.fromConcurrentEffect(fa)
    }

}

private[bio] abstract class TaskLikeImplicits1 extends TaskLikeImplicits2 {

  /**
    * Converts [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.Async]]
    * into a `Task`.
    */
  implicit def fromEffect[F[_]](implicit F: Effect[F]): TaskLike[F] =
    new TaskLike[F] {
      def apply[A](fa: F[A]): BIO.Unsafe[A] =
        BIO.fromEffect(fa)
    }

}

private[bio] abstract class TaskLikeImplicits2 {

  /**
    * Converts any Future-like datatype into a `Task`, via [[monix.catnap.FutureLift]].
    */
  implicit def fromFutureLift[F[_]](implicit F: FutureLift[BIO.Unsafe, F]): TaskLike[F] =
    new TaskLike[F] {
      def apply[A](fa: F[A]): BIO.Unsafe[A] =
        BIO.fromFutureLike(BIO.now(fa))
    }

}
