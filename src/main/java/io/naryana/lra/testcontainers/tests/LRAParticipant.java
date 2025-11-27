/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.naryana.lra.testcontainers.tests;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;

@ApplicationScoped
@Path(LRAParticipant.RESOURCE_PATH)
public class LRAParticipant {
    public static final String RESOURCE_PATH = "participant2";

    public static final String CREATE_OR_CONTINUE_LRA = "start-lra";
    public static final String END_EXISTING_LRA = "end-lra";
    public static final String AFTER_LRA = "after-lra";

    @LRA(end = false)
    @GET
    @Path(CREATE_OR_CONTINUE_LRA)
    public Response bookGame(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    @LRA(end = true)
    @GET
    @Path(END_EXISTING_LRA)
    public Response endTripBooking(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    @AfterLRA
    @PUT
    @Path(AFTER_LRA)
    public Response bookingProcessed(@HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus status) {
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }
}
