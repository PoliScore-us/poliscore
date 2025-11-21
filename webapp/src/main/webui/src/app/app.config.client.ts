import { APP_INITIALIZER, ApplicationConfig, mergeApplicationConfig, Provider } from '@angular/core';
import { provideClientHydration } from '@angular/platform-browser';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { sharedAppConfig } from './app.config';
import { routes } from './app.routes';
import { provideHttpClient, withFetch, withInterceptorsFromDi } from '@angular/common/http';
import { AbstractSecurityStorage, DefaultLocalStorageService, OidcSecurityService, provideAuth, withAppInitializerAuthCheck } from 'angular-auth-oidc-client';
import { makeAuthConfig } from './auth/auth.config';
import { environment } from '../environments/environment';

// Use window.origin ONLY in browser
const redirectBase = environment.baseUrl;

function initAuth(oidc: OidcSecurityService) {
  // Handles both normal loads and /auth-callback (it auto-detects the code in URL)
  return () => oidc.checkAuth().toPromise();
}

export const AUTH_INIT: Provider = {
  provide: APP_INITIALIZER,
  useFactory: initAuth,
  deps: [OidcSecurityService],
  multi: true,
};


export const clientOnlyConfig: ApplicationConfig = {
  providers: [
    AUTH_INIT,
  //   provideClientHydration(), provideAnimations(), provideAnimationsAsync(),
  //   provideRouter(routes),                // animations OK on client
    provideAuth({ config: makeAuthConfig(redirectBase)}), // withAppInitializerAuthCheck()
  //   { provide: AbstractSecurityStorage, useClass: DefaultLocalStorageService }
  ]
  // providers: [provideRouter(routes), provideAnimations(), provideAnimationsAsync(), provideClientHydration(), provideHttpClient(withFetch())]
};

export const clientConfig = mergeApplicationConfig(sharedAppConfig, clientOnlyConfig);
// export const clientConfig = clientOnlyConfig;
