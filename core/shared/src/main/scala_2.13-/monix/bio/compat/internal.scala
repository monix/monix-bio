package monix.bio.compat

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import monix.execution.compat.BuildFrom

private[monix] object internal {

  type IterableOnce[+X] = scala.collection.GenTraversableOnce[X]
  def toIterator[X](i: IterableOnce[X]): Iterator[X] = i.toIterator
  def hasDefiniteSize[X](i: IterableOnce[X]): Boolean = i.hasDefiniteSize

  def newBuilder[From, A, C](bf: BuildFrom[From, A, C], from: From): mutable.Builder[A, C] = bf.apply(from)
}
