package io.kotlintest.runner.junit5

import io.kotlintest.Description
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestResult
import io.kotlintest.TestStatus
import io.kotlintest.TestType
import io.kotlintest.runner.jvm.TestEngineListener
import io.kotlintest.runner.jvm.TestSet
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Notifies JUnit Platform of test statuses via a [EngineExecutionListener].
 *
 * JUnit platform supports out of order notification of tests, in that sibling
 * tests can be executing in parallel and updating JUnit out of order. However the gradle test
 * task gets confused if we are executing two or more tests directly under the root at once.
 * Therefore we must queue up notifications until each spec is completed.
 *
 * Gradle test run observations:
 *
 * Top level descriptors must have a source attached or the execution will fail with a parent attached exception.
 * Type.CONTAINER_TEST doesn't seem to work as a top level descriptor, it will hang
 * leaf tests do not need to be completed but they will be marked as uncomplete in intellij.
 * Dynamic test can be called after or before addChild.
 * A Type.TEST can be a child of a Type.TEST.
 * Intermediate Type.CONTAINER seem to be ignored in output.
 * Intermediate containers can have same class source as parent.
 * Type.TEST as top level seems to hang.
 * A TEST doesn't seem to be able to have the same source as a parent, or hang.
 * A TEST seems to hang if it has a ClassSource.
 * MethodSource seems to be ok with a TEST.
 * Container test names seem to be taken from a Source.
 * Nested tests are outputted as siblings.
 * Can complete executions out of order.
 * Child failures will fail parent CONTAINER.
 * Sibling containers can start and finish in parallel.
 *
 * Intellij runner observations:
 *
 * Intermediate Type.CONTAINERs are shown.
 * Intermediate Type.TESTs are shown.
 * A Type.TEST can be a child of a Type.TEST
 * MethodSource seems to be ok with a TEST.
 * Container test names seem to be taken from the name property.
 * Nested tests are outputted as nested.
 * Child failures will not fail containing TEST.
 * child failures will fail a containing CONTAINER.
 * Call addChild _before_ registering test otherwise will appear in the display out of order.
 * Must start tests after their parent or they can go missing.
 * Sibling containers can start and finish in parallel.
 */
class JUnitTestRunnerListener(val listener: EngineExecutionListener, val root: EngineDescriptor) : TestEngineListener {

  private val logger = LoggerFactory.getLogger(this.javaClass)

  data class ResultState(val testCase: TestCase, val result: TestResult)

  // contains a mapping of a Description to a junit TestDescription
  private val descriptors = HashMap<Description, TestDescriptor>()

  // contains every test that was discovered but not necessarily executed
  private val discovered = HashSet<Pair<Description, TestType>>()

  // contains a set of all the tests we have notified as started, to avoid
  // double notification when a test is set to run multiple times
  private val started = HashSet<Description>()

  // contains all the results generated by tests in this spec
  // we store them all and mark the tests as finished only when we exit the spec
  private val results = HashSet<ResultState>()

  override fun engineStarted(classes: List<KClass<out Spec>>) {
    logger.debug("Engine started; classes=[$classes]")
    listener.executionStarted(root)
  }

  override fun engineFinished(t: Throwable?) {
    logger.debug("Engine finished; throwable=[$t]")
    val result = if (t == null) TestExecutionResult.successful() else TestExecutionResult.failed(t)
    listener.executionFinished(root, result)
  }

  override fun prepareSpec(description: Description, klass: KClass<out Spec>) {
    logger.debug("prepareSpec [$description]")
    try {
      val descriptor = createSpecDescriptor(description, klass)
      listener.executionStarted(descriptor)
    } catch (t: Throwable) {
      logger.error("Error in JUnit Platform listener", t)
    }
  }

  override fun prepareTestCase(testCase: TestCase) {
    discovered.add(Pair(testCase.description, testCase.type))
  }

  override fun testRun(set: TestSet, k: Int) {
    // we only "start" a test once, the first time a test is actually run, because
    // at that point we know the test cannot be skipped. This is required because JUnit requires
    // that we do not "start" a test that is later marked as skipped.
    if (!started.contains(set.testCase.description)) {
      started.add(set.testCase.description)
      val descriptor = createTestCaseDescriptor(set.testCase.description, set.testCase.type)
      logger.debug("Notifying junit of start event ${descriptor.uniqueId}")
      listener.executionStarted(descriptor)
    }
  }

