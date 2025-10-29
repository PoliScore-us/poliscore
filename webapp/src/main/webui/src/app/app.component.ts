import { Component, inject, Inject, OnInit, PLATFORM_ID } from '@angular/core';
import { ChildrenOutletContexts, RouterOutlet } from '@angular/router';
import { slideInAnimation } from './animations';
import { Router, NavigationEnd } from '@angular/router';
import { isPlatformBrowser } from '@angular/common';
import { OidcSecurityService } from 'angular-auth-oidc-client';

declare let gtag: Function;

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  providers: [],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  animations: [
    slideInAnimation
  ]
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);

  constructor(public router: Router, private contexts: ChildrenOutletContexts, @Inject(PLATFORM_ID) private _platformId: Object){
    if (isPlatformBrowser(this._platformId)) {
      this.router.events.subscribe(event => {
        if(event instanceof NavigationEnd){
            gtag('config', 'G-7DY6Y1SM6W',
                  {
                    'page_path': event.urlAfterRedirects
                  }
                  );
          }
      });
     }
  }

  ngOnInit(): void {
    if (isPlatformBrowser(this._platformId)) {
      this.oidc.checkAuth().subscribe();
    }
  }

  getRouteAnimationData() {
    return this.contexts.getContext('primary')?.route?.snapshot?.data?.['animation'];
  }
}
