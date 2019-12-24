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

import cats.effect._
import cats.~>
import monix.bio.internal.TaskConversions

import scala.annotation.implicitNotFound

/**
  * A lawless type class that specifies conversions from `Task`
  * to similar data types (i.e. pure, asynchronous, preferably
  * cancelable).
  */
@implicitNotFound("""Cannot find implicit value for TaskLift[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait TaskLift[F[_]] extends (Task ~> F) {

  /**
    * Converts `Task[A]` into `F[A]`.
    *
    * The operation should preserve referential transparency and if
    * possible runtime characteristics (e.g. the result should not
    * block threads if the source doesn't) and interruptibility
    * (although this isn't possible for conversions to
    * `cats.effect.Async` data types that are not also `Concurrent`).
    */
  def apply[A](task: Task[A]): F[A]

}

object TaskLift extends TaskLiftImplicits0 {

  /**
    * Returns the available [[TaskLift]] instance for `F`.
    */
  def apply[F[_]](implicit F: TaskLift[F]): TaskLift[F] = F

  /**
    * Instance for converting into a `Task`, being the identity function.
    */
  implicit val toTask: TaskLift[Task] =
    new TaskLift[Task] {
      def apply[A](task: Task[A]): Task[A] = task
    }

  /**
    * Instance for converting to
    * [[https://typelevel.org/cats-effect/datatypes/io.html cats.effect.IO]].
    */
  implicit def toIO(implicit eff: ConcurrentEffect[Task]): TaskLift[IO] =
    new TaskLift[IO] {
      def apply[A](task: Task[A]): IO[A] = TaskConversions.toIO(task)(eff)
    }

}

private[bio] abstract class TaskLiftImplicits0 extends TaskLiftImplicits1 {

  /**
    * Instance for converting to any type implementing
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    */
  implicit def toConcurrent[F[_]](implicit F: Concurrent[F], eff: ConcurrentEffect[Task]): TaskLift[F] =
    new TaskLift[F] {
      def apply[A](task: Task[A]): F[A] = task.toConcurrent(F, eff, null)
    }

}

private[bio] abstract class TaskLiftImplicits1 extends TaskLiftImplicits2 {

  /**
    * Instance for converting to any type implementing
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    */
  implicit def toAsync[F[_]](implicit F: Async[F], eff: Effect[Task]): TaskLift[F] =
    new TaskLift[F] {
      def apply[A](task: Task[A]): F[A] = task.toAsync(F, eff, null)
    }

}

private[bio] abstract class TaskLiftImplicits2 {

  /**
    * Instance for converting to any type implementing
    * [[https://typelevel.org/cats-effect/typeclasses/liftio.html cats.effect.LiftIO]].
    */
  implicit def toAnyLiftIO[F[_]](implicit F: LiftIO[F], eff: ConcurrentEffect[Task]): TaskLift[F] =
    new TaskLift[F] {
      def apply[A](task: Task[A]): F[A] = F.liftIO(TaskConversions.toIO(task)(eff))
    }

}
