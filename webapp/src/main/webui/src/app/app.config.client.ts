import { ApplicationConfig } from '@angular/core';
import { provideClientHydration } from '@angular/platform-browser';

export const appClientConfig: ApplicationConfig = {
  providers: [provideClientHydration()]
};
