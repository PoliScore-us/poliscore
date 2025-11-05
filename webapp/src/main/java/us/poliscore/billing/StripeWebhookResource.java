package us.poliscore.billing;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.val;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@Path("/stripe/webhook")
public class StripeWebhookResource {

  @ConfigProperty(name = "stripe.webhook-secret") String webhookSecret;
  
  @Inject DynamoDbPersistenceService ddb;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response handle(@HeaderParam("Stripe-Signature") String sig, String payload) {
    Event event;
    try {
      event = Webhook.constructEvent(payload, sig, webhookSecret);
    } catch (Exception e) {
      return Response.status(400).build();
    }

    switch (event.getType()) {
      case "checkout.session.completed" -> {
        var obj = event.getDataObjectDeserializer().getObject().orElse(null);
        if (obj instanceof Session s) {
          var userId = s.getClientReferenceId();
          var op = ddb.get(userId, UserAccount.class);
          if (op.isPresent()) {
        	  val ub = op.get();
	          ub.setPlan("premium");
	          ub.setStatus("active");
	          ub.setUpdatedAt(java.time.Instant.now());
	          ddb.put(ub);
	          Log.info("Created new user in ddb with userid " + ub.getId());
          }
        }
      }
      // Add more handlers if needed: subscription.updated, canceled, etc.
    }
    return Response.ok().build();
  }
}

