package monix.bio

import monix.catnap.CancelableF
import monix.catnap.cancelables.BooleanCancelableF
import monix.execution.cancelables.BooleanCancelable
import monix.bio.internal.TaskConnectionRef
import monix.execution.ExecutionModel.SynchronousExecution

object TaskConnectionRefSuite extends BaseTestSuite {
  test("assign and cancel a Cancelable") { implicit s =>
    var effect = 0
    val cr = TaskConnectionRef[Throwable]()
    val b = BooleanCancelable { () =>
      effect += 1
    }

    cr := b
    assert(!b.isCanceled, "!b.isCanceled")

    cr.cancel.runAsyncAndForget; s.tick()
    assert(b.isCanceled, "b.isCanceled")
    assert(effect == 1)

    cr.cancel.runAsyncAndForget; s.tick()
    assert(effect == 1)
  }

  test("assign and cancel a CancelableF") { implicit s =>
    var effect = 0
    val cr = TaskConnectionRef[Throwable]()
    val b = CancelableF.wrap(Task { effect += 1 })

    cr := b
    assertEquals(effect, 0)

    cr.cancel.runAsyncAndForget; s.tick()
    assert(effect == 1)
  }

  test("assign and cancel a CancelToken[Task]") { implicit s =>
    var effect = 0
    val cr = TaskConnectionRef[Throwable]()
    val b = Task { effect += 1 }

    cr := b
    assertEquals(effect, 0)

    cr.cancel.runAsyncAndForget; s.tick()
    assertEquals(effect, 1)

    cr.cancel.runAsyncAndForget; s.tick()
    assertEquals(effect, 1)
  }

  test("cancel a Cancelable on single assignment") { implicit s =>
    val cr = TaskConnectionRef[Throwable]()
    cr.cancel.runAsyncAndForget; s.tick()

    var effect = 0
    val b = BooleanCancelable { () =>
      effect += 1
    }
    cr := b

    assert(b.isCanceled)
    assertEquals(effect, 1)

    cr.cancel.runAsyncAndForget; s.tick()
    assertEquals(effect, 1)

    val b2 = BooleanCancelable { () =>
      effect += 1
    }
    intercept[IllegalStateException] { cr := b2 }
    assertEquals(effect, 2)
  }

  test("cancel a CancelableF on single assignment") { scheduler =>
    implicit val s = scheduler.withExecutionModel(SynchronousExecution)

    val cr = TaskConnectionRef[Throwable]()
    cr.cancel.runAsyncAndForget; s.tick()

    var effect = 0
    val b = BooleanCancelableF(Task { effect += 1 }).runToFuture.value.get.get
    cr := b

    assert(b.isCanceled.runToFuture.value.get.get)
    assertEquals(effect, 1)

    cr.cancel.runAsyncAndForget; s.tick()
    assertEquals(effect, 1)

    val b2 = BooleanCancelableF(Task { effect += 1 }).runToFuture.value.get.get
    intercept[IllegalStateException] { cr := b2 }
    assertEquals(effect, 2)
  }

  test("cancel a Task on single assignment") { implicit s =>
    val cr = TaskConnectionRef[Throwable]()
    cr.cancel.runAsyncAndForget; s.tick()

    var effect = 0
    val b = Task { effect += 1 }

    cr := b; s.tick()
    assertEquals(effect, 1)

    cr.cancel.runAsyncAndForget; s.tick()
    assertEquals(effect, 1)

    intercept[IllegalStateException] {
      cr := b
    }
    assertEquals(effect, 2)
  }
}
