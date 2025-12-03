package us.poliscore.billing;

import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.WebappDatabase;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class BillingService {
	@ConfigProperty(name = "stripe.secret")
	String stripeSecret;
	@ConfigProperty(name = "stripe.success.url")
	String successUrl;
	@ConfigProperty(name = "stripe.cancel.url")
	String cancelUrl;

	@Inject
	@WebappDatabase
	ObjectStorageServiceIF ddb;
	
	@Inject
	ObjectMapper mapper;

	@PostConstruct
	void init() {
		Stripe.apiKey = stripeSecret;
		
		// Fail fast if any required URLs are missing
		  if (StringUtils.isBlank(successUrl) || StringUtils.isBlank(cancelUrl)) {
		    Log.errorf("BillingService config missing: successUrl='%s' cancelUrl='%s'", successUrl, cancelUrl);
		    throw new IllegalStateException("BillingService misconfigured: success/cancel URLs must be set");
		  }
	}

	public String ensureCustomer(String userId, String email) throws Exception {
		if (StringUtils.isBlank(userId))
			throw new IllegalArgumentException("userId cannot be blank");

		UserAccount ua = null;
		
		if (!userId.startsWith(UserAccount.ID_CLASS_PREFIX + "/"))
			userId = UserAccount.ID_CLASS_PREFIX + "/" + userId;
		
		val op = ddb.get(userId, UserAccount.class);
		if (op.isPresent() && StringUtils.isNotBlank(op.get().getStripeCustomerId()))
			return op.get().getStripeCustomerId();
		else
			ua = op.orElse(new UserAccount());

		// Email is technically only required if the user doesn't already exist and we need to create it.
		if (StringUtils.isBlank(email))
			throw new IllegalArgumentException("email cannot be blank");

		var customer = Customer.create(Map.of("email", email, "metadata", Map.of("app_user_id", userId)));

		ua.setId(userId);
		ua.setEmail(email);
		ua.setStripeCustomerId(customer.getId());
		ddb.put(ua);

		return customer.getId();
	}

	public String createCheckoutUrl(String priceId, String userId, String email) throws Exception {
		if (!userId.startsWith(UserAccount.ID_CLASS_PREFIX + "/"))
			userId = UserAccount.ID_CLASS_PREFIX + "/" + userId;
		
		Log.info("Checkout resource invoked with userid " + userId + ", priceid " + priceId + ", and email " + email);
		String customerId = ensureCustomer(userId, email);

		SessionCreateParams params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION)
				.setCustomer(customerId)
				.addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
				.setClientReferenceId(userId).putMetadata("app_user_id", userId)
				.setAllowPromotionCodes(true)
				.setSubscriptionData(
						SessionCreateParams.SubscriptionData.builder().putMetadata("userId", userId).build())
				.setSuccessUrl(Objects.requireNonNull(successUrl) + "?session_id={CHECKOUT_SESSION_ID}").setCancelUrl(Objects.requireNonNull(cancelUrl)).build();

		Session session = Session.create(params);
		return session.getUrl(); // Redirect user here
	}

	public String createPortalUrl(String userId, String email) throws Exception {
		if (!userId.startsWith(UserAccount.ID_CLASS_PREFIX + "/"))
			userId = UserAccount.ID_CLASS_PREFIX + "/" + userId;
		
		String customerId = ensureCustomer(userId, email);

		com.stripe.param.billingportal.SessionCreateParams params = com.stripe.param.billingportal.SessionCreateParams
				.builder().setCustomer(customerId).setReturnUrl(successUrl) // e.g. https://poliscore.us/account or
																			// homepage
				.build();

		com.stripe.model.billingportal.Session session = com.stripe.model.billingportal.Session.create(params);

		return session.getUrl(); // return portal URL
	}
}
