package us.poliscore.billing;

import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.service.storage.CachedDynamoDbService;

@ApplicationScoped
public class EntitlementsService {

  @Inject CachedDynamoDbService ddb;

  @ConfigProperty(name = "entitlements.grace-seconds", defaultValue = "0")
  long graceSeconds;

  public boolean isEntitled(String userId) {
    if (userId == null) return false;

    // normalize key if needed
    if (!userId.startsWith(UserAccount.ID_CLASS_PREFIX + "/")) {
      userId = UserAccount.ID_CLASS_PREFIX + "/" + userId;
    }

    val op = ddb.get(userId, UserAccount.class);
    if (op.isEmpty()) return false;

    UserAccount ua = op.get();
    String status = ua.getStatus();
    Long cpe = ua.getCurrentPeriodEnd();          // epoch seconds
    Boolean cape = ua.getCancelAtPeriodEnd();     // may be null

    // Entitled inside the current paid term if active or trialing
    if ("active".equals(status) || "trialing".equals(status)) {
      return !isExpired(cpe, graceSeconds);
    }

    // If user canceled at period end, allow until the end of the term
    if (Boolean.TRUE.equals(cape) && !isExpired(cpe, graceSeconds)) {
      return true;
    }

    // (Optional) allow 'past_due' inside the current period
    if ("past_due".equals(status) && !isExpired(cpe, graceSeconds)) {
      return true;
    }

    return false;
  }

  public boolean isAuthenticated(String userId) {
    return userId != null && !userId.isBlank();
  }

  private static boolean isExpired(Long cpe, long graceSeconds) {
    if (cpe == null || cpe <= 0) return false; // be lenient if you don’t have CPE
    long now = Instant.now().getEpochSecond();
    return now > (cpe + Math.max(0, graceSeconds));
  }
}
