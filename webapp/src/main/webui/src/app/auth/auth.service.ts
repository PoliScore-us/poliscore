import { Injectable, inject } from '@angular/core';
import { OidcSecurityService, UserDataResult } from 'angular-auth-oidc-client';
import { map, Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class AuthService {

  private oidc = inject(OidcSecurityService);

  constructor() { }

  isAuthenticated$ = this.oidc.isAuthenticated$.pipe(map(s => s.isAuthenticated));
  userData$: Observable<UserDataResult> = this.oidc.userData$;

  login(): void {
    const returnTo = location.pathname + location.search + location.hash;
    localStorage.setItem('ps:returnTo', returnTo);
    this.oidc.authorize();
  }

  logout(): void {
    // Best practice with Cognito: revoke tokens and log out of the OP
    this.oidc.logoffAndRevokeTokens().subscribe(); // redirects to postLogoutRedirectUri
    // If you prefer manual Hosted UI logout, uncomment:
    // window.location.href =
    //   `https://<your-domain>.auth.us-east-1.amazoncognito.com/logout?client_id=2cf7gbsb646vjei20g6cr0kio8&logout_uri=${encodeURIComponent(window.location.origin)}`;
  }
}
