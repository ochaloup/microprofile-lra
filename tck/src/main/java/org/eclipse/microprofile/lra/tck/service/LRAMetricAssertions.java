/*
 *******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck.service;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Assertion methods usable with metrics.
 */
@Dependent
public final class LRAMetricAssertions {

    @Inject
    private LRAMetricService lraMetricService;

    // ----------------------------- COMPENSATED -----------------------------------
    /**
     * Asserts that <b>compensated</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompensated(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Compensated, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>compensated</b> was not called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotCompensated(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Compensated, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>compensated</b> was called <code>expectedNumber</code> times for given LRA
     * and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompensatedEquals(String message, int expectedNumber, URI lraId, Class<?> resourceClazz) {
        assertEquals(message, expectedNumber, lraMetricService.getMetric(LRAMetricType.Compensated, lraId, resourceClazz.getName()));
    }

    /**
     * Asserts that <b>compensated</b> was called equal or more than <code>expectedNumber</code> times for given LRA
     * and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompensatedEqualOrMore(String message, int expectedNumberEqualOrMore, URI lraId, Class<?> resourceClazz) {
        assertTrue(message,lraMetricService.getMetric(LRAMetricType.Compensated, lraId, resourceClazz.getName()) >= expectedNumberEqualOrMore);
    }

    /**
     * Asserts that <b>compensated</b> was called <code>expectedNumber</code> times for all LRAs and resources,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompensatedAllEquals(String message, int expectedNumber) {
        assertEquals(message, expectedNumber, lraMetricService.getMetric(LRAMetricType.Compensated));
    }

    // ----------------------------- COMPLETED -------------------------------------
    /**
     * Asserts that <b>completed</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompleted(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Completed, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>completed</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotCompleted(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Completed, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>completed</b> was called <code>expectedNumber</code> times for given LRA
     * and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompletedEquals(String message, int expectedNumber, URI lraId, Class<?> resourceClazz) {
        assertEquals(message, expectedNumber, lraMetricService.getMetric(LRAMetricType.Completed, lraId, resourceClazz.getName()));
    }

    /**
     * Asserts that <b>completed</b> was called equal or more than <code>expectedNumber</code> times for given LRA
     * and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompletedEqualOrMore(String message, int expectedNumberEqualOrMore, URI lraId, Class<?> resourceClazz) {
        assertTrue(message,lraMetricService.getMetric(LRAMetricType.Completed, lraId, resourceClazz.getName()) >= expectedNumberEqualOrMore);
    }

    /**
     * Asserts that <b>completed</b> was called <code>expectedNumber</code> times for all LRAs and resources,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCompletedAllEquals(String message, int expectedNumber) {
        assertEquals(message, expectedNumber, lraMetricService.getMetric(LRAMetricType.Completed));
    }

    // ----------------------------- CLOSED -----------------------------------
    /**
     * Asserts that <b>closed</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertClosed(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Closed, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>closed</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotClosed(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Closed, lraId, resourceClazz.getName());
    }

    // ----------------------------- CANCELLED -----------------------------------
    /**
     * Asserts that <b>cancelled</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertCancelled(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Cancelled, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>cancelled</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotCancelled(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Cancelled, lraId, resourceClazz.getName());
    }

    // ----------------------------- AFTERLRA -----------------------------------
    /**
     * Asserts that <b>afterLRA</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertAferLRA(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.AfterLRA, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>afterLRA</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotAfterLRA(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.AfterLRA, lraId, resourceClazz.getName());
    }

    // ----------------------------- FORGET -----------------------------------
    /**
     * Asserts that <b>forget</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertForget(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Forget, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>forget</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotForget(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Forget, lraId, resourceClazz.getName());
    }

    // ----------------------------- STATUS -----------------------------------
    /**
     * Asserts that <b>status</b> was called for given LRA and resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertStatus(String message, URI lraId, Class<?> resourceClazz) {
        assertYes(message, LRAMetricType.Status, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that <b>status</b> was not called for given LRA and resource resource class translated to fully qualified classname as String,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNotStatus(String message, URI lraId, Class<?> resourceClazz) {
        assertNot(message, LRAMetricType.Status, lraId, resourceClazz.getName());
    }

    // ----------------------------- GENERIC ---------------------------------------
    /**
     * Asserts that {@link LRAMetricType} was called for given LRA and resource name,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertYes(String message, LRAMetricType metricType, URI lraId, String resourceName) {
        assertTrue(message,lraMetricService.getMetric(metricType, lraId, resourceName) >= 1);
    }

    /**
     * Asserts that {@link LRAMetricType} was <b>not</b> called for given LRA and resource name,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertNot(String message, LRAMetricType metricType, URI lraId, String resourceName) {
        assertEquals(message, 0, lraMetricService.getMetric(metricType, lraId, resourceName));
    }

    // ----------------------------- FINISH ---------------------------------------
    /**
     * Asserts that given LRA within the resource name (taken as fully qualified class name) was finished,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertFinished(String message, URI lraId, Class<?> resourceClazz) {
        assertFinished(message, lraId, resourceClazz.getName());
    }

    /**
     * Asserts that given LRA within the resource name was finished,
     * if not the {@link AssertionError} with the given message is thrown.
     */
    public void assertFinished(String message, URI lraId, String resourceName) {
        assertTrue(message, lraMetricService.isLRAFinished(lraId, resourceName));
    }
}
