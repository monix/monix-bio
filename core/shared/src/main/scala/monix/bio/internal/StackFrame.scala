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

package monix.bio.internal

/** A mapping function type that is also able to handle errors.
  *
  * Used in the `Task` and `Coeval` implementations to specify
  * error handlers in their respective `FlatMap` internal states.
  */
private[bio] abstract class StackFrame[E, -A, +R] extends (A => R) { self =>

  def apply(a: A): R
  def recover(e: E): R
}

// TODO: maybe add recoverFatal to StackFrame itself and throw when it's not possible?

private[bio] object StackFrame {

  /** [[StackFrame]] used in the implementation of `redeemWith`. */
  final class RedeemWith[E, -A, +R](fe: E => R, fa: A => R) extends StackFrame[E, A, R] {

    def apply(a: A): R = fa(a)
    def recover(e: E): R = fe(e)
  }

  /** [[StackFrame]] reference that only handles errors,
    * useful for quick filtering of `onErrorHandleWith` frames.
    */
  final class ErrorHandler[E, -A, +R](fe: E => R, fa: A => R) extends StackFrame[E, A, R] {

    def apply(a: A): R = fa(a)
    def recover(e: E): R = fe(e)
  }

  abstract class FatalStackFrame[E, -A, +R] extends StackFrame[E, A, R] {
    def apply(a: A): R
    def recover(e: E): R
    def recoverFatal(e: Throwable): R
  }

  final class RedeemFatalWith[-A, +R](fe: Throwable => R, fa: A => R) extends FatalStackFrame[Throwable, A, R] {
    override def apply(a: A): R = fa(a)

    override def recover(e: Throwable): R = fe(e)

    override def recoverFatal(e: Throwable): R = fe(e)
  }
}
