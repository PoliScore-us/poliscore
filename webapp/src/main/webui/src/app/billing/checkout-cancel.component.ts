import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

@Component({
  standalone: true,
  selector: 'app-checkout-cancel',
  imports: [CommonModule, RouterModule],
  template: `
  <div class="page">
    <div class="card">
      <h1>Checkout canceled</h1>
      <p>No charge was made. You can try again anytime.</p>
      <a class="btn" routerLink="/signup">Back to Signup</a>
    </div>
  </div>
  `,
  styles: [`
    .page { max-width: 720px; margin: 40px auto; padding: 0 16px; }
    .card { background:#fff; border:1px solid #eee; border-radius:16px; padding:24px; box-shadow:0 6px 24px rgba(0,0,0,.06); }
    .btn { display:inline-block; margin-top:10px; }
  `]
})
export class CheckoutCancelComponent {}
