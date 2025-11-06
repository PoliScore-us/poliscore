package us.poliscore.billing;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SubscriptionResponseMarker implements ContainerResponseFilter {

  @Inject EntitlementsService entitlements;
  @Inject AuthInfo auth;

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) {
    // Prefer values computed by the guard (if present on this route)
    Object authedProp   = req.getProperty(SubscriptionGuard.REQ_PROP_AUTHED);
    Object entitledProp = req.getProperty(SubscriptionGuard.REQ_PROP_ENTITLED);

    boolean authed = (authedProp instanceof Boolean b) ? b : entitlements.isAuthenticated(auth.userId());
    boolean entitled = (entitledProp instanceof Boolean b) ? b : entitlements.isEntitled(auth.userId());

    res.getHeaders().putSingle("X-Is-Authenticated", authed);
    res.getHeaders().putSingle("X-Is-Subscribed", entitled);

    // so browsers can read them
    // If you already set Access-Control-Expose-Headers via a CORS filter, you can skip this.
    String expose = "X-Is-Authenticated,X-Is-Subscribed";
    var existing = res.getHeaders().getFirst("Access-Control-Expose-Headers");
    if (existing == null) {
      res.getHeaders().putSingle("Access-Control-Expose-Headers", expose);
    } else if (!existing.toString().contains("X-Is-Authenticated")) {
      res.getHeaders().putSingle("Access-Control-Expose-Headers", existing + "," + expose);
    }
  }
}
