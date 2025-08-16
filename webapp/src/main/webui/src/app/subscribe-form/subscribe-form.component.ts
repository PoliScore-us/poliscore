import { Component, Input, ViewChild, ElementRef } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'subscribe-form',
  standalone: true,
  imports: [CommonModule, FormsModule], // ← add FormsModule
  templateUrl: './subscribe-form.component.html',
  styleUrls: ['./subscribe-form.component.scss']
})
export class SubscribeFormComponent {
  @Input() category = 'All';               // allow parent to set category
  @Input() buttonLabel = 'Get Alerts';     // allow custom button text
  @ViewChild('dlg') dlg!: ElementRef<HTMLDialogElement>;

  email = '';
  loading = false;
  message = '';

  constructor(private http: HttpClient) {}

  open()  { this.dlg.nativeElement.showModal(); }
  close() { this.dlg.nativeElement.close(); this.resetForm(); }

  subscribe() {
    if (!this.email) return;
    this.loading = true;
    this.message = '';

    const params = new HttpParams().set('email', this.email).set('category', this.category);

    this.http.get('/subscribe', { params, responseType: 'text' }).subscribe({
      next: () => {
        this.loading = false;
        this.message = 'You’re subscribed! Check your inbox.';
        // Close after a short beat so they see the success
        setTimeout(() => this.close(), 900);
      },
      error: () => {
        this.loading = false;
        this.message = 'Error subscribing. Please try again.';
      }
    });
  }

  private resetForm() {
    this.email = '';
    this.loading = false;
    this.message = '';
  }
}
