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

  login(returnTo: string | null = null): void {
    if (returnTo == null)
      returnTo = location.pathname + location.search + location.hash;

    localStorage.setItem('ps:returnTo', returnTo);
    this.oidc.authorize();
  }

  logout(): void {
    // ChatGPT's advice:
    // Best practice with Cognito: revoke tokens and log out of the OP
    // this.oidc.logoffAndRevokeTokens().subscribe(); // redirects to postLogoutRedirectUri



    // Clear session storage
    // if (window.sessionStorage) {
    //   window.sessionStorage.clear();
    // }


    // window.location.href = "https://us-east-1ukl1ofrmk.auth.us-east-1.amazoncognito.com/logout?client_id=2cf7gbsb646vjei20g6cr0kio8&logout_uri=<logout uri>";
    
    this.clearLocalStorage();

    const logoutUri = window.location.origin.endsWith('/') 
      ? window.location.origin 
      : window.location.origin + '/';

    
    window.location.href =
      `https://us-east-1ukl1ofrmk.auth.us-east-1.amazoncognito.com/logout` +
      `?client_id=2cf7gbsb646vjei20g6cr0kio8` +
      `&logout_uri=${encodeURIComponent(logoutUri)}`;
  }

  clearLocalStorage(): void {
    // const clientId = '2cf7gbsb646vjei20g6cr0kio8';

    // // Remove only OIDC-related entries
    // for (const key of Object.keys(localStorage)) {
    //   if (
    //     key.includes(clientId) ||
    //     key.startsWith('oidc.') ||
    //     key.startsWith('oidc.user:') ||
    //     key.startsWith('oidc.config:')
    //   ) {
    //     localStorage.removeItem(key);
    //   }
    // }

    localStorage.clear();
  }
}
