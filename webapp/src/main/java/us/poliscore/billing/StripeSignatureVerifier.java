package us.poliscore.billing;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

@ApplicationScoped
public class StripeSignatureVerifier {
  @ConfigProperty(name="stripe.webhook-secret") String webhookSecret;

  public Event verifyAndParse(String payload, String signatureHeader) throws SignatureVerificationException {
    return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
  }
}
