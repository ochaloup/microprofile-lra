package org.eclipse.microprofile.lra.tck.participant.nonjaxrs.valid;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.tck.service.LRAMetricService;
import org.eclipse.microprofile.lra.tck.service.LRAMetricType;

@ApplicationScoped
@Path(LongBusinessMethodParticipant.ROOT_PATH)
public class LongBusinessMethodParticipant {

    public static final String ROOT_PATH = "long-business-participant";
    public static final String BUSINESS_METHOD = "business-method";
    public static final String SYNC_METHOD = "sync-method";
    
    private static final Logger LOGGER = Logger.getLogger(LongBusinessMethodParticipant.class.getName());

    private CountDownLatch businessLatch = new CountDownLatch(1);
    private CountDownLatch syncLatch = new CountDownLatch(1);
    
    @Inject
    private LRAMetricService lraMetricService;
    
    @Compensate
    public CompletionStage<Void> compensate(URI lraId) {
        assert lraId != null;
        if(businessLatch.getCount() > 0) {
            businessLatch.countDown();
        }
        return CompletableFuture.runAsync(() -> lraMetricService.incrementMetric(LRAMetricType.Compensated, lraId));
    }
    
    @PUT
    @Path(BUSINESS_METHOD)
    @LRA(value = LRA.Type.MANDATORY, end = false)
    public Response enlistWithLongLatency(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        LOGGER.info("call of enlistWithLongLatency");
        try {
            syncLatch.countDown();
            // await for compensation
            businessLatch.await();
            return Response.ok(lraId).build();
        } catch (InterruptedException ex) {
            return Response.serverError().build();
        }
    }

    @PUT
    @Path(SYNC_METHOD)
    public Response sync() {
        LOGGER.info("call of waitForArleadyIn");
        try {
            syncLatch.await();
        } catch (Exception e) {
            throw new IllegalStateException("Exepcting the latch will be succesfully released on long latency LRA is in progress");
        }
        return Response.ok().build();
    }
}
