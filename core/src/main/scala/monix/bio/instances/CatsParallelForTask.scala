package monix.bio.instances

import cats.{Applicative, Monad, Parallel, ~>}
import monix.bio.WRYYY

/** `cats.Parallel` type class instance for [[monix.bio.WRYYY WRYYY]].
  *
  * A `cats.Parallel` instances means that `Task` can be used for
  * processing tasks in parallel (with non-deterministic effects
  * ordering).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsParallelForTask[E] extends Parallel[WRYYY[E, ?]] {
  override type F[A] = WRYYY.Par[E, A]

  override val applicative: Applicative[WRYYY.Par[E, ?]] = new NondetApplicative[E]
  override val monad: Monad[WRYYY[E, ?]] = new CatsBaseForTask[E]

  override val sequential: WRYYY.Par[E, ?] ~> WRYYY[E, ?] = new (WRYYY.Par[E, ?] ~> WRYYY[E, ?] ) {
    def apply[A](fa: WRYYY.Par[E, A]): WRYYY[E, A] = WRYYY.Par.unwrap(fa)
  }
  override val parallel: WRYYY[E, ?]  ~> WRYYY.Par[E, ?] = new (WRYYY[E, ?]  ~> WRYYY.Par[E, ?]) {
    def apply[A](fa: WRYYY[E, A]): WRYYY.Par[E, A] = WRYYY.Par.apply(fa)
  }
}


  private class NondetApplicative[E] extends Applicative[WRYYY.Par[E, ?]] {

    import WRYYY.Par.{unwrap, apply => par}

    override def ap[A, B](ff: WRYYY.Par[E, A => B])(fa: WRYYY.Par[E, A]): WRYYY.Par[E, B] =
      par(WRYYY.mapBoth(unwrap(ff), unwrap(fa))(_(_)))
    override def map2[A, B, Z](fa: WRYYY.Par[E, A], fb: WRYYY.Par[E, B])(f: (A, B) => Z): WRYYY.Par[E, Z] =
      par(WRYYY.mapBoth(unwrap(fa), unwrap(fb))(f))
    override def product[A, B](fa: WRYYY.Par[E, A], fb: WRYYY.Par[E, B]): WRYYY.Par[E, (A, B)] =
      par(WRYYY.mapBoth(unwrap(fa), unwrap(fb))((_, _)))
    override def pure[A](a: A): WRYYY.Par[E, A] =
      par(WRYYY.now(a))
    override val unit: WRYYY.Par[E, Unit] =
      par(WRYYY.unit)
    override def map[A, B](fa: WRYYY.Par[E, A])(f: A => B): WRYYY.Par[E, B] =
      par(unwrap(fa).map(f))
  }
