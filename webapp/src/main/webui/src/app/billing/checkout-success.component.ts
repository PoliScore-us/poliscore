import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { CommonModule } from '@angular/common';

@Component({
  standalone: true,
  selector: 'app-checkout-success',
  imports: [CommonModule, RouterModule],
  template: `
  <div class="page">
    <div class="card">
      <h1>🎉 You’re all set</h1>
      <p>Your subscription is active. Enjoy premium features.</p>
      <a class="btn" routerLink="/legislators">Go to PoliScore</a>
      <div *ngIf="sid" class="meta">Session: {{ sid }}</div>
    </div>
  </div>
  `,
  styles: [`
    .page { max-width: 720px; margin: 40px auto; padding: 0 16px; }
    .card { background:#fff; border:1px solid #eee; border-radius:16px; padding:24px; box-shadow:0 6px 24px rgba(0,0,0,.06); }
    .btn { display:inline-block; margin-top:10px; }
    .meta { margin-top:12px; color:#666; font-size:.9rem; }
  `]
})
export class CheckoutSuccessComponent {
  sid = inject(ActivatedRoute).snapshot.queryParamMap.get('session_id');
}
