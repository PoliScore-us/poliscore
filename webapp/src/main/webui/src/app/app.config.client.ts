import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { provideClientHydration } from '@angular/platform-browser';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { sharedAppConfig } from './app.config';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideHttpClient, withFetch, withInterceptorsFromDi } from '@angular/common/http';
import { AbstractSecurityStorage, DefaultLocalStorageService, provideAuth } from 'angular-auth-oidc-client';
import { makeAuthConfig } from './auth/auth.config';

// Use window.origin ONLY in browser
const redirectBase =
  (typeof window !== 'undefined' && window.location?.origin + "/auth-callback") || 'http://localhost:4200/auth-callback';

export const clientOnlyConfig: ApplicationConfig = {
  providers: [
    provideClientHydration(), provideAnimations(), provideAnimationsAsync(),
    provideRouter(routes),                // animations OK on client
    provideAuth({ config: makeAuthConfig(redirectBase)}), // OIDC on client
    { provide: AbstractSecurityStorage, useClass: DefaultLocalStorageService }
  ]
};

export const clientConfig = mergeApplicationConfig(sharedAppConfig, clientOnlyConfig);
