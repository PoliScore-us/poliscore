package us.poliscore.billing;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@RequiresSubscription
public class SubscriptionGuard implements ContainerRequestFilter {

  @Inject EntitlementsService entitlements;
  @Inject AuthInfo auth;

  public static final String REQ_PROP_ENTITLED = "ps.isEntitled";
  public static final String REQ_PROP_AUTHED   = "ps.isAuthed";

  @Override
  public void filter(ContainerRequestContext ctx) {
    String userId = auth.userId();
    boolean authed   = entitlements.isAuthenticated(userId);
    boolean entitled = entitlements.isEntitled(userId);

    // store for the response filter (so we don't recompute)
    ctx.setProperty(REQ_PROP_AUTHED, authed);
    ctx.setProperty(REQ_PROP_ENTITLED, entitled);

    if (!authed || !entitled) {
      // Include headers even on the error so the client can react
      Response resp = Response.status(402) // Payment Required
          .entity("{\"error\":\"subscription_required\"}")
          .header("X-Is-Authenticated", authed)
          .header("X-Is-Subscribed", entitled)
          .header("Access-Control-Expose-Headers", "X-Is-Authenticated,X-Is-Subscribed")
          .build();
      ctx.abortWith(resp);
    }
  }
}
