package us.poliscore.billing;

import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.NoSuchElementException;
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

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.SneakyThrows;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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

		StripeObject obj = event.getDataObjectDeserializer().deserializeUnsafe();

		if (obj == null)
			throw new NullPointerException("stripe data object was null...");

		switch (type) {

			// After Checkout completes: attach Stripe IDs to your user record
			case "checkout.session.completed" -> {
				final var s = (Session) obj;
	
				String userId = firstNonBlank(s.getClientReferenceId(),
						s.getMetadata() != null ? s.getMetadata().get("userId") : null,
						s.getMetadata() != null ? s.getMetadata().get("app_user_id") : null);
	
				final String customerId = s.getCustomer();
				final String subId = s.getSubscription();
				
				syncSubscriptionFromStripe(subId, customerId, userId);
	
				Log.infof("checkout.session.completed: upserted user=%s customer=%s sub=%s", userId, customerId, subId);
			}
	
			// Authoritative lifecycle events for the Subscription
			case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> {
	
				RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
						.handle(ConditionalCheckFailedException.class, IllegalArgumentException.class)
						.withBackoff(1, 16, ChronoUnit.SECONDS).withMaxRetries(5)
						.onRetry(e -> Log.warn("Retrying due to conditional check failed"))
						.onFailure(e -> Log.error("Retries exhausted", e.getException())).build();
				
				Failsafe.with(retryPolicy).run(() -> {
			        final var sub = (Subscription) obj;
			        String userId = (sub.getMetadata() != null) ? firstNonBlank(sub.getMetadata().get("userId"), sub.getMetadata().get("app_user_id")) : null;
			        String customer = sub.getCustomer();

			        syncSubscriptionFromStripe(sub.getId(), customer, userId);
			    });
			}
	
			// Payment for the current term succeeded
			case "invoice.paid" -> {
				RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
						.handle(ConditionalCheckFailedException.class, IllegalArgumentException.class)
						.withBackoff(1, 16, ChronoUnit.SECONDS).withMaxRetries(5)
						.onRetry(e -> Log.warn("Retrying due to " + e.getClass().getName()))
						.onFailure(e -> Log.error("Retries exhausted", e.getException())).build();
				
				Failsafe.with(retryPolicy).run(() -> {
			        final var inv = (Invoice) obj;
			        String customerId = inv.getCustomer();
			        String invoiceId  = inv.getId();

			        // TODO : It might be possible to get the subscription id from the invoice lines. AI doesn't know how to do this because it wasn't trained on stripe 3.x
			        String subId = null;
					try {
						subId = resolveSubIdForInvoice(customerId, invoiceId).orElse(null);
					} catch (Exception e) {
						Log.warnf(e, "Could not resolve subscription by latest_invoice for invoice %s", invoiceId);
					}
					if (StringUtils.isBlank(subId)) throw new IllegalArgumentException();
					
					syncSubscriptionFromStripe(subId, customerId, null);
			    });
			}
	
			default -> {
				// ignore others
				Log.warnf("No built-in handler for stripe event %s", type);
			}
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
	
	private void syncSubscriptionFromStripe(String subId, String stripeCustomerId, String maybeUserId) throws Exception {
		// First we fetch the user account. It's important this happens first, because it also sets our 'lastUpdate' date, which will be used to determine how 'fresh' our stripe response is.
		UserAccount acct;
	    if (!isBlank(maybeUserId)) {
	        // Prefer explicit user id if you have one
	        acct = ddb.get(maybeUserId, UserAccount.class)
	                  .orElseGet(() -> getByStripeId(stripeCustomerId).orElse(new UserAccount()));
	        acct.setId(maybeUserId);
	    } else {
	        // Fallback: purely by stripe customer
	        acct = getByStripeId(stripeCustomerId).orElse(new UserAccount());
	    }
	    
	    // Now we fetch the latest stripe subscription data.
	    Subscription fresh = retrieveSubscription(subId);

	    // Update account with the data from the subscription, then apply account if nobody else has beaten us to it with newer sub data.
	    upsertFromSubscription(fresh, acct);
	}


	private RequestOptions stripeOpts() {
		return RequestOptions.builder().setApiKey(stripeSecret).build();
	}

	private void upsertFromSubscription(Subscription sub, UserAccount acc) {

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
		
		// Even though we expect the core to do this, it's best to be explicit, just in case the core changes in the future.
		if (StringUtils.isBlank(acc.getId())) throw new IllegalArgumentException();
		
		// Throws if somebody applied new data while we were working. 
		ddb.putIfLatest(acc);

		Log.infof("sync sub: user=%s sub=%s status=%s cpe=%s cape=%s", acc.getId(), acc.getSubscriptionId(),
				acc.getStatus(), String.valueOf(acc.getCurrentPeriodEnd()), String.valueOf(acc.getCancelAtPeriodEnd()));
	}

	private Optional<UserAccount> getByStripeId(String stripeCustomerId) {
		if (StringUtils.isEmpty(stripeCustomerId))
			return Optional.empty();

		return ddb.query(UserAccount.class, -1, Persistable.OBJECT_BY_LOCATION_INDEX, null, null, stripeCustomerId,
				UserAccount.ID_CLASS_PREFIX).stream()
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
}
