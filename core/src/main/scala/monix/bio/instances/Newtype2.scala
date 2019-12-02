package monix.bio.instances

/** INTERNAL API — Newtype encoding for types with one type parameter.
  *
  * The `Newtype1` abstract class indirection is needed for Scala 2.10,
  * otherwise we could just define these types straight on the
  * companion object. In Scala 2.10 definining these types
  * straight on the companion object yields an error like:
  * ''"only classes can have declared but undefined members"''.
  *
  * Inspired by
  * [[https://github.com/alexknvl/newtypes alexknvl/newtypes]].
  */
private[monix] abstract class Newtype2[F[_, _]] { self =>
  type Base
  trait Tag extends Any
  type Type[+E, +A] <: Base with Tag

  def apply[E, A](fa: F[E, A]): Type[E, A] =
    fa.asInstanceOf[Type[E, A]]

  def unwrap[E, A](fa: Type[E, A]): F[E, A] =
    fa.asInstanceOf[F[E, A]]
}
