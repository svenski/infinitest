package org.infinitest;

import static com.google.common.collect.Iterables.*;
import static java.util.Arrays.*;
import static org.hamcrest.Matchers.*;
import static org.infinitest.testrunner.TestEvent.*;
import static org.junit.Assert.*;

import java.util.Collection;

import org.infinitest.testrunner.TestEvent;
import org.junit.Before;
import org.junit.Test;

public class WhenTrackingFailuresForATestCase
{
    private TestCaseFailures failures;
    private TestEvent newEvent;

    @Before
    public void inContext()
    {
        TestEvent method1Event = eventFor("method1");
        failures = new TestCaseFailures(asList(method1Event, eventFor("method2")));
        failures.addNewFailure(method1Event);
    }

    @Test
    public void shouldIgnoreStrictlyEqualUpdates()
    {
        assertTrue(failures.updatedFailures().isEmpty());
    }

    @Test
    public void shouldOnlyUpdateFailuresIfNotStrictlyEqual()
    {
        newEvent = failedWithMessage("method2", "anotherMessage");
        failures.addNewFailure(newEvent);
        Collection<TestEvent> updatedFailures = failures.updatedFailures();
        assertSame(newEvent, getOnlyElement(updatedFailures));
    }

    @Test
    public void shouldDetermineWhichFailuresAreNew()
    {
        failures.addNewFailure(eventFor("method3"));
        Collection<TestEvent> newFailures = failures.newFailures();
        assertThat(newFailures, hasItem(eventFor("method3")));
        assertEquals(1, newFailures.size());
    }

    @Test
    public void shouldDetermineWhichFailuresHaveBeenRemoved()
    {
        Collection<TestEvent> removedFailures = failures.removedFailures();
        assertEquals(eventFor("method2"), getOnlyElement(removedFailures));
    }

    private TestEvent eventFor(String methodName)
    {
        return failedWithMessage(methodName, "message");
    }

    private TestEvent failedWithMessage(String methodName, String message)
    {
        return methodFailed(message, "testName", methodName, new AssertionError(methodName + " failed"));
    }
}
