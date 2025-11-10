import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideClientHydration } from '@angular/platform-browser';
import { authInterceptor } from './auth/auth.interceptor';
import { entitlementInterceptor } from './billing/entitlement.interceptor';
import { environment } from '../environments/environment';

export const sharedAppConfig: ApplicationConfig = {
  // providers: [ provideHttpClient(withFetch(), withInterceptorsFromDi()) ]
  providers: [
    provideAnimations(),
    provideClientHydration(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor, entitlementInterceptor])),
  ]
};

export const backendUrl: string = environment.apiUrl;
export const year: number = 2025;
export const namespace: string = "us/co";
