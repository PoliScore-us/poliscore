import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering } from '@angular/platform-server';
import { backendUrl, sharedAppConfig } from './app.config';
import { provideAnimations, provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withFetch, withInterceptorsFromDi } from '@angular/common/http';
import { provideAuth } from 'angular-auth-oidc-client';
import { makeAuthConfig } from './auth/auth.config';
import { provideClientHydration } from '@angular/platform-browser';
import { provideRouter, withDisabledInitialNavigation } from '@angular/router';
import { routes } from './app.routes';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

// TDOO : This backendUrl just seems wrong. Does it even matter for the server config? IDK
const redirectBase =
  backendUrl
  || 'https://d84l1y8p4kdic.cloudfront.net';

const serverOnlyConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withDisabledInitialNavigation()),
    provideServerRendering(),
  //   provideClientHydration(),
  //   provideNoopAnimations(),
    provideAuth({ config: makeAuthConfig(redirectBase, { server: true }) })
  ]
  // providers: [provideRouter(routes), provideAnimations(), provideAnimationsAsync(), provideClientHydration(), provideHttpClient(withFetch())]
};

export const serverConfig = mergeApplicationConfig(sharedAppConfig, serverOnlyConfig);
// export const serverConfig = serverOnlyConfig;

