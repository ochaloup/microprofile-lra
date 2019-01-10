/*
 *******************************************************************************
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.lra.tck;

import static org.eclipse.microprofile.lra.tck.participant.api.LraController.ACCEPT_WORK;
import static org.eclipse.microprofile.lra.tck.participant.api.LraController.TRANSACTIONAL_WORK_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.LraController.LRA_CONTROLLER_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.client.LRAClient;
import org.eclipse.microprofile.lra.client.LRAInfo;
import org.eclipse.microprofile.lra.tck.participant.api.LraController;
import org.eclipse.microprofile.lra.tck.participant.api.NoLRAController;
import org.eclipse.microprofile.lra.tck.participant.api.Util;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;


@RunWith(Arquillian.class)
public class TckTests {
    private final Long LRA_TIMEOUT_MILLIS = 50000L;

    /**
     * <p>
     * Timeout factor which adjusts waiting time and timeouts for the TCK suite.
     * <p>
     * The default value is set to <code>1.0</code> which means the defined timeout
     * is multiplied by <code>1</code>.
     * <p>
     * If you wish the test waits longer then set the value bigger than <code>1.0</code>.
     * If you wish the test waits shorter time than designed
     * or the timeout is elapsed faster then set the value less than <code>1.0</code> 
     */
    @Inject @ConfigProperty(name = "lra.tck.timeout.factor", defaultValue = "1.0")
    private double timeoutFactor;
    
    /**
     * Number of seconds the TCK suite waits for coordinator to be available.
     * The coordinator will is expected to be available at the hostname and port
     * defined by config properties {@link #coordinatorHostName} and {@link #coordinatorPort}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.coordinator.waiting", defaultValue = "5")
    private int coordinatorWaitingTime;
    
    /**
     * Host name where coordinator is expected to be launch and TCK suite tries to connect to it at.
     * The port is specifed by {@link #coordinatorPort}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.coordinator.hostname", defaultValue = "localhost")
    private String coordinatorHostName;
    
    /**
     * Port where coordinator is expected to be launch and TCK suite tries to connect to it at.
     * The hostname is specifed by {@link #coordinatorHostName}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.coordinator.port", defaultValue = "8080")
    private int coordinatorPort;

    /**
     * Host name where TCK suites is deployed at and where the {@link LraController} waits
     * for the testcases to contact it.
     * The port is specified by {@link #lraTckSuiteDeploymentPort}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.suite.hostname", defaultValue = "localhost")
    private String lraTckSuiteDeploymentHostName;
    
    /**
     * The port where TCK suites is deployed at and where the {@link LraController} waits
     * for the testcases to contact it.
     * The host name is specifed by {@link #lraTckSuiteDeploymentHostName}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.suite.port", defaultValue = "8180")
    private int lraTckSuiteDeploymentPort;

    @Rule public TestName testName = new TestName();

    private static final Logger LOGGER = Logger.getLogger(Util.class.getName());

    private static URL tckSuiteBaseUrl;
    private static URL recoveryCoordinatorBaseUrl;

    @Inject
    private LRAClient lraClient;

    private static Client tckSuiteClient;
    private static Client recoveryCoordinatorClient;

    private WebTarget tckSuiteTarget;
    private WebTarget recoveryTarget;

    private static List<LRAInfo> notProperlyClosedLRAs = new ArrayList<>();

    private enum CompletionType {
        complete, compensate, mixed
    }

    @TargetsContainer("managed")
    @Deployment(name = "tcktests", managed = true, testable = true)
    public static WebArchive deploy() {
        String archiveName = TckTests.class.getSimpleName().toLowerCase();
        return ShrinkWrap
            .create(WebArchive.class, archiveName + ".war")
            .addPackages(true, "org.eclipse.microprofile.lra.tck")
            .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
    }
    
    @AfterClass
    public static void afterClass() {
        if(tckSuiteClient != null) tckSuiteClient.close();
        if(recoveryCoordinatorClient != null) recoveryCoordinatorClient.close();
    }

    @Before
    public void before() {
         setUpTestCase();

        try {
            tckSuiteTarget = tckSuiteClient.target(URI.create(new URL(tckSuiteBaseUrl, "/").toExternalForm()));
        } catch (MalformedURLException mfe) {
            throw new IllegalStateException("Cannot create URL for the LRA TCK suite base url " + tckSuiteBaseUrl, mfe);
        }
        recoveryTarget = recoveryCoordinatorClient.target(URI.create(recoveryCoordinatorBaseUrl.toExternalForm()));
    }

    @After
    public void after() {
        List<LRAInfo> activeLRAs = lraClient.getActiveLRAs();

        if (activeLRAs.size() != 0) {
            activeLRAs.forEach(lra -> {
                try {
                    if (!notProperlyClosedLRAs.contains(lra)) {
                        LOGGER.warning(String.format(
                                "%s: WARNING: test did not close %s%n", "testName.getMethodName()", lra.getLraId()));
                        notProperlyClosedLRAs.add(lra);
                        lraClient.closeLRA(new URL(lra.getLraId()));
                    }
                } catch (WebApplicationException | MalformedURLException e) {
                    LOGGER.warning(String.format("After Test: exception %s closing %s%n", e.getMessage(), lra.getLraId()));
                }
            });
        }
    }

    /**
     * Checking if coordinator is running, set ups the client to contact the recovery manager and the TCK suite itself.
     */
    private void setUpTestCase() {
        long startTimeS = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        int coordinatorStartupWaitTime = Util.adjust(coordinatorWaitingTime, timeoutFactor);
        boolean isCoordinatorStarted = true;
        while(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - startTimeS <= coordinatorStartupWaitTime) {
            isCoordinatorStarted = !Util.isPortAvailable(coordinatorHostName, coordinatorPort);
            if(isCoordinatorStarted)
                break; // coordinator hostname:port is occupied by a process, expecting coordinator is started
        }
        Assert.assertTrue(String.format("Unfortunatelly there is no started process at %s:%d where coordinator "
                + "is expected to be present. The test '%s' waited for the coordinator for %s seconds",
                coordinatorHostName, coordinatorPort, testName.getMethodName(), coordinatorStartupWaitTime), isCoordinatorStarted);

        if(tckSuiteBaseUrl != null)
            return; // we've already set up the base urls and REST clients for the tests

        try {
            tckSuiteBaseUrl = new URL(String.format("http://%s:%d", lraTckSuiteDeploymentHostName, lraTckSuiteDeploymentPort));
            // TODO: what to do with this? recovery url is needed?
            recoveryCoordinatorBaseUrl = new URL(String.format("http://%s:%d/%s", coordinatorHostName, coordinatorPort, "lra-recovery-coordinator"));

            tckSuiteClient = ClientBuilder.newClient();
            recoveryCoordinatorClient = ClientBuilder.newClient();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Cannot properly setup the TCK tests (coordinator endpoint, testsuite endpoints...)", e);
        }
    }

    // TODO: what's difference to closeLRA?
    @Test
    public void startLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);
    }

    /**
     * <p>
     * Starting LRA and canceling it.
     * <p>
     * It's expected the LRA won't be listed in active LRAs when canceled.
     */
    @Test
    public void cancelLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null,lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.cancelLRA(lra);

        List<LRAInfo> allClientLRAs = lraClient.getAllLRAs();
        boolean isLraIdInList = containsLraId(allClientLRAs, lra);

        assertFalse("LRA '" + lra + "' should not be active anymore but was found in the list of all lras "
            + allClientLRAs, isLraIdInList);
    }

    /**
     * <p>
     * Starting LRA and closing it.
     * <p>
     * It's expected the LRA won't be listed in active LRAs when closed.
     */
    @Test
    public void closeLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        List<LRAInfo> allClientLRAs = lraClient.getAllLRAs();
        boolean isLraIdInList = containsLraId(allClientLRAs, lra);

        assertFalse("LRA '" + lra + "' should not be active anymore but was found in the list of all lras "
                + allClientLRAs, isLraIdInList);
    }

    /**
     * Started LRA should be listed amongst active LRAs, see {@link LRAClient#getActiveLRAs()}.
     */
    @Test
    public void getActiveLRAs() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        List<LRAInfo> allActiveLRAs = lraClient.getActiveLRAs();
        boolean isLraIdInList = containsLraId(allActiveLRAs, lra);

        assertTrue("LRA '" + lra + "' should be listed in the list of the active LRAs, as it was not closed yet, but it isn't",
                isLraIdInList);

        lraClient.closeLRA(lra);
    }

    /**
     * Started LRA should be listed amongst active LRAs, see {@link LRAClient#getAllLRAs()}.
     */
    @Test
    public void getAllLRAs() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        List<LRAInfo> allClientLRAs = lraClient.getAllLRAs();
        boolean isLraIdInList = containsLraId(allClientLRAs, lra);

        assertTrue("LRA '" + lra + "' should be listed in the list of the all LRAs, as it was not closed yet, but it isn't",
                isLraIdInList);

        lraClient.closeLRA(lra);
    }

    @Test
    public void getRecoveringLRAs() throws WebApplicationException {
        // TODO
    }

    /**
     * Started LRA should in state 'active', see {@link LRAClient#isActiveLRA(URL)}.
     */
    @Test
    public void isActiveLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        assertTrue("LRA '" + lra + "' is not denoted as active even it was started", lraClient.isActiveLRA(lra));

        lraClient.closeLRA(lra);
    }

    /**
     * Started LRA should in state 'active', see {@link LRAClient#isCompensatedLRA(URL)}.
     * NOTE: the coordinator cleans up when canceled
     */
    @Test
    public void isCompensatedLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.cancelLRA(lra);

        assertTrue("LRA '" + lra + "' is not denoted as compensated even it was canceled", lraClient.isCompensatedLRA(lra));
    }

    /**
     * Closed LRA should in state 'completed', see {@link LRAClient#isCompletedLRA(URL)}.
     * NOTE: the coordinator cleans up when completed
     */
    @Test
    public void isCompletedLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        assertTrue("LRA '" + lra + "' is not denoted as compensated even it was canceled", lraClient.isCompletedLRA(lra));
    }

    /**
     * HTTP request to {@link LraController#activityWithLRA}
     * which is a method annotated with {@link org.eclipse.microprofile.lra.annotation.LRA.Type#REQUIRED}.  
     */
    @Test
    public void joinLRAViaBody() throws WebApplicationException {

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath.request().put(Entity.text(""));

        String lraId = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);

        // validate that the LRA coordinator no longer knows about lraId
        List<LRAInfo> activeLras = lraClient.getActiveLRAs();
        boolean isLraIdInList = containsLraId(activeLras, lraId);

        // the resource /activities/work is annotated with Type.REQUIRED so the container should have ended it
        assertFalse("LRA work was processed and the annotated method finished but the LRA id '" + lraId + "'"
            + "is still part of known active LRAs " + activeLras, isLraIdInList);
    }

    @Test
    public void nestedActivity() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget
                .path(LRA_CONTROLLER_PATH).path("nestedActivity");

        Response response = resourcePath
                .request()
                .header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        Object parentId = response.getHeaders().getFirst(LRAClient.LRA_HTTP_HEADER);

        assertNotNull("Expecting to get parent LRA id as response from " + resourcePath
                + ", the whole response to the request was " + response, parentId);
        assertEquals("The nested activity should return the parent LRA id. The call to "
                + resourcePath + " returned response " + response, parentId, lra.toExternalForm());

        String nestedLraId = response.readEntity(String.class);

        // close the LRA
        lraClient.closeLRA(lra);

        // validate that the nested LRA was closed
        List<LRAInfo> activeLras = lraClient.getActiveLRAs();
        boolean isLraIdInList = containsLraId(activeLras, nestedLraId);

        // the resource /activities/work is annotated with Type.REQUIRED so the container should have ended it
        assertFalse("Nested LRA id '" + lra + "' should be listed in the list of the active LRAs (from call to "
                + resourcePath + ")", isLraIdInList);        
    }

    @Test
    public void completeMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.complete, 1);
    }

    @Test
    public void compensateMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.compensate, 1);
    }

    @Test
    public void mixedMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.mixed, 2);
    }

    @Test
    public void joinLRAViaHeader() throws WebApplicationException {
        int beforeCompletedCount = getCompletedCount();

        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        // validate that the LRA coordinator still knows about lraId
        List<LRAInfo> allActiveLRAs = lraClient.getActiveLRAs();
        boolean isLraIdInList = containsLraId(allActiveLRAs, lra);
        assertTrue("LRA '" + lra + "' should be active as not closed yet. But it was not found in the list of active LRAs "
                + allActiveLRAs, isLraIdInList);

        // close the LRA
        lraClient.closeLRA(lra);

        // check that LRA coordinator no longer knows about lraId
        allActiveLRAs = lraClient.getActiveLRAs();
        assertFalse("LRA '" + lra + "' should not be active anymore as it was closed yet. But it was not found amongst active LRAs "
                + allActiveLRAs, containsLraId(allActiveLRAs, lra));

        // check that participant was told to complete
        int completedCount = getCompletedCount();
        assertEquals("Wrong completion count for call " + resourcePath + ". Expecting the method LRA was completed "
                + "after joining the existing LRA " + lra, beforeCompletedCount + 1, completedCount);
    }

    @Test
    public void join() throws WebApplicationException {
        List<LRAInfo> lras = lraClient.getActiveLRAs();
        int count = lras.size();
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(Response.Status.OK, response, resourcePath);
        lraClient.closeLRA(lra);

        lras = lraClient.getActiveLRAs();
        assertEquals("Number of LRA instances before the test does not match current number of active LRAs. The joint LRA should be closed already. "
                + "The test call went to LRA controller at " + resourcePath, count, lras.size());
    }

    @Test
    public void leaveLRA() throws WebApplicationException {
        int beforeCompletedCount = getCompletedCount();
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        // perform a second request to the same method in the same LRA context to validate that multiple participants are not registered
        resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        // call a method annotated with @Leave (should remove the participant from the LRA)
        resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("leave");
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        lraClient.closeLRA(lra);

        // check that participant was not told to complete
        int completedCount = getCompletedCount();

        assertEquals("Wrong completion count when participant left the LRA. "
                + "Expecting the completed count hasn't change between start and end of the test. "
                + "The test call went to LRA controller at " + resourcePath, beforeCompletedCount, completedCount);
    }

    @Test
    public void leaveLRAViaAPI() throws WebApplicationException {
        int beforeCompletedCount = getCompletedCount();
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);

        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        // perform a second request to the same method in the same LRA context to validate that multiple participants are not registered
        resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        String recoveryUrl = response.getHeaderString(LRAClient.LRA_HTTP_RECOVERY_HEADER);        
        
        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        // call a method annotated with @Leave (should remove the participant from the LRA)
        try {
            resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("leave");
            response = resourcePath.path(URLEncoder.encode(lra.toString(), "UTF-8"))
                    .request()
                    .header(LRAClient.LRA_HTTP_HEADER, lra)
                    .header(LRAClient.LRA_HTTP_RECOVERY_HEADER, recoveryUrl)
                    .put(Entity.text(""));
        } catch (UnsupportedEncodingException e) {
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                            Entity.text(String.format("%s: %s", resourcePath.getUri().toString(), e.getMessage()))).build());
        }
        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        lraClient.closeLRA(lra);

        // check that participant was not told to complete
        int completedCount = getCompletedCount();

        assertEquals("Wrong completion count when participant left the LRA by calling @Leave method. "
                + "Expecting the completed count hasn't change between start and end of the test. "
                + "The test call went to LRA controller at " + resourcePath, beforeCompletedCount, completedCount);
    }

    @Test
    public void dependentLRA() throws WebApplicationException, MalformedURLException {
        // call a method annotated with NOT_SUPPORTED but one which programatically starts an LRA and returns it via a header
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("startViaApi");
        Response response = resourcePath.request().put(Entity.text(""));
        // check that the method started an LRA
        Object lraHeader = response.getHeaders().getFirst(LRAClient.LRA_HTTP_HEADER);

        String lraId = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);

        // the value returned via the header and body should be equal

        assertNotNull("JAX-RS response to PUT request should have returned the header " + LRAClient.LRA_HTTP_HEADER
                + ". The test call went to " + resourcePath, lraHeader);
        assertNotNull("JAX-RS response to PUT request should have returned content of LRA id. The test call went to "
                + resourcePath, lraId);
        assertEquals("The dependent LRA has to belong to the same LRA id. The test call went to " + resourcePath,
                lraId, lraHeader.toString());

        lraClient.closeLRA(new URL(lraHeader.toString()));
    }

    @Test
    public void cancelOn() {
        cancelCheck("cancelOn");
    }

    @Test
    public void cancelOnFamily() {
        cancelCheck("cancelOnFamily");
    }

    @Test
    public void timeLimit() {
        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();
        
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("timeLimit");
        Response response = resourcePath
                .request()
                .get();

        response.close();

        // Note that the timeout firing will cause the coordinator to compensate
        // the LRA so it may no longer exist
        // (depends upon how long the coordinator keeps a record of finished LRAs

        // check that participant was invoked
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        /*
         * The call to activities/timeLimit should have started an LRA which should have timed out
         * (because the invoked resource method sleeps for longer than the timeLimit annotation
         * attribute specifies). Therefore the participant should have compensated:
         */
        assertEquals("The LRA should have timed out but complete was called instead of compensate. "
                + "Expecting the number of complete call before test matches the ones after LRA timed out. "
                + "The test call went to " + resourcePath, beforeCompletedCount, completedCount);
        assertEquals("The LRA should have timed out and compensate should be called. "
                + "Expecting the number of compensate call before test is one less lower than the ones after LRA timed out. "
                + "The test call went to " + resourcePath, beforeCompensatedCount + 1, compensatedCount);
    }

    /*
     * Participants can pass data during enlistment and this data will be returned during
     * the complete/compensate callbacks
     */
    // TODO: is this rest to be run?
    private void testUserData() {
        List<LRAInfo> beforeActiveLRAs = lraClient.getActiveLRAs();
        String testData = "test participant data";
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("testUserData");

        Response response = resourcePath
                .request().put(Entity.text(testData));

        String activityId = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);

        List<LRAInfo> activeLRAs = lraClient.getActiveLRAs();

        assertEquals("produced the wrong LRA count on call of method " + resourcePath,
                beforeActiveLRAs.size(), activeLRAs.size());

        response = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("getActivity")
                .queryParam("activityId", activityId)
                .request()
                .get();

        String activity = response.readEntity(String.class);

        // validate that the service received the correct data during the complete call
        assertTrue("service should get received userData field during complete call", activity.contains("userData='" + testData));
        assertTrue("service should get received endData field during complete call", activity.contains("endData='" + testData));
    }

    @Test
    public void acceptTest() throws WebApplicationException {
        joinAndEnd(true, true, LRA_CONTROLLER_PATH, ACCEPT_WORK);
    }

    // TODO the spec does not specifiy recovery semantics
    private void joinAndEnd(boolean waitForRecovery, boolean close, String path, String path2) throws WebApplicationException {
        int beforeActiveLRACount = lraClient.getActiveLRAs().size();
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(path).path(path2);

        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        checkStatusAndClose(Response.Status.OK, response, resourcePath);

        if (close) {
            lraClient.closeLRA(lra);
        } else {
            lraClient.cancelLRA(lra);
        }

        if (waitForRecovery) {
            // trigger a recovery scan which trigger a replay attempt on any participants
            // that have responded to complete/compensate requests with Response.Status.ACCEPTED
            resourcePath = recoveryTarget.path("recovery");
            Response response2 = resourcePath
                    .request().get();

            checkStatusAndClose(Response.Status.OK, response2, resourcePath);
        }

        int activeLRACount = lraClient.getActiveLRAs().size();

        assertEquals("Expecting all LRAs were recovered and the number of active LRAs before test matches the number after. "
                + "The test call went to " + resourcePath,
                beforeActiveLRACount, activeLRACount);
    }

    @Test
    public void noLRATest() throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget
                .path(NoLRAController.NO_LRA_CONTROLLER_PATH)
                .path(NoLRAController.NON_TRANSACTIONAL_WORK_PATH);

        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();
        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        String lraId = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);

        assertEquals("While calling non-LRA method the controller returns not expected LRA id",
                lraId, lra.toExternalForm());

        lraClient.cancelLRA(lra);

        // check that second service (the LRA aware one), namely
        // {@link org.eclipse.microprofile.lra.tck.participant.api.ActivityController#activityWithMandatoryLRA(String, String)}
        // was told to compensate
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        assertEquals("Complete should not be called on the LRA aware service. "
                + "The number of completed count for before and after test does not match. "
                + "The test call went to " + resourcePath, beforeCompletedCount, completedCount);
        assertEquals("Compensate service should be called on LRA aware service. The number of compensated count after test is bigger for one. "
                + "The test call went to " + resourcePath, beforeCompensatedCount + 1, compensatedCount);
    }

    // TODO: is this test to be run?
    private void renewTimeLimit() {
        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH)
                .path("renewTimeLimit");

        Response response = resourcePath
                .request()
                .get();

        response.close();

        // check that participant was invoked
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        /*
         * The call to activities/timeLimit should have started an LRA whch should not have timed out
         * (because the called resource method renews the timeLimit before sleeping for longer than
         * the timeLimit annotation attribute specifies).
         * Therefore the participant should not have compensated:
         */
        assertEquals("Compensate was called instead of complete. The test call went to " + resourcePath, beforeCompletedCount + 1, completedCount);
        assertEquals("Compensate should not have been called. The test call went to " + resourcePath, beforeCompensatedCount, compensatedCount);
    }

    private void checkStatusAndClose(Response.Status expectedStatus, Response response, WebTarget resourcePath) {
        try {
            assertEquals("Response status on call to ' " + resourcePath + "' does not match. The response was '" + response + "'.",
                    expectedStatus.getStatusCode(), response.getStatus());
        } finally {
            response.close();
        }
    }

    private String checkStatusReadAndClose(Response.Status expectedStatus, Response response, WebTarget resourcePath) {
        try {
            assertEquals("Response status on call to ' " + resourcePath + "' does not match. The response was '" + response + "'.",
                    expectedStatus.getStatusCode(), response.getStatus());
            return response.readEntity(String.class);
        } finally {
            response.close();
        }
    }

    private int getCompletedCount() {
        return getActivityCount("completedactivitycount");
    }

    private int getCompensatedCount() {
        return getActivityCount("compensatedactivitycount");
    }

    private int getActivityCount(String activityCountTargetPath) {
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH)
                .path(activityCountTargetPath);

        Response response = resourcePath.request().get();
        String count = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);
        return Integer.parseInt(count);
    }

    private void multiLevelNestedActivity(CompletionType how, int nestedCnt) throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("multiLevelNestedActivity");

        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();

        if (how == CompletionType.mixed && nestedCnt <= 1) {
            how = CompletionType.complete;
        }

        URL lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        String lraId = lra.toString();

        Response response = resourcePath
                .queryParam("nestedCnt", nestedCnt)
                .request()
                .header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        String lraStr = checkStatusReadAndClose(Response.Status.OK, response, resourcePath);
        assert lraStr != null;
        String[] lraArray = lraStr.split(",");
        final List<LRAInfo> lras = lraClient.getActiveLRAs();
        URL[] urls = new URL[lraArray.length];

        IntStream.range(0, urls.length).forEach(i -> {
            try {
                urls[i] = new URL(lraArray[i]);
            } catch (MalformedURLException e) {
                fail(String.format("%s (multiLevelNestedActivity): returned an invalid URL: %s",
                        resourcePath.getUri().toString(), e.getMessage()));
            }
        });
        // check that the multiLevelNestedActivity method returned the mandatory LRA followed by any nested LRAs
        assertEquals("multiLevelNestedActivity: step 1 (the test call went to " + resourcePath + ")", nestedCnt + 1, lraArray.length);
        // first element should be the mandatory LRA
        assertEquals("multiLevelNestedActivity: step 2 (the test call went to " + resourcePath + ")", lraId, lraArray[0]);

        // check that the coordinator knows about the two nested LRAs started by the multiLevelNestedActivity method
        // NB even though they should have completed they are held in memory pending the enclosing LRA finishing
        IntStream.rangeClosed(1, nestedCnt).forEach(i -> assertTrue("missing nested LRA: step 2b (path called " + resourcePath + ")",
                containsLraId(lras, lraArray[i])));

        // and the mandatory lra seen by the multiLevelNestedActivity method
        assertTrue("lra should have been found (path called " + resourcePath + ")", containsLraId(lras, lraArray[0]));

        int inMiddleCompletedCount = getCompletedCount();
        int inMiddleCompensatedCount = getCompensatedCount();

        // check that all nested activities were told to complete
        assertEquals("multiLevelNestedActivity: step 3 (called test path " + resourcePath + ")",
                beforeCompletedCount + nestedCnt, inMiddleCompletedCount);
        // and that neither were told to compensate
        assertEquals("multiLevelNestedActivity: step 4 (called test path " + resourcePath + ")",
                beforeCompensatedCount, inMiddleCompensatedCount);

        // close the LRA
        if (how == CompletionType.compensate) {
            lraClient.cancelLRA(lra);
        } else if (how == CompletionType.complete) {
            lraClient.closeLRA(lra);
        } else {
            /*
             * The test is calling for a mixed outcome (a top level LRA L! and nestedCnt nested LRAs (L2, L3, ...)::
             * L1 the mandatory call (PUT "activities/multiLevelNestedActivity") registers participant C1
             *   the resource makes nestedCnt calls to "activities/nestedActivity" each of which create nested LRAs
             * L2, L3, ... each of which enlists a participant (C2, C3, ...) which are completed when the call returns
             * L2 is canceled  which causes C2 to compensate
             * L1 is closed which triggers the completion of C1
             *
             * To summarise:
             *
             * - C1 is completed
             * - C2 is completed and then compensated
             * - C3, ... are completed
             */
            lraClient.cancelLRA(urls[1]); // compensate the first nested LRA
            lraClient.closeLRA(lra); // should not complete any nested LRAs (since they have already completed via the interceptor)
        }

        // validate that the top level and nested LRAs are gone
        final List<LRAInfo> lras2 = lraClient.getActiveLRAs();

        IntStream.rangeClosed(0, nestedCnt).forEach(i -> assertFalse(
                "multiLevelNestedActivity: top level or nested activity still active (called path " + resourcePath + ")",
                containsLraId(lras2, lraArray[i])));

        int afterCompletedCount = getCompletedCount();
        int afterCompensatedCount = getCompensatedCount();

        if (how == CompletionType.complete) {
            // make sure that all nested activities were not told to complete or cancel a second time
            assertEquals("multiLevelNestedActivity: step 5 (called test path " + resourcePath + ")",
                    inMiddleCompletedCount + nestedCnt, afterCompletedCount);
            // and that neither were still not told to compensate
            assertEquals("multiLevelNestedActivity: step 6 (called test path " + resourcePath + ")",
                    beforeCompensatedCount, afterCompensatedCount);

        } else if (how == CompletionType.compensate) {
            /*
             * the test starts LRA1 calls a @Mandatory method multiLevelNestedActivity which enlists in LRA1
             * multiLevelNestedActivity then calls an @Nested method which starts L2 and enlists another participant
             *   when the method returns the nested participant is completed (ie completed count is incremented)
             * Canceling L1 should then compensate the L1 enlistement (ie compensate count is incrememted)
             * which will then tell L2 to compenstate (ie the compensate count is incrememted again)
             */
            // each nested participant should have completed (the +nestedCnt)
            assertEquals("multiLevelNestedActivity: step 7 (called test path " + resourcePath + ")",
                    beforeCompletedCount + nestedCnt, afterCompletedCount);
            // each nested participant should have compensated. The top level enlistement should have compensated (the +1)
            assertEquals("multiLevelNestedActivity: step 8 (called test path " + resourcePath + ")",
                    inMiddleCompensatedCount + 1 + nestedCnt, afterCompensatedCount);
        } else {
            /*
             * The test is calling for a mixed uutcome:
             * - the top level LRA was closed
             * - one of the nested LRAs was compensated the rest should have been completed
             */
            // there should be just 1 compensation (the first nested LRA)
            assertEquals("multiLevelNestedActivity: step 9 (called test path " + resourcePath + ")",
                    1, afterCompensatedCount - beforeCompensatedCount);
            /*
             * Expect nestedCnt + 1 completions, 1 for the top level and one for each nested LRA
             * (NB the first nested LRA is completed and compensated)
             * Note that the top level complete should not call complete again on the nested LRA
             */
            assertEquals("multiLevelNestedActivity: step 10 (called test path " + resourcePath + ")",
                    nestedCnt + 1, afterCompletedCount - beforeCompletedCount);
        }
    }

    private void cancelCheck(String path) {
        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();

        URL lra = lraClient.startLRA(null, "SpecTest#" + path, lraTimeout(), ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(path);

        Response response = resourcePath
                .request()
                .header(LRAClient.LRA_HTTP_HEADER, lra)
                .get();

        checkStatusReadAndClose(Response.Status.BAD_REQUEST, response, resourcePath);

        // check that participant was invoked
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        // check that complete was not called and that compensate was
        assertEquals("complete was called instead of compensate (called to " + resourcePath + ")",
                beforeCompletedCount, completedCount);
        assertEquals("compensate should have been called (called to " + resourcePath + ")", beforeCompensatedCount + 1, compensatedCount);

        try {
            assertTrue("LRA '" + lra + "' should have been cancelled (called to " + resourcePath + ")", !lraClient.isActiveLRA(lra));
        } catch (NotFoundException ignore) {
            // means the LRA has gone
        }
    }

    private boolean containsLraId(List<LRAInfo> lras, URL lraIdURL) {
        String lraId = lraIdURL.toExternalForm();
        return containsLraId(lras, lraId);
    }

    private boolean containsLraId(List<LRAInfo> lras, String lraId) {
        return lras.stream().anyMatch(lrainfo -> lrainfo.getLraId().equals(lraId));
    }

    /**
     * The started LRA will be named based on the class name and the running test name.
     */
    private String lraClientId() {
        return this.getClass().getSimpleName() + "#" + testName.getMethodName();
    }

    /**
     * Adjusting the default timeout by the specified timeout factor
     * which can be defined by user.
     */
    private long lraTimeout() {
        return Util.adjust(LRA_TIMEOUT_MILLIS, timeoutFactor);
    }
}
