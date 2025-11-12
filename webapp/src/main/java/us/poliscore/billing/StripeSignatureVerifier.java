package us.poliscore.billing;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

@ApplicationScoped
public class StripeSignatureVerifier {
	@ConfigProperty(name = "stripe.webhook-secret-ps1")
	String webhookSecretPs1;

	@ConfigProperty(name = "stripe.webhook-secret-ps2")
	String webhookSecretPs2;
	
	@ConfigProperty(name = "ddb.table")
	String ddbTableName;

	public Event verifyAndParse(String payload, String signatureHeader) throws SignatureVerificationException {
		if ("poliscore1".equals(ddbTableName))
			return Webhook.constructEvent(payload, signatureHeader, webhookSecretPs1);
		else
			return Webhook.constructEvent(payload, signatureHeader, webhookSecretPs2);
	}
}
