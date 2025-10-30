import { provideHttpClient, withFetch, withInterceptorsFromDi } from '@angular/common/http';
import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideClientHydration } from '@angular/platform-browser';

export const sharedAppConfig: ApplicationConfig = {
  // providers: [ provideHttpClient(withFetch(), withInterceptorsFromDi()) ]
  providers: [provideRouter(routes), provideAnimations(), provideAnimationsAsync(), provideClientHydration(), provideHttpClient(withFetch())]
};

export const backendUrl: string = "https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws/";
export const year: number = 2025;
export const namespace: string = "us/co";
