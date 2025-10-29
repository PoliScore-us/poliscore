import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { clientConfig } from './app/app.config.client';

bootstrapApplication(AppComponent, clientConfig)
  .catch((err) => console.error(err));

