import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';

type UrlResponse = { url: string };

@Injectable({ providedIn: 'root' })
export class BillingService {
  private http = inject(HttpClient);

  async beginCheckout(priceId: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<UrlResponse>(environment.apiUrl + '/billing/checkout', { priceId }));
    if (!res?.url) throw new Error('No checkout URL returned');
    window.location.href = res.url; // Stripe-hosted page
  }
}
