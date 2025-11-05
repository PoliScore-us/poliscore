import { Component, inject } from '@angular/core';
import { PurchaseFlowService } from './purchase-flow.service';

@Component({
  standalone: true,
  selector: 'app-purchase-resume',
  template: '' // no UI; it just resumes
})
export class PurchaseResumeComponent {
  private flow = inject(PurchaseFlowService);
  async ngOnInit() {
    await this.flow.resumeAfterLogin();
  }
}
