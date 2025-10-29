import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering } from '@angular/platform-server';
import { backendUrl, sharedAppConfig } from './app.config';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideAuth } from 'angular-auth-oidc-client';
import { makeAuthConfig } from './auth/auth.config';

// For prerender, don't touch window. Use a known public base URL:
const redirectBase =
  backendUrl
  || 'https://d84l1y8p4kdic.cloudfront.net';

const serverOnlyConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(),
    provideNoopAnimations(),
    provideAuth({ config: makeAuthConfig(redirectBase, { server: true }) })
  ]
};

export const serverConfig = mergeApplicationConfig(sharedAppConfig, serverOnlyConfig);

