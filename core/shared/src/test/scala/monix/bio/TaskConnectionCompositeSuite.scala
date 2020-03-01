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

import monix.bio.internal.TaskConnectionComposite
import monix.catnap.CancelableF
import monix.execution.Cancelable
import monix.execution.cancelables.BooleanCancelable

object TaskConnectionCompositeSuite extends BaseTestSuite {
  test("cancels Cancelable references") { implicit sc =>
    val conn = TaskConnectionComposite[Nothing]()
    val b1 = BooleanCancelable()
    val b2 = BooleanCancelable()

    conn += b1
    conn += b2
    assert(!b1.isCanceled, "!b1.isCanceled")
    assert(!b2.isCanceled, "!b2.isCanceled")

    conn.cancel.runAsyncAndForget; sc.tick()

    assert(b1.isCanceled)
    assert(b2.isCanceled)
  }

  test("cancels Cancelable references after cancel on assignment") { implicit sc =>
    val conn = TaskConnectionComposite[Nothing]()
    val b1 = BooleanCancelable()
    val b2 = BooleanCancelable()

    conn.cancel.runAsyncAndForget; sc.tick()

    conn += b1
    conn += b2

    assert(b1.isCanceled)
    assert(b2.isCanceled)
  }

  test("cancels Task references") { implicit sc =>
    var effect = 0
    val conn = TaskConnectionComposite[Throwable]()
    val b1 = UIO { effect += 1 }
    val b2 = UIO { effect += 1 }

    conn += b1
    conn += b2
    assertEquals(effect, 0)

    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 2)

    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 2)

    conn += b1
    conn += b2
    assertEquals(effect, 4)
  }

  test("cancels Task references after cancel on assignment") { implicit sc =>
    var effect = 0
    val conn = TaskConnectionComposite[Throwable]()
    val b1 = UIO { effect += 1 }
    val b2 = UIO { effect += 1 }

    conn.cancel.runAsyncAndForget; sc.tick()

    conn += b1
    conn += b2
    assertEquals(effect, 2)

    conn += b1
    conn += b2
    assertEquals(effect, 4)
  }

  test("cancels CancelableF references") { implicit sc =>
    var effect = 0
    val conn = TaskConnectionComposite[Throwable]()
    val b1 = CancelableF.wrap(UIO { effect += 1 })
    val b2 = CancelableF.wrap(UIO { effect += 1 })

    conn += b1
    conn += b2
    assertEquals(effect, 0)

    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 2)

    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 2)

    conn += b1
    conn += b2
    assertEquals(effect, 4)
  }

  test("cancels CancelableF references after cancel on assignment") { implicit sc =>
    var effect = 0
    val conn = TaskConnectionComposite[Throwable]()
    val b1 = CancelableF.wrap(UIO { effect += 1 })
    val b2 = CancelableF.wrap(UIO { effect += 1 })

    conn.cancel.runAsyncAndForget; sc.tick()

    conn += b1
    conn += b2
    assertEquals(effect, 2)

    conn += b1
    conn += b2
    assertEquals(effect, 4)
  }

  test("addAll") { implicit sc =>
    var effect = 0
    val task1 = UIO { effect += 1 }
    val task2 = UIO { effect += 2 }
    val task3 = UIO { effect += 3 }

    val conn = TaskConnectionComposite[Throwable]()
    conn.addAll(Seq(task1, task2, task3))
    assertEquals(effect, 0)

    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 6)

    conn.addAll(Seq(task1, task2, task3)); sc.tick()
    assertEquals(effect, 12)
  }

  test("remove Task") { implicit sc =>
    var effect = 0
    val task1 = UIO { effect += 1 }
    val task2 = UIO { effect += 2 }
    val task3 = UIO { effect += 3 }

    val conn = TaskConnectionComposite[Throwable]()
    conn.addAll(Seq(task1, task2, task3))
    assertEquals(effect, 0)

    conn.remove(task2)
    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 4)
  }

  test("remove Cancelable") { implicit sc =>
    var effect = 0
    val task1 = Cancelable { () =>
      effect += 1
    }
    val task2 = Cancelable { () =>
      effect += 2
    }
    val task3 = Cancelable { () =>
      effect += 3
    }

    val conn = TaskConnectionComposite[Throwable]()
    for (ref <- Seq(task1, task2, task3)) conn += ref
    assertEquals(effect, 0)

    conn.remove(task2)
    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 4)
  }

  test("remove CancelableF") { implicit sc =>
    var effect = 0
    val task1 = CancelableF.wrap(UIO { effect += 1 })
    val task2 = CancelableF.wrap(UIO { effect += 2 })
    val task3 = CancelableF.wrap(UIO { effect += 3 })

    val conn = TaskConnectionComposite[Throwable]()
    for (ref <- Seq(task1, task2, task3)) conn += ref
    assertEquals(effect, 0)

    conn.remove(task2)
    conn.cancel.runAsyncAndForget; sc.tick()
    assertEquals(effect, 4)
  }
}
