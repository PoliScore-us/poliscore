import { isPlatformBrowser } from '@angular/common';
import { Component, inject, OnInit, PLATFORM_ID } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [],
  templateUrl: './auth-callback.component.html',
  styleUrl: './auth-callback.component.scss'
})
export class AuthCallbackComponent implements OnInit {

  private oidc = inject(OidcSecurityService);
  private platformId = inject(PLATFORM_ID);

  ngOnInit() {
  if (!isPlatformBrowser(this.platformId)) {
      // Running on the server? Do nothing here.
      return;
    }

    const url = window.location.href;
    console.log("Auth callback initiaited");
    // this.oidc.checkAuth().subscribe(() => {
    //   const ret = localStorage.getItem('ps:returnTo') || '/';
    //   localStorage.removeItem('ps:returnTo');
    //   console.log("Redirecting to " + ret);
    //   location.replace(ret); // use full reload to land inside whichever sub-app
    // });

    this.oidc.checkAuth(url).subscribe({
      next: () => {
        setTimeout(() => { // TODO : This setTimeout really shouldn't be necessary
          const ret = localStorage.getItem('ps:returnTo') || '/';
          localStorage.removeItem('ps:returnTo');
          console.log('[auth-callback] checkAuth OK → redirecting to', ret);
          location.replace(ret); // hard reload so interceptors/token state apply everywhere
        }, 1000)
      },
      error: (err) => {
        console.error('[auth-callback] checkAuth FAILED:', err);
        // While debugging: avoid a missing route
        // You can change this to '/signup' or another safe page.
        location.replace('/'); 
      }
    });
  }
  
}
