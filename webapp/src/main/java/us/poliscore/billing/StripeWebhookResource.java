package us.poliscore.billing;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionCollection;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionRetrieveParams;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.SneakyThrows;
import us.poliscore.model.Persistable;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@Path("/stripe/webhook")
public class StripeWebhookResource {

	@Inject
	StripeSignatureVerifier verifier;

	@Inject
	DynamoDbPersistenceService ddb;

	@ConfigProperty(name = "stripe.secret")
	String stripeSecret;

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@SneakyThrows
	public Response handle(@HeaderParam("Stripe-Signature") java.util.List<String> sigHeaders, String payload) {
		String sig = (sigHeaders == null || sigHeaders.isEmpty()) ? null : String.join(",", sigHeaders); // reconstruct
																											// full
		final Event event;
		try {
			event = verifier.verifyAndParse(payload, sig);
		} catch (Exception e) {
			Log.warnf(e, "Stripe signature verification failed. incomingSig=%s ourSig=%s payloadLen=%s first80='%s'",
					sig, verifier.getWebhookSecret(), (payload == null ? -1 : payload.length()),
					(payload == null) ? ""
							: payload.substring(0, Math.min(payload.length(), 80)).replace("\n", "\\n").replace("\r",
									"\\r"));
			return Response.status(400).entity("Bad signature").build();
		}

		final var type = event.getType();

		Log.info("Stripe webhook invoked. Event type is " + type);

//		final var dod = event.getDataObjectDeserializer();
//		if (dod.getObject().isEmpty()) {
//			Exception e = null;
//		  try {
//		    // Will throw with the underlying reason (missing reflection, unknown field, etc.)
//		    dod.deserializeUnsafe();
//		  } catch (Exception de) {
//		    e = de;
//		  }
//		  io.quarkus.logging.Log.errorf(e, "Stripe DataObject deserialization failed for type %s", event.getType());
//		  
//		  return Response.ok().build();
//		}
//		
//		StripeObject obj = dod.getObject().get();

		StripeObject obj = event.getDataObjectDeserializer().deserializeUnsafe();

		if (obj == null)
			throw new NullPointerException("stripe data object was null...");

		try {
			switch (type) {

			// After Checkout completes: attach Stripe IDs to your user record
			case "checkout.session.completed" -> {
				final var s = (Session) obj;

				String userId = firstNonBlank(s.getClientReferenceId(),
						s.getMetadata() != null ? s.getMetadata().get("userId") : null,
						s.getMetadata() != null ? s.getMetadata().get("app_user_id") : null);
				if (isBlank(userId))
					userId = "unknown";

				final String customerId = s.getCustomer();
				final String subId = s.getSubscription();

				// quick upsert so UI can reflect something immediately
				UserAccount acct = ddb.get(userId, UserAccount.class)
						.orElseGet(() -> getByStripeId(customerId).orElse(new UserAccount()));
				acct.setId(userId);
				acct.setStripeCustomerId(customerId);
				acct.setSubscriptionId(subId);
				acct.setPlan("premium");
				acct.setStatus("incomplete");
				ddb.putIfLatest(acct);

				// now fetch canonical state from Stripe (order-proof)
				if (!isBlank(subId)) {
					try {
						Subscription fresh = retrieveSubscription(subId);
						upsertFromSubscription(fresh, userId);
					} catch (Exception e) {
						Log.warnf(e, "failed to sync sub after checkout.session.completed sub=%s", subId);
					}
				}

				Log.infof("checkout.session.completed: upserted user=%s customer=%s sub=%s", userId, customerId, subId);
			}

			// Authoritative lifecycle events for the Subscription
			case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> {
				final var sub = (Subscription) obj; // from event (may be stale)
				try {
					Subscription fresh = retrieveSubscription(sub.getId()); // canonical
					String hintedUserId = firstNonBlank(
							fresh.getMetadata() != null ? fresh.getMetadata().get("userId") : null,
							fresh.getMetadata() != null ? fresh.getMetadata().get("app_user_id") : null);
					upsertFromSubscription(fresh, hintedUserId);
				} catch (Exception e) {
					Log.warnf(e, "failed to retrieve/sync subscription %s from stripe", sub.getId());
				}
			}

			// Payment for the current term succeeded
			case "invoice.paid" -> {
				  final var inv = (Invoice) obj;

				  String customerId = inv.getCustomer();       // this exists in all versions
				  String invoiceId  = inv.getId();

				  String subId = null;
				  try {
				    subId = resolveSubIdForInvoice(customerId, invoiceId).orElse(null);
				  } catch (Exception e) {
				    Log.warnf(e, "Could not resolve subscription by latest_invoice for invoice %s", invoiceId);
				  }

				  if (subId != null && !subId.isBlank()) {
				    try {
				      Subscription fresh = retrieveSubscription(subId); // your helper that fetches + expands items.price/plan
				      upsertFromSubscription(fresh, null);              // your canonical sync
				      break;
				    } catch (Exception e) {
				      Log.warnf(e, "Failed to retrieve subscription %s for invoice %s; falling back.", subId, invoiceId);
				    }
				  }

				  // Fallback: mark active by customer (still safe/order-proof enough)
				  Long periodEnd = null;
				  if (inv.getLines() != null && inv.getLines().getData() != null && !inv.getLines().getData().isEmpty()
				      && inv.getLines().getData().get(0).getPeriod() != null) {
				    periodEnd = inv.getLines().getData().get(0).getPeriod().getEnd();
				  }

				  UserAccount acc = getByStripeId(customerId).orElseGet(() -> {
				    var ua = new UserAccount();
				    ua.setStripeCustomerId(customerId);
				    return ua;
				  });

				  acc.setStatus("active");
				  if (periodEnd != null) acc.setCurrentPeriodEnd(periodEnd);
				  ddb.putIfLatest(acc);

				  Log.infof("invoice.paid (fallback): customer=%s active; cpe=%s",
				      acc.getStripeCustomerId(), String.valueOf(periodEnd));
				}

			default -> {
				// ignore others
				Log.warnf("No built-in handler for stripe event %s", type);
			}
			}
		} catch (Exception ex) {
			Log.warnf(ex, "stripe webhook handling failed for type %s", type);
			return Response.ok().build();
		}

		return Response.ok().build();
	}

