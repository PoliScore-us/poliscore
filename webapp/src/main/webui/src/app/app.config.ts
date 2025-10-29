import { provideHttpClient, withFetch, withInterceptorsFromDi } from '@angular/common/http';
import { ApplicationConfig } from '@angular/core';

export const sharedAppConfig: ApplicationConfig = {
  providers: [ provideHttpClient(withFetch(), withInterceptorsFromDi()) ]
};

export const backendUrl: string = "https://y5i3jhm7k5vy67elvzly4b3b240kjwlp.lambda-url.us-east-1.on.aws/";
export const year: number = 2026;
export const namespace: string = "us/congress";
