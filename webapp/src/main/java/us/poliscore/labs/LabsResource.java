package us.poliscore.labs;

import org.jboss.resteasy.reactive.RestResponse;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import us.poliscore.billing.AuthInfo;

@Path("/labs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LabsResource {

  record FeatureRequest(String featureId) {}
  record UrlResponse(String url) {}

  @Inject LabsService service;
  @Inject AuthInfo auth;

  @Authenticated
  @POST
  @Path("/requestFeature")
  public RestResponse<Void> requestFeature(FeatureRequest req) throws Exception {
    service.requestFeature(req.featureId(), auth.userId(), auth.email());
    return RestResponse.noContent();
  }
}

