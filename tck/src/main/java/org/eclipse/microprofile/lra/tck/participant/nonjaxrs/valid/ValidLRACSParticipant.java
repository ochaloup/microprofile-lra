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
package org.eclipse.microprofile.lra.tck.participant.nonjaxrs.valid;

import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.tck.service.LRAMetricService;
import org.eclipse.microprofile.lra.tck.service.LRAMetricType;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.ACCEPT_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.RECOVERY_PARAM;
import static org.eclipse.microprofile.lra.tck.participant.api.RecoveryResource.LRA_TIMEOUT;

/**
 * Valid participant resource containing async non-JAX-RS participant methods with 
 * {@link CompletionStage} return types
 */
@ApplicationScoped
@Path(ValidLRACSParticipant.ROOT_PATH)
public class ValidLRACSParticipant {
    
    public static final String ROOT_PATH = "valid-cs-participant1";
    public static final String ENLIST_WITH_COMPLETE = "enlist-complete";
    public static final String ENLIST_WITH_COMPENSATE = "enlist-compensate";
    public static final String ENLIST_WITH_LONG_LATENCY_START = "latency-start";
    public static final String ENLIST_WITH_LONG_LATENCY_END = "latency-end";
    private static final Logger LOGGER = Logger.getLogger(ValidLRACSParticipant.class.getName());
    
    private CountDownLatch latch = new CountDownLatch(1);
    private int recoveryPasses;

    @Inject
    private LRAMetricService lraMetricService;

    @GET
    @Path(ENLIST_WITH_COMPLETE)
    @LRA(value = LRA.Type.REQUIRED)
    public Response enlistWithComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        return Response.ok(lraId).build();
    }
    
    @GET
    @Path(ENLIST_WITH_COMPENSATE)
    @LRA(value = LRA.Type.REQUIRED, cancelOn = Response.Status.INTERNAL_SERVER_ERROR)
    public Response enlistWithCompensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(lraId).build();
    }

    @Compensate
    public CompletionStage<Void> compensate(URI lraId) {
        assert lraId != null;
        
        return CompletableFuture.runAsync(() -> lraMetricService.incrementMetric(LRAMetricType.Compensated, lraId));
    }

    @Complete
    public CompletionStage<Response> complete(URI lraId) {
        assert lraId != null;
        
        return CompletableFuture.supplyAsync(() -> {
            lraMetricService.incrementMetric(LRAMetricType.Completed, lraId);
            
            return Response.accepted().build(); // Completing
        });
    }

    @Status
    public CompletionStage<ParticipantStatus> status(URI lraId) {
        assert lraId != null;
        
        return CompletableFuture.supplyAsync(() -> {
            lraMetricService.incrementMetric(LRAMetricType.Status, lraId);
            
            return ParticipantStatus.Completed;
        });
    }
    
    @PUT
    @Path(ACCEPT_PATH)
    @LRA(value = LRA.Type.REQUIRES_NEW)
    public Response acceptLRA(@QueryParam(RECOVERY_PARAM) @DefaultValue("0") Integer recoveryPasses) {
        this.recoveryPasses = recoveryPasses;

        return Response.ok().build();
    }

    @GET
    @Path(ACCEPT_PATH)
    public Response getAcceptLRA() {
        return Response.ok(this.recoveryPasses).build();
    }
    
    @GET
    @Path(ENLIST_WITH_LONG_LATENCY_END)
    public Response longLatencyFinish() {
        latch.countDown();
        return Response.ok().build();
    }
    
    @PUT
    @Path(ENLIST_WITH_LONG_LATENCY_START)
    @LRA(value = LRA.Type.MANDATORY, end = false, timeLimit = 10 * LRA_TIMEOUT, timeUnit = ChronoUnit.MILLIS)
    public Response enlistWithLongLatency(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        LOGGER.info("call of enlistWithLongLatency");
        try {
            latch.await();
            return Response.ok(lraId)
                    .entity(lraMetricService.getMetric(LRAMetricType.Compensated, lraId, ValidLRACSParticipant.class.getName()))
                    .build();
        } catch (InterruptedException ex) {
            return Response.ok(lraId).build();
        }
    }

}
