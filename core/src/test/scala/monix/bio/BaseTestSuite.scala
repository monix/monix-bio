package monix.bio

import minitest.TestSuite
import minitest.laws.Checkers
import monix.execution.schedulers.TestScheduler

abstract class BaseTestSuite extends TestSuite[TestScheduler] with Checkers with ArbitraryInstances {

  def setup(): TestScheduler = TestScheduler()
  def tearDown(env: TestScheduler): Unit = {
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")
  }
}
