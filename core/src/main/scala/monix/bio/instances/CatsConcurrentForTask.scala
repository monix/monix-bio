package monix.bio
package instances

import cats.effect.{Async, CancelToken, Concurrent, ExitCase}
import monix.bio.Task
import monix.bio.internal.TaskCreate

/** Cats type class instance of [[monix.bio.Task Task]]
  * for  `cats.effect.Async` and `CoflatMap` (and implicitly for
  * `Applicative`, `Monad`, `MonadError`, etc).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsAsyncForTask extends CatsBaseForTask[Throwable] with Async[Task] {
  override def delay[A](thunk: => A): Task[A] =
    Task.eval(thunk)
  override def suspend[A](fa: => Task[A]): Task[A] =
    Task.defer(fa)
  override def async[A](k: ((Either[Throwable, A]) => Unit) => Unit): Task[A] =
    TaskCreate.async(k)
  // TODO: fail completely instead of ignoring errors in finalizers
  override def bracket[A, B](acquire: Task[A])(use: A => Task[B])(release: A => Task[Unit]): Task[B] =
    acquire.bracket(use)(release.andThen(_.onErrorHandle(_ => ())))
  override def bracketCase[A, B](acquire: Task[A])(use: A => Task[B])(
    release: (A, ExitCase[Throwable]) => Task[Unit]): Task[B] =
    acquire.bracketCase(use)((a, exit) => release(a, exit).onErrorHandle(_ => ()))
  override def asyncF[A](k: (Either[Throwable, A] => Unit) => Task[Unit]): Task[A] =
    TaskCreate.asyncF(k)
  override def guarantee[A](acquire: Task[A])(finalizer: Task[Unit]): Task[A] =
    acquire.guarantee(finalizer.onErrorHandle(_ => ()))
  override def guaranteeCase[A](acquire: Task[A])(finalizer: ExitCase[Throwable] => Task[Unit]): Task[A] =
    acquire.guaranteeCase(exit => finalizer(exit).onErrorHandle(_ => ()))
}

/** Cats type class instance of [[monix.bio.WRYYY WRYYY]]
  * for  `cats.effect.Concurrent`.
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsConcurrentForTask extends CatsAsyncForTask with Concurrent[Task] {
  override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[Task]): Task[A] =
    TaskCreate.cancelableEffect(k)
  override def uncancelable[A](fa: Task[A]): Task[A] =
    fa.uncancelable
  override def start[A](fa: Task[A]): Task[Fiber[Throwable, A]] =
    fa.start
  override def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    Task.racePair(fa, fb)
  override def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    Task.race(fa, fb)
}

/** Default and reusable instance for [[CatsConcurrentForTask]].
  *
  * Globally available in scope, as it is returned by
  * [[monix.bio.WRYYY.catsAsync WRYYY.catsAsync]].
  */
object CatsConcurrentForTask extends CatsConcurrentForTask
