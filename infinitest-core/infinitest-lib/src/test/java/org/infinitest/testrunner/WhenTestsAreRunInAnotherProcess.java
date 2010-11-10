package org.infinitest.testrunner;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.logging.Level.WARNING;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.infinitest.ConsoleOutputListener.OutputType.STDOUT;
import static org.infinitest.CoreDependencySupport.FAILING_TEST;
import static org.infinitest.CoreDependencySupport.PASSING_TEST;
import static org.infinitest.testrunner.TestEvent.TestState.METHOD_FAILURE;
import static org.infinitest.testrunner.TestEvent.TestState.TEST_CASE_STARTING;
import static org.infinitest.testrunner.TestRunnerMother.createRunner;
import static org.infinitest.util.FakeEnvironments.emptyRuntimeEnvironment;
import static org.infinitest.util.FakeEnvironments.fakeEnvironment;
import static org.infinitest.util.InfinitestTestUtils.testIsBeingRunFromInfinitest;
import static org.infinitest.util.InfinitestUtils.addLoggingListener;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;

import org.infinitest.ConcurrencyController;
import org.infinitest.ConsoleListenerAdapter;
import org.infinitest.ConsoleOutputListener;
import org.infinitest.EventSupport;
import org.infinitest.QueueDispatchException;
import org.infinitest.TestResultsAdapter;
import org.infinitest.util.InfinitestTestUtils;
import org.infinitest.util.LoggingAdapter;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class WhenTestsAreRunInAnotherProcess extends AbstractRunnerTest
{
    private EventSupport eventSupport;
    private AbstractTestRunner runner;
    public boolean outputPrinted;
    protected boolean runComplete;
    protected int timesAcquired;

    @Before
    public void inContext()
    {
        eventSupport = new EventSupport(5000);
        runner = createRunner();
        runner.addTestResultsListener(eventSupport);
        runner.addTestQueueListener(eventSupport);
        outputPrinted = false;
    }

    @AfterClass
    public static void resetStatefulCounter()
    {
        StubStatefulTest.counter = 0;
    }

    @Test
    public void shouldHandleLargeAmountsOfConsoleOutput() throws Exception
    {
        final StringBuffer stdOut = new StringBuffer();
        final StringBuffer stdErr = new StringBuffer();
        runner = new MultiProcessRunner();
        runner.setRuntimeEnvironment(fakeEnvironment());
        runner.addTestQueueListener(eventSupport);
        runner.addConsoleOutputListener(new ConsoleListenerAdapter()
        {
            @Override
            public void consoleOutputUpdate(String newText, ConsoleOutputListener.OutputType outputType)
            {
                if (outputType.equals(STDOUT))
                    stdOut.append(newText);
                else
                    stdErr.append(newText);
            }
        });
        // DEBT Race condition in this test. We need to check the status of stdOut and stdErr in the
        // consoleOutputUpdate method and notify a lock that we can hold in the test instead of just
        // assuming that all the output will be ready by the time the tests finish running. But
        // I don't have the brain cells for that right now.

        runner.runTest(TestWithLotsOfConsoleOutput.class.getName());
        waitForCompletion();
        assertThat(stdOut.toString(), containsString("Hello"));
        assertThat(stdOut.toString(), not(containsString("World")));
        assertThat(stdErr.toString(), containsString("World"));
        assertThat(stdErr.toString(), not(containsString("Hello")));
    }

    public static class TestWithLotsOfConsoleOutput
    {
        @Test
        public void shouldFail()
        {
            assumeTrue(testIsBeingRunFromInfinitest());
            for (int i = 0; i < 10; i++)
            {
                System.out.println("Hello");
                System.err.println("World");
            }
        }
    }

    @Test
    public void individualTestRunsAreIndependent() throws Exception
    {
        runTest(StubStatefulTest.class.getName());
        eventSupport.assertTestsStarted(StubStatefulTest.class);
        eventSupport.assertTestPassed(StubStatefulTest.class);

        eventSupport.reset();
        runTest(StubStatefulTest.class.getName());
        eventSupport.assertTestsStarted(StubStatefulTest.class);
        eventSupport.assertTestPassed(StubStatefulTest.class);
    }

    @Test
    public void canControlParallelUpdatesOfCores() throws Exception
    {
        ConcurrencyController controller = createMock(ConcurrencyController.class);
        controller.acquire();
        controller.release();
        replay(controller);

        runner.setConcurrencyController(controller);
        runTest(PASSING_TEST.getName());
        verify(controller);
    }

    // FIXME This is a horrible, horrible test...and guess what? It fails inconsistently!
    @Test
    @Ignore
    public void shouldRestartRunnerProcessWhenNewTestsAreAddedToTheQueue() throws Exception
    {
        final List<String> testsToRun = newArrayList(FAILING_TEST.getName());

        // By listening for the start of the test, we can interrupt the test run
        // in a predictable manner
        runner.addTestResultsListener(new TestResultsAdapter()
        {
            @Override
            public void testCaseStarting(TestEvent event)
            {
                if (!testsToRun.isEmpty())
                {
                    // Push a new test into the queue just as the current one is starting. This
                    // should interrupt the current test runner thread (which just so happens to be
                    // the thread we're on).
                    runner.runTests(testsToRun);

                    // Avoids an infinite loop. Also verifies we made it this far
                    testsToRun.clear();
                    try
                    {
                        // Wait for this thread to be interrupted by the test runner. If we've
                        // already been interrupted before we get here,
                        // this will throw an exception immediately.
                        Thread.sleep(0);
                    }
                    catch (InterruptedException e)
                    {
                        // The ProcessorRunnable treats this like InterruptedException,
                        // except that it's unchecked, so it will clean up properly
                        throw new QueueDispatchException(e);
                    }
                }
            }
        });
        runTests(PASSING_TEST);

        eventSupport.assertTestsStarted(PASSING_TEST, FAILING_TEST);
        eventSupport.assertTestRun(PASSING_TEST);
        eventSupport.assertTestRun(FAILING_TEST);
        assertTrue(testsToRun.isEmpty());
    }

    @Test
    public void shouldFireStartingEventBeforeTestIsActuallyStarted() throws Exception
    {
        runTest("thisIsNotATest");
        eventSupport.assertEventsReceived(TEST_CASE_STARTING, METHOD_FAILURE);
    }

    @Test
    public void subsequentTestRunAreIndependent() throws Exception
    {
        runTest(PASSING_TEST.getName());
        eventSupport.assertTestsStarted(PASSING_TEST);
        eventSupport.assertTestPassed(PASSING_TEST);
        eventSupport.reset();

        runTest(FAILING_TEST.getName());
        eventSupport.assertTestsStarted(FAILING_TEST);
        eventSupport.assertTestFailed(FAILING_TEST);
    }

    @Test
    public void shouldGroupTestRunsTogether() throws Exception
    {
        runTests(StubStatefulTest.class, OtherStubStatefulTest.class);
        eventSupport.assertTestsStarted(StubStatefulTest.class, OtherStubStatefulTest.class);
        eventSupport.assertTestPassed(StubStatefulTest.class);
        eventSupport.assertTestFailed(OtherStubStatefulTest.class);
    }

    @Test
    public void shouldLogWarningIfClasspathContainsMissingFilesOrDirectories()
    {
        LoggingAdapter adapter = new LoggingAdapter();
        addLoggingListener(adapter);
        emptyRuntimeEnvironment().getCompleteClasspath();
        String expectedMessage = "Could not find classpath entry [classpath] at file system root or relative to working "
                        + "directory [.].";
        assertTrue(adapter.toString(), adapter.hasMessage(expectedMessage, WARNING));
    }

    public static class OtherStubStatefulTest
    {
        @Test
        public void incrementStaticVariable()
        {
            assumeTrue(InfinitestTestUtils.testIsBeingRunFromInfinitest());
            StubStatefulTest.counter++;
            assertEquals(1, StubStatefulTest.counter);
        }
    }

    public static class StubStatefulTest
    {
        private static int counter = 0;

        @Test
        public void incrementStaticVariable()
        {
            assumeTrue(testIsBeingRunFromInfinitest());
            counter++;
            assertEquals(1, counter);
        }
    }

    @Override
    protected AbstractTestRunner getRunner()
    {
        return runner;
    }

    @Override
    protected void waitForCompletion() throws InterruptedException
    {
        eventSupport.assertRunComplete();
    }
}
