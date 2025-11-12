package us.poliscore.billing;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@Path("/stripe/webhook")
public class StripeWebhookResource {

  @Inject StripeSignatureVerifier verifier;
  @Inject DynamoDbPersistenceService ddb;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response handle(@HeaderParam("Stripe-Signature") String sig, String payload) {
    final Event event;
    try {
      event = verifier.verifyAndParse(payload, sig);
    } catch (Exception e) {
      return Response.status(400).entity("Bad signature").build();
    }

    final var type = event.getType();
    final var obj = event.getDataObjectDeserializer().getObject().orElse(null);
    if (obj == null) return Response.ok().build(); // nothing to do

    try {
      switch (type) {

        // After Checkout completes: attach Stripe IDs to your user record
        case "checkout.session.completed" -> {
          final var s = (Session) obj;

          String userId = firstNonBlank(
              s.getClientReferenceId(),
              s.getMetadata() != null ? s.getMetadata().get("userId") : null,
              s.getMetadata() != null ? s.getMetadata().get("app_user_id") : null
          );
          if (isBlank(userId)) userId = "unknown";
          
          final String fUserId = userId;
          final String customerId = s.getCustomer();
          final String subId      = s.getSubscription();

          UserAccount acct = ddb.get(userId, UserAccount.class).orElseGet(() -> {
            var ua = new UserAccount();
            ua.setId(fUserId);
            return ua;
          });

          acct.setStripeCustomerId(customerId);
          acct.setSubscriptionId(subId);
          acct.setPlan("premium");
          acct.setStatus("incomplete"); // will flip to active on invoice.paid / subscription.updated
          acct.setUpdatedAt(java.time.Instant.now());

          ddb.put(acct);
          Log.infof("checkout.session.completed: upserted user=%s customer=%s sub=%s", userId, customerId, subId);
        }

        // Authoritative lifecycle events for the Subscription
        case "customer.subscription.created",
             "customer.subscription.updated",
             "customer.subscription.deleted" -> {
          final var sub = (Subscription) obj;

          String userId = (sub.getMetadata() != null)
              ? firstNonBlank(sub.getMetadata().get("userId"), sub.getMetadata().get("app_user_id"))
              : null;

          UserAccount acc = !isBlank(userId)
              ? ddb.get(userId, UserAccount.class).orElseGet(() -> { var ua = new UserAccount(); ua.setId(userId); return ua; })
              : ddb.get("unknown", UserAccount.class).orElseGet(() -> { var ua = new UserAccount(); ua.setId("unknown"); return ua; });

          acc.setSubscriptionId(sub.getId());
          acc.setStripeCustomerId(sub.getCustomer());
          acc.setStatus(sub.getStatus());

          // --- current_period_end now lives on subscription items ---
          Long maxCpe = null;
          if (sub.getItems() != null && sub.getItems().getData() != null && !sub.getItems().getData().isEmpty()) {
            for (SubscriptionItem it : sub.getItems().getData()) {
              Long itemCpe = it.getCurrentPeriodEnd(); // epoch seconds on each item
              if (itemCpe != null && (maxCpe == null || itemCpe > maxCpe)) {
                maxCpe = itemCpe;
              }
            }

            // Also capture price/plan from the first item for convenience
            var first = sub.getItems().getData().get(0);
            if (first != null) {
              if (first.getPrice() != null) {
                acc.setPriceId(first.getPrice().getId());      // "price_..."
              } else if (first.getPlan() != null) {
                acc.setPriceId(first.getPlan().getId());       // "plan_..." (older API surfaces)
              }
            }
          }
          if (maxCpe != null) acc.setCurrentPeriodEnd(maxCpe);

          // --- cancel at period end still on Subscription ---
          acc.setCancelAtPeriodEnd(Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));

          acc.setUpdatedAt(java.time.Instant.now());
          ddb.put(acc);

          Log.infof("subscription.%s: user=%s sub=%s status=%s cpe=%s cape=%s",
              type.substring(type.lastIndexOf('.') + 1),
              acc.getId(), acc.getSubscriptionId(), acc.getStatus(),
              String.valueOf(acc.getCurrentPeriodEnd()),
              String.valueOf(acc.getCancelAtPeriodEnd()));
        }

        // Payment for the current term succeeded
        case "invoice.paid" -> {
          final var inv = (Invoice) obj;

          // Period end from the first invoice line
          Long periodEnd = null;
          if (inv.getLines() != null
              && inv.getLines().getData() != null
              && !inv.getLines().getData().isEmpty()
              && inv.getLines().getData().get(0).getPeriod() != null) {
            periodEnd = inv.getLines().getData().get(0).getPeriod().getEnd();
          }

          // Resolve your user via invoice/customer metadata (set these when creating Customer/Session)
          String userId = scrapeUserId(
              inv.getMetadata(),
              inv.getCustomerObject() != null ? inv.getCustomerObject().getMetadata() : null
          );

          UserAccount acc = (!isBlank(userId)) ? ddb.get(userId, UserAccount.class).orElse(null) : null;

          if (acc != null) {
            acc.setStatus("active");
            if (periodEnd != null) acc.setCurrentPeriodEnd(periodEnd);
            acc.setUpdatedAt(java.time.Instant.now());
            ddb.put(acc);
            Log.infof("invoice.paid: user=%s set active; cpe=%s", acc.getId(), String.valueOf(periodEnd));
          } else {
            Log.warn("invoice.paid: could not resolve user account; consider lookup by stripeCustomerId");
          }
        }

        case "invoice.payment_failed" -> {
          // Optional: set a soft-fail/grace state or rely on subsequent subscription.updated
        }

        default -> {
          // ignore others
        }
      }
    } catch (Exception ex) {
      Log.warnf(ex, "stripe webhook handling failed for type %s", type);
      return Response.ok().build();
    }

    return Response.ok().build();
  }

  // ---------- helpers ----------

  private static String firstNonBlank(String... vals) {
    if (vals == null) return null;
    for (String v : vals) if (!isBlank(v)) return v;
    return null;
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private String scrapeUserId(Map<String, String>... metadatas) {
    if (metadatas == null) return null;
    for (var metadata : metadatas) {
      if (metadata == null) continue;
      if (metadata.containsKey("app_user_id")) return metadata.get("app_user_id");
      if (metadata.containsKey("userId")) return metadata.get("userId");
    }
    return null;
  }
}
