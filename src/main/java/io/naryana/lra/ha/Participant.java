package io.naryana.lra.ha;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal participant exposing only the compensate and complete endpoints needed
 * for enrollment in tests that exercise lifecycle other than the callbacks themselves.
 */
@ApplicationScoped
@Path("participant")
public class Participant {

    private static final Logger log = LoggerFactory.getLogger(Participant.class);

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPENSATE lraId={}", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE lraId={}", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }
}
