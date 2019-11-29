package monix

package object bio {
  type UIO[+A]      = WRYYY[Nothing, A]
  type Task[+A]     = WRYYY[Throwable, A]
}
