import { bootstrapApplication } from '@angular/platform-browser';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import localeDeAt from '@angular/common/locales/de-AT';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

registerLocaleData(localeDe, 'de');
registerLocaleData(localeDeAt, 'de-AT');

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
