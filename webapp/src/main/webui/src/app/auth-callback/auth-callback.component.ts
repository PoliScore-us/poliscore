import { isPlatformBrowser } from '@angular/common';
import { Component, inject, OnInit, PLATFORM_ID } from '@angular/core';
import { Router } from '@angular/router';
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

  router = inject(Router)

  ngOnInit() {
    if (!isPlatformBrowser(this.platformId)) return; // Running on the server? Do nothing here.

    const ret = localStorage.getItem('ps:returnTo') || '/';
    localStorage.removeItem('ps:returnTo');
    this.router.navigateByUrl(ret, { replaceUrl: true });
  }
}
