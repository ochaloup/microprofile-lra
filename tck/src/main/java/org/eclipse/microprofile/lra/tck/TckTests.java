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

import static org.eclipse.microprofile.lra.tck.participant.api.ActivityController.ACCEPT_WORK;
import static org.eclipse.microprofile.lra.tck.participant.api.ActivityController.ACTIVITIES_PATH;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import org.eclipse.microprofile.lra.client.GenericLRAException;
import org.eclipse.microprofile.lra.client.LRAClient;
import org.eclipse.microprofile.lra.client.LRAInfo;
import org.eclipse.microprofile.lra.tck.participant.api.ActivityController;
import org.eclipse.microprofile.lra.tck.participant.api.StandardController;
import org.eclipse.microprofile.lra.tck.participant.api.Util;
import org.jboss.arquillian.container.test.api.Deployment;
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
    @Inject @ConfigProperty(name = "lra.tck.coordinator.port", defaultValue = "8180")
    private int coordinatorPort;

    /**
     * Host name where TCK suites is deployed at and where the {@link ActivityController} waits
     * for the testcases to contact it.
     * The port is specified by {@link #lraTckSuiteDeploymentPort}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.suite.hostname", defaultValue = "localhost")
    private String lraTckSuiteDeploymentHostName;
    
    /**
     * The port where TCK suites is deployed at and where the {@link ActivityController} waits
     * for the testcases to contact it.
     * The host name is specifed by {@link #lraTckSuiteDeploymentHostName}. 
     */
    @Inject @ConfigProperty(name = "lra.tck.suite.port", defaultValue = "8080")
    private int lraTckSuiteDeploymentPort;

    @Rule public TestName testName = new TestName();

    private static URL tckSuiteBaseUrl;
    private static URL recoveryCoordinatorBaseUrl;

    private static final String RECOVERY_PATH_TEXT = "recovery";
    private static final String PASSED_TEXT = "passed";
    private static final String WORK_TEXT = "work";

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

    @Deployment
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
                        System.out.printf("%s: WARNING: test did not close %s%n", "testName.getMethodName()", lra.getLraId());
                        notProperlyClosedLRAs.add(lra);
                        lraClient.closeLRA(new URL(lra.getLraId()));
                    }
                } catch (WebApplicationException | MalformedURLException e) {
                    System.out.printf("After Test: exception %s closing %s%n", e.getMessage(), lra.getLraId());
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
                + "is expected to be present. The test %s waited for the coordinator for %s seconds",
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

    // @Test
    private String startLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#startLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        return lra.toExternalForm();
    }

    // @Test
    private String cancelLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null,"SpecTest#cancelLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        lraClient.cancelLRA(lra);

        List<LRAInfo> lras = lraClient.getAllLRAs();

        assertNull(getLra(lras, lra.toExternalForm()), "cancelLRA via client: lra still active", null);

        return lra.toExternalForm();
    }

    // @Test
    private String closeLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#closelLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        List<LRAInfo> lras = lraClient.getAllLRAs();

        assertNull(getLra(lras, lra.toExternalForm()), "closeLRA via client: lra still active", null);

        return lra.toExternalForm();
    }

    // @Test
    private String getActiveLRAs() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#getActiveLRAs", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        List<LRAInfo> lras = lraClient.getActiveLRAs();

        assertNotNull(getLra(lras, lra.toExternalForm()), "getActiveLRAs: getLra returned null", null);

        lraClient.closeLRA(lra);

        return lra.toExternalForm();
    }

    // @Test
    private String getAllLRAs() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#getAllLRAs", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        List<LRAInfo> lras = lraClient.getAllLRAs();

        assertNotNull(getLra(lras, lra.toExternalForm()), "getAllLRAs: getLra returned null", null);

        lraClient.closeLRA(lra);

        return PASSED_TEXT;
    }

    //    // @Test
    private void getRecoveringLRAs() throws WebApplicationException {
        // TODO
    }

    // @Test
    private String isActiveLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#isActiveLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        assertTrue(lraClient.isActiveLRA(lra), null, null, lra);

        lraClient.closeLRA(lra);

        return lra.toExternalForm();
    }

    // the coordinator cleans up when canceled
    // @Test
    private String isCompensatedLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#isCompensatedLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        lraClient.cancelLRA(lra);

        assertTrue(lraClient.isCompensatedLRA(lra), null, null, lra);

        return lra.toExternalForm();
    }

    // the coordinator cleans up when completed
    // @Test
    private String isCompletedLRA() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#isCompletedLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        assertTrue(lraClient.isCompletedLRA(lra), null, null, lra);

        return lra.toExternalForm();
    }

    // @Test
    private String joinLRAViaBody() throws WebApplicationException {

        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        Response response = resourcePath.request().put(Entity.text(""));

        String lra = checkStatusAndClose(response, Response.Status.OK.getStatusCode(), true, resourcePath);

        // validate that the LRA coordinator no longer knows about lraId
        List<LRAInfo> lras = lraClient.getActiveLRAs();

        // the resource /activities/work is annotated with Type.REQUIRED so the container should have ended it
        assertNull(getLra(lras, lra), "joinLRAViaBody: lra is still active", resourcePath);

        return PASSED_TEXT;
    }

    // @Test
    private String nestedActivity() throws WebApplicationException {
        URL lra = lraClient.startLRA(null, "SpecTest#nestedActivity", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget
                .path(ACTIVITIES_PATH).path("nestedActivity");

        Response response = resourcePath
                .request()
                .header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        Object parentId = response.getHeaders().getFirst(LRAClient.LRA_HTTP_HEADER);

        assertNotNull(parentId, "nestedActivity: null parent LRA", resourcePath);
        assertEquals(lra.toExternalForm(), parentId, "nestedActivity should have returned the parent LRA", resourcePath);

        String nestedLraId = checkStatusAndClose(response, Response.Status.OK.getStatusCode(), true, resourcePath);

        // close the LRA
        lraClient.closeLRA(lra);

        // validate that the nested LRA was closed
        List<LRAInfo> lras = lraClient.getActiveLRAs();

        // the resource /activities/work is annotated with Type.REQUIRED so the container should have ended it
        assertNull(getLra(lras, nestedLraId), "nestedActivity: nested LRA should not be active", resourcePath);

        return lra.toExternalForm();
    }

    // @Test
    private String completeMultiLevelNestedActivity() throws WebApplicationException {
        return multiLevelNestedActivity(CompletionType.complete, 1);
    }

    // @Test
    private String compensateMultiLevelNestedActivity() throws WebApplicationException {
        return multiLevelNestedActivity(CompletionType.compensate, 1);
    }

    // @Test
    private String mixedMultiLevelNestedActivity() throws WebApplicationException {
        return multiLevelNestedActivity(CompletionType.mixed, 2);
    }

    // @Test
    private String joinLRAViaHeader() throws WebApplicationException {
        int cnt1 = completedCount(true);

        URL lra = lraClient.startLRA(null, "SpecTest#joinLRAViaBody", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        // validate that the LRA coordinator still knows about lraId
        List<LRAInfo> lras = lraClient.getActiveLRAs();
        assertNotNull(getLra(lras, lra.toExternalForm()), "joinLRAViaHeader: missing lra", resourcePath);

        // close the LRA
        lraClient.closeLRA(lra);

        // check that LRA coordinator no longer knows about lraId
        lras = lraClient.getActiveLRAs();
        assertNull(getLra(lras, lra.toExternalForm()), "joinLRAViaHeader: LRA should not be active", resourcePath);

        // check that participant was told to complete
        int cnt2 = completedCount(true);
        assertEquals(cnt1 + 1, cnt2, "joinLRAViaHeader: wrong completion count", resourcePath);

        return PASSED_TEXT;
    }

    // @Test
    private String join() throws WebApplicationException {
        List<LRAInfo> lras = lraClient.getActiveLRAs();
        int count = lras.size();
        URL lra = lraClient.startLRA(null, "SpecTest#join", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);
        lraClient.closeLRA(lra);

        lras = lraClient.getActiveLRAs();
        System.out.printf("join ok %d versus %d lras%n", count, lras.size());
        assertEquals(count, lras.size(), "join: wrong LRA count", resourcePath);

        return lra.toExternalForm();
    }

    // @Test
    private String leaveLRA() throws WebApplicationException {
        int cnt1 = completedCount(true);
        URL lra = lraClient.startLRA(null, "SpecTest#leaveLRA", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        // perform a second request to the same method in the same LRA context to validate that multiple participants are not registered
        resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        // call a method annotated with @Leave (should remove the participant from the LRA)
        resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("leave");
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        lraClient.closeLRA(lra);

        // check that participant was not told to complete
        int cnt2 = completedCount(true);

        assertEquals(cnt1, cnt2, "leaveLRA: wrong completion count", resourcePath);

        return lra.toExternalForm();
    }

    // @Test
    private String leaveLRAViaAPI() throws WebApplicationException {
        int cnt1 = completedCount(true);
        URL lra = lraClient.startLRA(null, "SpecTest#leaveLRAViaAPI", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);

        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        // perform a second request to the same method in the same LRA context to validate that multiple participants are not registered
        resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(WORK_TEXT);
        response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        String recoveryUrl = response.getHeaderString(LRAClient.LRA_HTTP_RECOVERY_HEADER);        
        
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        // call a method annotated with @Leave (should remove the participant from the LRA)
        try {
            resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("leave");
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
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        lraClient.closeLRA(lra);

        // check that participant was not told to complete
        int cnt2 = completedCount(true);

        assertEquals(cnt1, cnt2,
                String.format("leaveLRAViaAPI: wrong count %d versus %d", cnt1, cnt2), resourcePath);

        return PASSED_TEXT;
    }

    // @Test
    private String dependentLRA() throws WebApplicationException {
        // call a method annotated with NOT_SUPPORTED but one which programatically starts an LRA and returns it via a header
        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("startViaApi");
        Response response = resourcePath.request().put(Entity.text(""));
        // check that the method started an LRA
        Object lraHeader = response.getHeaders().getFirst(LRAClient.LRA_HTTP_HEADER);

        String id = checkStatusAndClose(response, Response.Status.OK.getStatusCode(), true, resourcePath);

        // the value returned via the header and body should be equal

        assertNotNull(lraHeader, String.format("JAX-RS response to PUT request should have returned the header %s",
                LRAClient.LRA_HTTP_HEADER), resourcePath);
        assertNotNull(id, "JAX-RS response to PUT request should have returned content", resourcePath);
        assertEquals(id, lraHeader.toString(), "dependentLRA: resource returned wrong LRA", resourcePath);

        try {
            lraClient.closeLRA(new URL(lraHeader.toString()));
        } catch (MalformedURLException e) {
            throw new WebApplicationException(e);
        }

        return PASSED_TEXT;
    }

    // @Test
    private String cancelOn() {
        cancelCheck("cancelOn");

        return PASSED_TEXT;
    }

    // @Test
    private String cancelOnFamily() {
        cancelCheck("cancelOnFamily");

        return PASSED_TEXT;
    }

    @Test
    public void timeLimit() {
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>> " + lraClient + " - " + timeoutFactor);
        int[] cnt1 = {completedCount(true), completedCount(false)};
        Response response = null;

        try {
            WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("timeLimit");
            response = resourcePath
                    .request()
                    .get();

            checkStatusAndClose(response, -1, true, resourcePath);

            // Note that the timeout firing will cause the coordinator to compensate
            // the LRA so it may no longer exist
            // (depends upon how long the coordinator keeps a record of finished LRAs

            // check that participant was invoked
            int[] cnt2 = {completedCount(true), completedCount(false)};

            /*
             * The call to activities/timeLimit should have started an LRA which should have timed out
             * (because the invoked resource method sleeps for longer than the timeLimit annotation
             * attribute specifies). Therefore the participant should have compensated:
             */
            assertEquals(cnt1[0], cnt2[0],
                    "timeLimit: complete was called instead of compensate", resourcePath);
            assertEquals(cnt1[1] + 1, cnt2[1],
                    "timeLimit: compensate should have been called", resourcePath);
        } finally {

            if (response != null) {
                response.close();
            }
        }
    }

    /*
     * Participants can pass data during enlistment and this data will be returned during
     * the complete/compensate callbacks
     */
    private void testUserData() {
        List<LRAInfo> lras = lraClient.getActiveLRAs();
        int count = lras.size();
        String testData = "test participant data";
        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("testUserData");

        Response response = resourcePath
                .request().put(Entity.text(testData));

        String activityId = response.readEntity(String.class);
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        lras = lraClient.getActiveLRAs();

        assertEquals(count, lras.size(), "testUserData: testUserData produced the wrong LRA count",
                resourcePath);

        response = tckSuiteTarget.path(ACTIVITIES_PATH).path("getActivity")
                .queryParam("activityId", activityId)
                .request()
                .get();

        String activity = response.readEntity(String.class);

        // validate that the service received the correct data during the complete call
        assertTrue(activity.contains("userData='" + testData), null, null, null);
        assertTrue(activity.contains("endData='" + testData), null, null, null);
    }

    // @Test
    private String acceptTest() throws WebApplicationException {
        joinAndEnd(true, true, ACTIVITIES_PATH, ACCEPT_WORK);
        return PASSED_TEXT;
    }

    // TODO the spec does not specifiy recovery semantics
    // @Test
    private void joinAndEnd(boolean waitForRecovery, boolean close, String path, String path2) throws WebApplicationException {
        int countBefore = lraClient.getActiveLRAs().size();
        URL lra = lraClient.startLRA(null, "SpecTest#join", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(path).path(path2);

        Response response = resourcePath
                .request().header(LRAClient.LRA_HTTP_HEADER, lra).put(Entity.text(""));

        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false, resourcePath);

        if (close) {
            lraClient.closeLRA(lra);
        } else {
            lraClient.cancelLRA(lra);
        }

        if (waitForRecovery) {
            // trigger a recovery scan which trigger a replay attempt on any participants
            // that have responded to complete/compensate requests with Response.Status.ACCEPTED
            resourcePath = recoveryTarget.path(RECOVERY_PATH_TEXT);
            Response response2 = resourcePath
                    .request().get();

            checkStatusAndClose(response2, Response.Status.OK.getStatusCode(), false, resourcePath);
        }

        int countAfter = lraClient.getActiveLRAs().size();

        assertEquals(countBefore, countAfter, "joinAndEnd: some LRAs were not recovered", resourcePath);
    }

    // @Test
    private String noLRATest() throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget
                .path(StandardController.ACTIVITIES_PATH3)
                .path(StandardController.NON_TRANSACTIONAL_WORK);

        int[] cnt1 = {completedCount(true), completedCount(false)};
        URL lra = lraClient.startLRA(null, "SpecTest#noLRATest",
                LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);

        Response response = resourcePath.request().header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        String result = checkStatusAndClose(response, Response.Status.OK.getStatusCode(),
                true, resourcePath);

        assertEquals(result, lra.toExternalForm(), "service returned the wrong LRA", null);

        lraClient.cancelLRA(lra);

        // check that second service (the LRA aware one), namely
        // {@link org.eclipse.microprofile.lra.tck.participant.api.ActivityController#activityWithMandatoryLRA(String, String)}
        // was told to compensate
        int[] cnt2 = {completedCount(true), completedCount(false)};

        assertEquals(cnt1[0], cnt2[0], "complete should not have been called", resourcePath);
        assertEquals(cnt1[1] + 1, cnt2[1], "compensate should have been called", resourcePath);

        return PASSED_TEXT;
    }

    private void renewTimeLimit() {
        int[] cnt1 = {completedCount(true), completedCount(false)};
        Response response = null;

        try {
            WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH)
                    .path("renewTimeLimit");

            response = resourcePath
                    .request()
                    .get();

            checkStatusAndClose(response, -1, true, resourcePath);

            // check that participant was invoked
            int[] cnt2 = {completedCount(true), completedCount(false)};

            /*
             * The call to activities/timeLimit should have started an LRA whch should not have timed out
             * (because the called resource method renews the timeLimit before sleeping for longer than
             * the timeLimit annotation attribute specifies).
             * Therefore the participant should not have compensated:
             */
            assertEquals(cnt1[0] + 1, cnt2[0],
                    resourcePath.getUri().toString() + ": compensate was called instead of complete", resourcePath);
            assertEquals(cnt1[1], cnt2[1],
                    resourcePath.getUri().toString() + ": compensate should not have been called", resourcePath);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private String checkStatusAndClose(Response response, int expected, boolean readEntity, WebTarget webTarget) {
        try {
            if (expected != -1 && response.getStatus() != expected) {
                if (webTarget != null) {
                    throw new WebApplicationException(String.format("%s: expected status %d got %d",
                            webTarget.getUri().toString(), expected, response.getStatus()), response);
                }

                throw new WebApplicationException(response);
            }

            if (readEntity) {
                return response.readEntity(String.class);
            }
        } finally {
            response.close();
        }

        return null;
    }

    private int completedCount(boolean completed) {
        Response response = null;
        String path = completed ? "completedactivitycount" : "compensatedactivitycount";

        try {
            WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(path);

            response = resourcePath.request().get();

            assertEquals(Response.Status.OK.getStatusCode(),
                    response.getStatus(),
                    resourcePath.getUri().toString() + ": wrong status",
                    resourcePath);

            return Integer.parseInt(response.readEntity(String.class));
        } finally {
            if (response != null) {
                response.close();
            }
        }

    }

    private String multiLevelNestedActivity(CompletionType how, int nestedCnt) throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path("multiLevelNestedActivity");

        int[] cnt1 = {completedCount(true), completedCount(false)};

        if (how == CompletionType.mixed && nestedCnt <= 1) {
            how = CompletionType.complete;
        }

        URL lra = lraClient.startLRA(null, "SpecTest#multiLevelNestedActivity", LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        String lraId = lra.toString();

        Response response = resourcePath
                .queryParam("nestedCnt", nestedCnt)
                .request()
                .header(LRAClient.LRA_HTTP_HEADER, lra)
                .put(Entity.text(""));

        String lraStr = checkStatusAndClose(response, Response.Status.OK.getStatusCode(), true, resourcePath);
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
        assertEquals(nestedCnt + 1, lraArray.length, "multiLevelNestedActivity: step 1", resourcePath);
        assertEquals(lraId, lraArray[0], "multiLevelNestedActivity: step 2", resourcePath); // first element should be the mandatory LRA

        // check that the coordinator knows about the two nested LRAs started by the multiLevelNestedActivity method
        // NB even though they should have completed they are held in memory pending the enclosing LRA finishing
        IntStream.rangeClosed(1, nestedCnt).forEach(i -> assertNotNull(getLra(lras, lraArray[i]),
                " missing nested LRA: step 2b",
                resourcePath));

        // and the mandatory lra seen by the multiLevelNestedActivity method
        assertNotNull(getLra(lras, lraArray[0]), "lra should have been found", resourcePath);

        int[] cnt2 = {completedCount(true), completedCount(false)};

        // check that all nested activities were told to complete
        assertEquals(cnt1[0] + nestedCnt, cnt2[0], "multiLevelNestedActivity: step 3", resourcePath);
        // and that neither were told to compensate
        assertEquals(cnt1[1], cnt2[1], "multiLevelNestedActivity: step 4", resourcePath);

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

        IntStream.rangeClosed(0, nestedCnt).forEach(i -> assertNull(getLra(lras2, lraArray[i]),
                        "multiLevelNestedActivity: top level or nested activity still active", resourcePath));

        int[] cnt3 = {completedCount(true), completedCount(false)};

        if (how == CompletionType.complete) {
            // make sure that all nested activities were not told to complete or cancel a second time
            assertEquals(cnt2[0] + nestedCnt, cnt3[0], "multiLevelNestedActivity: step 5", resourcePath);
            // and that neither were still not told to compensate
            assertEquals(cnt1[1], cnt3[1], "multiLevelNestedActivity: step 6", resourcePath);

        } else if (how == CompletionType.compensate) {
            /*
             * the test starts LRA1 calls a @Mandatory method multiLevelNestedActivity which enlists in LRA1
             * multiLevelNestedActivity then calls an @Nested method which starts L2 and enlists another participant
             *   when the method returns the nested participant is completed (ie completed count is incremented)
             * Canceling L1 should then compensate the L1 enlistement (ie compensate count is incrememted)
             * which will then tell L2 to compenstate (ie the compensate count is incrememted again)
             */
            // each nested participant should have completed (the +nestedCnt)
            assertEquals(cnt1[0] + nestedCnt, cnt3[0], "multiLevelNestedActivity: step 7", resourcePath);
            // each nested participant should have compensated. The top level enlistement should have compensated (the +1)
            assertEquals(cnt2[1] + 1 + nestedCnt, cnt3[1], "multiLevelNestedActivity: step 8", resourcePath);
        } else {
            /*
             * The test is calling for a mixed uutcome:
             * - the top level LRA was closed
             * - one of the nested LRAs was compensated the rest should have been completed
             */
            // there should be just 1 compensation (the first nested LRA)
            assertEquals(1, cnt3[1] - cnt1[1], "multiLevelNestedActivity: step 9", resourcePath);
            /*
             * Expect nestedCnt + 1 completions, 1 for the top level and one for each nested LRA
             * (NB the first nested LRA is completed and compensated)
             * Note that the top level complete should not call complete again on the nested LRA
             */
            assertEquals(nestedCnt + 1, cnt3[0] - cnt1[0], "multiLevelNestedActivity: step 10", resourcePath);
        }

        return PASSED_TEXT;
    }

    private void cancelCheck(String path) {
        int[] cnt1 = {completedCount(true), completedCount(false)};
        URL lra = lraClient.startLRA(null, "SpecTest#" + path, LRA_TIMEOUT_MILLIS, ChronoUnit.MILLIS);
        Response response = null;

        WebTarget resourcePath = tckSuiteTarget.path(ACTIVITIES_PATH).path(path);

        try {
            response = resourcePath
                    .request()
                    .header(LRAClient.LRA_HTTP_HEADER, lra)
                    .get();

            checkStatusAndClose(response, Response.Status.BAD_REQUEST.getStatusCode(), true, resourcePath);

            // check that participant was invoked
            int[] cnt2 = {completedCount(true), completedCount(false)};

            // check that complete was not called and that compensate was
            assertEquals(cnt1[0], cnt2[0], "complete was called instead of compensate", resourcePath);
            assertEquals(cnt1[1] + 1, cnt2[1], "compensate should have been called", resourcePath);

            try {
                assertTrue(!lraClient.isActiveLRA(lra), "cancelCheck: LRA should have been cancelled", resourcePath, lra);
            } catch (NotFoundException ignore) {
                // means the LRA has gone
            }
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private static LRAInfo getLra(List<LRAInfo> lras, String lraId) {
        for (LRAInfo lraInfo : lras) {
            if (lraInfo.getLraId().equals(lraId)) {
                return lraInfo;
            }
        }

        return null;
    }

    private static void assertTrue(boolean condition, String reason, WebTarget target, URL lra) {
//        assert condition;

        if (!condition) {
            throw new GenericLRAException(lra, 0, target.getUri().toString() + ": " + reason, null);
        }
    }

    private static <T> void assertEquals(T expected, T actual, String reason, WebTarget target) {
//        assert expected.equals(actual);

        if (!expected.equals(actual)) {
            throw new GenericLRAException(null, 0, target.getUri().toString() + ": " + reason, null);
        }
    }
    private static void fail(String msg) {
        System.out.printf("%s%n", msg);
        assert false;
    }

    private static <T> void assertNotNull(T value, String reason, WebTarget target) {
        if (value == null) {
            if (target == null) {
                throw new GenericLRAException(null, 0, reason, null);
            } else {
                throw new GenericLRAException(null, 0, target.getUri().toString() + reason, null);
            }
        }
    }

    private static <T> void assertNull(T value, String reason, WebTarget target) {
        if (value != null) {
            if (target == null) {
                throw new GenericLRAException(null, 0, reason, null);
            } else {
                throw new GenericLRAException(null, 0, target.getUri().toString() + reason, null);
            }
        }
    }
}
