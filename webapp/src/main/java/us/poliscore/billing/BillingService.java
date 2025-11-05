package us.poliscore.billing;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@ApplicationScoped
public class BillingService {
  @ConfigProperty(name = "stripe.secret") String stripeSecret;
  @ConfigProperty(name = "app.success-url") String successUrl;
  @ConfigProperty(name = "app.cancel-url") String cancelUrl;

  @Inject DynamoDbPersistenceService ddb;

  @PostConstruct
  void init() { Stripe.apiKey = stripeSecret; }

  public String ensureCustomer(String userId, String email) throws Exception {
	  if (StringUtils.isBlank(userId))
		  throw new IllegalArgumentException("userId cannot be blank");
	  if (StringUtils.isBlank(email))
		  throw new IllegalArgumentException("email cannot be blank");
	  
	  val op = ddb.get(userId, UserAccount.class);
	  if (op.isPresent())
		  return op.get().getStripeCustomerId();

    var customer = Customer.create(Map.of(
      "email", email,
      "metadata", Map.of("app_user_id", userId)
    ));

    var ub = new UserAccount();
    ub.setId(userId);
    ub.setEmail(email);
    ub.setStripeCustomerId(customer.getId());
    ub.setUpdatedAt(java.time.Instant.now());
    ddb.put(ub);

    return customer.getId();
  }

  public String createCheckoutUrl(String priceId, String userId, String email) throws Exception {
	  Log.info("Checkout resource invoked with userid " + userId + ", priceid " + priceId + ", and email " + email);
    String customerId = ensureCustomer(userId, email);

    SessionCreateParams params = SessionCreateParams.builder()
      .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
      .setCustomer(customerId)
      .addLineItem(
        SessionCreateParams.LineItem.builder()
          .setPrice(priceId)
          .setQuantity(1L)
          .build()
      )
      .setClientReferenceId(userId)
      .putMetadata("app_user_id", userId)
      .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}")
      .setCancelUrl(cancelUrl)
      .build();

    Session session = Session.create(params);
    return session.getUrl(); // Redirect user here
  }
}

