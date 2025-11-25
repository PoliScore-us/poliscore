import { isPlatformBrowser } from "@angular/common";
import { Inject, Injectable, PLATFORM_ID } from "@angular/core";
import { Title } from "@angular/platform-browser";

@Injectable({ providedIn: 'root' })
export class AnalyticsService {

    constructor(@Inject(PLATFORM_ID) private _platformId: Object, private titleService: Title) { }

    fireGAPageView(): void {
        if (!isPlatformBrowser(this._platformId) || typeof window === 'undefined') { return; } // Don’t run on the server

        const w = window as any;
        if (!w.gtag) { return; } // GA script not loaded or blocked

        const page_title = this.titleService.getTitle();
        const page_path = w.location.pathname + w.location.search + w.location.hash;
        const page_location = w.location.origin + page_path;

        w.gtag('event', 'page_view', {
            page_title,
            page_path,
            page_location
        });
    }

    trackEvent(
        name: string,
        params: Record<string, any> = {}
    ) {
        if (!isPlatformBrowser(this._platformId) || typeof window === 'undefined') { return; } // Don’t run on the server

        const w = window as any;
        if (!w.gtag) { return; } // GA script not loaded or blocked

        const eventName = (name && name.trim()) || 'undefined';
        
        w.gtag('event', eventName, params);
    }

    trackButtonClick(
        buttonId: string,
        extra: Record<string, any> = {}
    ) {
        this.trackEvent('button_click', {
            button_id: buttonId,
            ...extra,
        });
    }
}