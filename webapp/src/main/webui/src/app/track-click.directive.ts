// track-click.directive.ts
import { Directive, HostListener, Input } from '@angular/core';
import { AppService } from './app.service';
import { AnalyticsService } from './service/analytics.service';

@Directive({
  selector: '[psTrackClick]',
  standalone: true
})
export class TrackClickDirective {
  private _eventName = 'button_click';

  @Input('psTrackClick')
  set psTrackClick(value: string | null | undefined) {
    // If attribute is present with no value, Angular passes ""
    if (value == null || value === '') {
      this._eventName = 'button_click';
    } else {
      this._eventName = value;
    }
  }

  @Input() psTrackClickId?: string;
  @Input() psTrackClickExtra?: Record<string, any>;

  constructor(private service: AnalyticsService) {}

  @HostListener('click')
  onClick() {
    this.fireEvent('click');
  }

  @HostListener('keydown.enter', ['$event'])
  onEnter(event: KeyboardEvent) {
    this.fireEvent('enter');
  }

  private fireEvent(trigger: 'click' | 'enter') {
    this.service.trackEvent(this._eventName, {
      button_id: this.psTrackClickId,
      trigger,
      ...this.psTrackClickExtra
    });
  }
}
