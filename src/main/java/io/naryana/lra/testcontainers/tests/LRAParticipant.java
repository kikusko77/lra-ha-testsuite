/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.naryana.lra.testcontainers.tests;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;

@ApplicationScoped
@Path(LRAParticipant.RESOURCE_PATH)
public class LRAParticipant {
    public static final String RESOURCE_PATH = "participant2";

    public static final String CREATE_OR_CONTINUE_LRA = "start-lra";
    public static final String CREATE_OR_CONTINUE_LRA2 = "start-lra-before-entry-and-end-it-after-exit";
    public static final String END_EXISTING_LRA = "end-lra";
    public static final String CONTINUE_LRA = "continue-lra";
    public static final String START_NEW_LRA = "start-lra2";

    @Context
    UriInfo uriInfo;

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
    @Path("/bookingProcessed")
    public Response bookingProcessed(@HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus status) {
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    private URI remoteInvocation(URI lra, String resourcePath) {
        Response response = null;
        Client client = ClientBuilder.newClient();

        try {
            Invocation.Builder builder = client.target(UriBuilder.fromUri(uriInfo.getBaseUri()) // protocol and domain
                    .path(uriInfo.getPathSegments().get(0).getPath()) // RESOURCE_PATH
                    .path(resourcePath).build()) // the sub-path of the resource method
                    .request();

            if (lra != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, lra.toASCIIString());
            }

            response = builder.get();

            if (response.hasEntity()) {
                return URI.create(response.readEntity(String.class));
            }

            throw new WebApplicationException(
                    Response.status(Response.Status.PRECONDITION_FAILED).entity("Missing LRA is response").build());
        } finally {
            if (response != null) {
                response.close();
            }

            if (client != null) {
                client.close();
            }
        }
    }

    private void validateLRAIsActive(String message, LRAStatus status) {
        if (!status.equals(LRAStatus.Active)) {
            throw new WebApplicationException(Response.status(Response.Status.PRECONDITION_FAILED).entity(message).build());
        }
    }

    private void validateLRAIsNotActive(LRAStatus status) {
        if (status.equals(LRAStatus.Active)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.PRECONDITION_FAILED).entity(
                            "lra2 should no longer be active").build());
        }
    }
}