  override fun completeTestCase(testCase: TestCase, result: TestResult) {
    logger.debug("completeTestCase ${testCase.description} with result $result")
    // we don't immediately finish a test, we just store the result until we have completed the spec
    // this allows us to handle multiple invocations of the same test case, deferring the notification
    // to junit until all invocations have completed
    results.add(ResultState(testCase, result))
  }

  override fun completeSpec(description: Description, klass: KClass<out Spec>, t: Throwable?) {
    logger.debug("completeSpec [$description]")

    // we should have a result for at least every test that was discovered
    // we wait until the spec is completed before completing all child scopes, because we need
    // to wait until all possible invocations of each scope have completed.
    // for each description we can grab the best result and use that
    discovered
        .filter { description.isAncestorOf(it.first) }
        .sortedBy { it.first.depth() }
        .reversed()
        .forEach {
          val descriptor = descriptors[it.first] ?: getOrCreateDescriptor(it.first, it.second)
          // find an error by priority
          val result = findResultFor(it.first)
          if (result == null) {
            logger.error("Could not find result for $it")
            throw RuntimeException("Every description must have a result but could not find one for $it")
          } else {
            logger.debug("Notifying junit of test case completion ${descriptor.uniqueId}=$result")
            try {
              when (result.status) {
                TestStatus.Success -> listener.executionFinished(descriptor, TestExecutionResult.successful())
                TestStatus.Error, TestStatus.Failure -> listener.executionFinished(descriptor, TestExecutionResult.failed(result.error))
                TestStatus.Ignored -> listener.executionSkipped(descriptor, result.reason ?: "Test Ignored")
              }
            } catch (t: Throwable) {
              logger.error("Error in JUnit Platform listener", t)
            }
          }
        }

    // now we can complete the spec
    val descriptor = descriptors[description]
    if (descriptor == null) {
      logger.error("Spec descriptor cannot be null $description")
      throw RuntimeException("Spec descriptor cannot be null")
    } else {
      val result = if (t == null) TestExecutionResult.successful() else TestExecutionResult.failed(t)
      logger.debug("Notifying junit that spec finished ${descriptor.uniqueId} $result")
      listener.executionFinished(descriptor, result)
    }
  }

  private fun getOrCreateDescriptor(description: Description, type: TestType): TestDescriptor =
      descriptors.getOrPut(description, { createTestCaseDescriptor(description, type) })

  // returns the most important result for a given description
// by searching all the results stored for that description and child descriptions
  private fun findResultFor(description: Description): TestResult? {

    fun findByStatus(status: TestStatus): TestResult? = results
        .filter { it.testCase.description == description || description.isAncestorOf(it.testCase.description) }
        .filter { it.result.status == status }
        .map { it.result }
        .firstOrNull()

    var result = findByStatus(TestStatus.Error)
    if (result == null)
      result = findByStatus(TestStatus.Failure)
    if (result == null)
      result = findByStatus(TestStatus.Success)
    if (result == null)
      result = findByStatus(TestStatus.Ignored)
    return result
  }

  private fun createTestCaseDescriptor(description: Description, type: TestType): TestDescriptor {
    logger.debug("Creating test case descriptor $description/$type")

    val parentDescription = description.parent() ?: throw RuntimeException("All test cases must have a parent")
    val parent = descriptors[parentDescription]!!
    val id = parent.uniqueId.append("test", description.name)

    val descriptor = object : AbstractTestDescriptor(id, description.name) {
      override fun getType(): TestDescriptor.Type {
        // there is a bug in gradle 4.7+ whereby CONTAINER_AND_TEST breaks test reporting, as it is not handled
        // see https://github.com/gradle/gradle/issues/4912
        // so we can't use CONTAINER_AND_TEST for our test scopes, but simply container
        return when (type) {
          TestType.Container -> TestDescriptor.Type.CONTAINER
          TestType.Test -> TestDescriptor.Type.TEST
        }
      }

      override fun mayRegisterTests(): Boolean = type == TestType.Container
    }

    descriptors[description] = descriptor

    parent.addChild(descriptor)
    listener.dynamicTestRegistered(descriptor)

    return descriptor
  }

  private fun createSpecDescriptor(description: Description, klass: KClass<out Spec>): TestDescriptor {

    val id = root.uniqueId.append("spec", description.name)
    val source = ClassSource.from(klass.java)

    val descriptor = object : AbstractTestDescriptor(id, description.name, source) {
      override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER
      override fun mayRegisterTests(): Boolean = true
    }

    descriptors[description] = descriptor

    // we need to synchronize because we don't want to allow multiple specs adding
    // to the root container at the same time
    root.addChild(descriptor)
    listener.dynamicTestRegistered(descriptor)

    return descriptor
  }
}