package us.poliscore.billing;

import java.util.Map;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/billing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BillingResource {

  record CheckoutRequest(String priceId) {}
  record UrlResponse(String url) {}

  @Inject BillingService billing;
  @Inject AuthInfo auth;

  @Authenticated
  @POST
  @Path("/checkout")
  public UrlResponse checkout(CheckoutRequest req) throws Exception {
    String url = billing.createCheckoutUrl(req.priceId(), auth.userId(), auth.email());
    return new UrlResponse(url);
  }
  
  @Authenticated
  @GET
  @Path("/portal")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> getPortal() throws Exception {
      String url = billing.createPortalUrl(auth.userId(), auth.email());
      return Map.of("url", url);
  }
}

