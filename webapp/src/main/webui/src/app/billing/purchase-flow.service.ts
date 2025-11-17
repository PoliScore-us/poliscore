// src/app/billing/purchase-flow.service.ts
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, of } from 'rxjs';
import { catchError, take } from 'rxjs/operators';
import { BillingService } from './billing.service';
import { AuthService } from '../auth/auth.service';
import { OidcSecurityService } from 'angular-auth-oidc-client';

const PENDING_PRICE_KEY = 'pendingCheckoutPriceId';
const RETURN_TO_KEY = 'ps:returnTo';

@Injectable({ providedIn: 'root' })
export class PurchaseFlowService {
  private auth = inject(AuthService);               // your wrapper that exposes isAuthenticated$
  private billing = inject(BillingService);
  private oidc = inject(OidcSecurityService);

  async start(priceId: string) {
    const authed = await firstValueFrom(
      this.auth.isAuthenticated$.pipe(take(1), catchError(() => of(false)))
    );
    if (authed) {
      return this.billing.beginCheckout(priceId);
    }

    // plan to resume after callback
    sessionStorage.setItem(PENDING_PRICE_KEY, priceId);
    localStorage.setItem(RETURN_TO_KEY, '/billing/resume');

    this.oidc.authorize(undefined, {
      // Take them to the /signup page so they can create a new user (not log in)
      urlHandler: (url: string) => {
        try {
          const u = new URL(url);

          // Cognito docs: /signup accepts the same query params as /oauth2/authorize
          if (
            u.pathname.endsWith('/login') ||
            u.pathname.endsWith('/oauth2/authorize')
          ) {
            u.pathname = '/signup';
            window.location.href = u.toString();
          } else {
            window.location.href = url;
          }
        } catch {
          // Fallback if URL parsing fails for some reason
          window.location.href = url;
        }
      },
    });
  }

  async resumeAfterLogin() {
    // by the time this runs (after auth-callback), tokens should be valid
    const authed = await firstValueFrom(
      this.auth.isAuthenticated$.pipe(take(1), catchError(() => of(false)))
    );
    if (!authed) {
      // Safety fallback: retry the library-driven login
      localStorage.setItem(RETURN_TO_KEY, '/billing/resume');
      this.oidc.authorize(undefined, { customParams: { screen_hint: 'signup' } });
      return;
    }

    const priceId = sessionStorage.getItem(PENDING_PRICE_KEY);
    sessionStorage.removeItem(PENDING_PRICE_KEY);
    await this.billing.beginCheckout(priceId ?? ''); // redirects to Stripe
  }
}