	// ---------- helpers ----------

	/**
	 * It is well known and assumed that stripe events may come in out of order or
	 * even out-of-date. For this reason, it is recommended that you simply fetch
	 * the latest subscription state from stripe instead of using the information
	 * coming from the event. Seems stupid? I agree. But that's what they recommend.
	 * 
	 * @param subId
	 * @return
	 * @throws Exception
	 */
	private Subscription retrieveSubscription(String subId) throws Exception {
		// Use typed params to expand what you need for convenience
		SubscriptionRetrieveParams params = SubscriptionRetrieveParams.builder().addExpand("items.data.price")
				.addExpand("items.data.plan").build();

		RequestOptions opts = RequestOptions.builder().setApiKey(stripeSecret).build();

		return Subscription.retrieve(subId, params, opts);
	}

	private Invoice retrieveInvoiceWithSubscription(String invoiceId) throws Exception {
		InvoiceRetrieveParams params = InvoiceRetrieveParams.builder().addExpand("subscription") // ensures
																									// getSubscriptionObject()
																									// is populated
				.addExpand("lines.data.price") // optional: handy for price/plan info
				.build();
		return Invoice.retrieve(invoiceId, params, stripeOpts());
	}

	private Optional<String> resolveSubIdForInvoice(String customerId, String invoiceId) throws Exception {
		// Expand latest_invoice so we can match it to this invoice
		SubscriptionListParams params = SubscriptionListParams.builder().setCustomer(customerId)
				.setStatus(SubscriptionListParams.Status.ALL).setLimit(20L).addExpand("data.latest_invoice").build();

		SubscriptionCollection col = Subscription.list(params, stripeOpts());
		for (Subscription s : col.getData()) {
			var latest = s.getLatestInvoiceObject(); // expanded by addExpand above
			if (latest != null && invoiceId.equals(latest.getId())) {
				return Optional.ofNullable(s.getId());
			}
		}
		return Optional.empty();
	}

	private RequestOptions stripeOpts() {
		return RequestOptions.builder().setApiKey(stripeSecret).build();
	}

	private void upsertFromSubscription(Subscription sub, String hintedUserId) {
		// Try to resolve user id from metadata if not provided
		String userId = firstNonBlank(hintedUserId,
				sub.getMetadata() != null ? sub.getMetadata().get("app_user_id") : null,
				sub.getMetadata() != null ? sub.getMetadata().get("userId") : null);

		UserAccount acc = (userId != null ? ddb.get(userId, UserAccount.class).orElse(null) : null);
		if (acc == null) {
			acc = getByStripeId(sub.getCustomer()).orElse(new UserAccount());
		}

		if (userId != null)
			acc.setId(userId);
		acc.setStripeCustomerId(sub.getCustomer());
		acc.setSubscriptionId(sub.getId());
		acc.setStatus(sub.getStatus());

		// Prefer item-level current_period_end, fallback to subscription field if
		// present
		Long maxCpe = null;
		if (sub.getItems() != null && sub.getItems().getData() != null) {
			for (SubscriptionItem it : sub.getItems().getData()) {
				Long itemCpe = it.getCurrentPeriodEnd();
				if (itemCpe != null && (maxCpe == null || itemCpe > maxCpe))
					maxCpe = itemCpe;
			}
			// capture price/plan from first item for convenience
			if (!sub.getItems().getData().isEmpty()) {
				var first = sub.getItems().getData().get(0);
				if (first.getPrice() != null)
					acc.setPriceId(first.getPrice().getId());
				else if (first.getPlan() != null)
					acc.setPriceId(first.getPlan().getId());
			}
		}

		if (maxCpe != null)
			acc.setCurrentPeriodEnd(maxCpe);

		acc.setCancelAtPeriodEnd(Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));

		ddb.putIfLatest(acc);

		Log.infof("sync sub: user=%s sub=%s status=%s cpe=%s cape=%s", acc.getId(), acc.getSubscriptionId(),
				acc.getStatus(), String.valueOf(acc.getCurrentPeriodEnd()), String.valueOf(acc.getCancelAtPeriodEnd()));
	}

	private Optional<UserAccount> getByStripeId(String stripeCustomerId) {
		if (StringUtils.isEmpty(stripeCustomerId)) return Optional.empty();
		
		return ddb
				.query(UserAccount.class, -1, Persistable.OBJECT_BY_LOCATION_INDEX, null, null, stripeCustomerId, UserAccount.ID_CLASS_PREFIX)
				.stream()
//				.filter(ua -> Objects.equals(ua.getStripeCustomerId(), inv.getCustomer()))
				.findFirst();
	}

	private static String firstNonBlank(String... vals) {
		if (vals == null)
			return null;
		for (String v : vals)
			if (!isBlank(v))
				return v;
		return null;
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private String scrapeUserId(Map<String, String>... metadatas) {
		if (metadatas == null)
			return null;
		for (var metadata : metadatas) {
			if (metadata == null)
				continue;
			if (metadata.containsKey("app_user_id"))
				return metadata.get("app_user_id");
			if (metadata.containsKey("userId"))
				return metadata.get("userId");
		}
		return null;
	}
}
