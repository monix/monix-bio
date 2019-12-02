package monix.bio.instances

import cats.{CoflatMap, Eval, MonadError, SemigroupK}
import monix.bio.{UIO, WRYYY}

import scala.util.Try

/** Cats type class instances for [[monix.bio.Task Task]]
  * for  `cats.MonadError` and `CoflatMap` (and implicitly for
  * `Applicative`, `Monad`, etc).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsBaseForTask[E] extends MonadError[WRYYY[E, ?], E] with CoflatMap[WRYYY[E, ?]] with SemigroupK[WRYYY[E, ?]] {
  override def pure[A](a: A): UIO[A] =
    WRYYY.pure(a)
  override val unit: UIO[Unit] =
    WRYYY.unit
  override def flatMap[A, B](fa: WRYYY[E, A])(f: (A) => WRYYY[E, B]): WRYYY[E, B] =
    fa.flatMap(f)
  override def flatten[A](ffa: WRYYY[E, WRYYY[E, A]]): WRYYY[E, A] =
    ffa.flatten
  override def tailRecM[A, B](a: A)(f: (A) => WRYYY[E, Either[A, B]]): WRYYY[E, B] =
    WRYYY.tailRecM(a)(f)
  override def ap[A, B](ff: WRYYY[E, (A) => B])(fa: WRYYY[E, A]): WRYYY[E, B] =
    for (f <- ff; a <- fa) yield f(a)
  override def map2[A, B, Z](fa: WRYYY[E, A], fb: WRYYY[E, B])(f: (A, B) => Z): WRYYY[E, Z] =
    for (a <- fa; b <- fb) yield f(a, b)
  override def product[A, B](fa: WRYYY[E, A], fb: WRYYY[E, B]): WRYYY[E, (A, B)] =
    for (a <- fa; b <- fb) yield (a, b)
  override def map[A, B](fa: WRYYY[E, A])(f: (A) => B): WRYYY[E, B] =
    fa.map(f)
  override def raiseError[A](e: E): WRYYY[E, A] =
    WRYYY.raiseError(e)
  override def handleError[A](fa: WRYYY[E, A])(f: (E) => A): WRYYY[E, A] =
    fa.onErrorHandle(f)
  override def handleErrorWith[A](fa: WRYYY[E, A])(f: (E) => WRYYY[E, A]): WRYYY[E, A] =
    fa.onErrorHandleWith(f)
  override def recover[A](fa: WRYYY[E, A])(pf: PartialFunction[E, A]): WRYYY[E, A] =
    fa.onErrorRecover(pf)
  override def recoverWith[A](fa: WRYYY[E, A])(pf: PartialFunction[E, WRYYY[E, A]]): WRYYY[E, A] =
    fa.onErrorRecoverWith(pf)
  override def attempt[A](fa: WRYYY[E, A]): WRYYY[E, Either[E, A]] =
    fa.attempt
  override def catchNonFatal[A](a: => A)(implicit ev: <:<[Throwable, E]): WRYYY[E, A] =
    WRYYY.eval(a).asInstanceOf[WRYYY[E, A]]
  override def catchNonFatalEval[A](a: Eval[A])(implicit ev: <:<[Throwable, E]): WRYYY[E, A] =
    WRYYY.eval(a.value).asInstanceOf[WRYYY[E, A]]
  override def fromTry[A](t: Try[A])(implicit ev: <:<[Throwable, E]): WRYYY[E, A] =
    WRYYY.fromTry(t).asInstanceOf[WRYYY[E, A]]
  override def coflatMap[A, B](fa: WRYYY[E, A])(f: (WRYYY[E, A]) => B): WRYYY[E, B] =
    fa.start.map(fiber => f(fiber.join))
  override def coflatten[A](fa: WRYYY[E, A]): WRYYY[E, WRYYY[E, A]] =
    fa.start.map(_.join)
  override def combineK[A](ta: WRYYY[E, A], tb: WRYYY[E, A]): WRYYY[E, A] =
    ta.onErrorHandleWith(_ => tb)
}