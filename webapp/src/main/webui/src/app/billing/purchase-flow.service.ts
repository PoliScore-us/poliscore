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

    // IMPORTANT: let the library generate state/nonce and redirect
    // "screen_hint=signup" nudges Cognito Hosted UI to the Sign up screen
    this.oidc.authorize(undefined, {
      customParams: { screen_hint: 'signup' },   // Cognito-friendly
      // Optional: ensure it uses window.location (default does anyway)
      urlHandler: (url: string) => window.location.href = url,
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
