import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { appClientConfig } from './app/app.config.client';
import { mergeApplicationConfig } from '@angular/core';

bootstrapApplication(AppComponent, mergeApplicationConfig(appConfig, appClientConfig))
  .catch((err) => console.error(err));

