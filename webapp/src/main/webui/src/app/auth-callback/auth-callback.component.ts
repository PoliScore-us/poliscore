import { Component, inject, OnInit } from '@angular/core';
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

  ngOnInit() {
    this.oidc.checkAuth().subscribe(() => {
      const ret = localStorage.getItem('ps:returnTo') || '/';
      localStorage.removeItem('ps:returnTo');
      location.replace(ret); // use full reload to land inside whichever sub-app
    });
  }
  
}
