package us.poliscore.billing;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class AuthInfo {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;
  @Inject UserInfo userInfo; // <-- the key

  public String userId() {
    if (identity == null || identity.isAnonymous()) return null;
    String sub = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
    if (sub != null && !sub.isBlank()) return sub;
    sub = jwt.getSubject();
    if (sub == null) sub = jwt.getClaim("sub");
    return sub;
  }

  public String email() {
    // Prefer UserInfo (Cognito usually puts email here or in the ID token, not the access token)
    if (userInfo != null && userInfo.contains("email")) {
      String v = userInfo.getString("email");
      if (v != null && !v.isBlank()) return v;
    }

    // Occasionally providers expose a top-level attribute
    String attr = identity.getAttribute("email");
    if (attr != null && !attr.isBlank()) return attr;

    // Fallback to ID token claim (often null for bearer/access-only flows)
    String claim = jwt.getClaim("email");
    return (claim != null && !claim.isBlank()) ? claim : null;
  }
}
