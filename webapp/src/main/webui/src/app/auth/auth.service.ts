import { Injectable, inject } from '@angular/core';
import { OidcSecurityService, UserDataResult } from 'angular-auth-oidc-client';
import { map, Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class AuthService {

  private oidc = inject(OidcSecurityService);

  constructor() { }

  isAuthenticated$ = this.oidc.isAuthenticated$.pipe(map(s => s.isAuthenticated));
  userData$: Observable<UserDataResult> = this.oidc.userData$;

  displayName$ = this.oidc.userData$.pipe(
    map(ud => {
      const c = ud?.userData as Record<string, any> | undefined;
      return (
        c?.['name'] ??
        c?.['preferred_username'] ??
        c?.['cognito:username'] ??
        c?.['email'] ??
        'Account'
      );
    })
  );

  login(returnTo: string | null = null): void {
    if (returnTo == null)
      returnTo = location.pathname + location.search + location.hash;

    localStorage.setItem('ps:returnTo', returnTo);
    this.oidc.authorize();
  }

  logout(): void {
    // clear local state only
    this.oidc.logoffLocal();

    const postLogout = environment.baseUrl;

    const url =
      `${environment.cognito.domain}/logout` +
      `?client_id=${encodeURIComponent(environment.cognito.clientId)}` +
      `&logout_uri=${encodeURIComponent(postLogout)}`;

    window.location.href = url;
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

  signup(returnUrl?: string) {
    const redirect = encodeURIComponent(returnUrl ?? environment.cognito.redirectUri);
    window.location.href =
      `${environment.cognito.domain}/signup?client_id=${environment.cognito.clientId}` +
      `&response_type=code&scope=${environment.cognito.scope}&redirect_uri=${redirect}`;
  }
}
