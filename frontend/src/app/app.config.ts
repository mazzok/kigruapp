import { ApplicationConfig, provideZoneChangeDetection, LOCALE_ID, importProvidersFrom, APP_INITIALIZER } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { CalendarModule, DateAdapter } from 'angular-calendar';
import { adapterFactory } from 'angular-calendar/date-adapters/date-fns';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { AuthService } from './core/services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
    { provide: LOCALE_ID, useValue: 'de-AT' },
    provideOAuthClient(),
    importProvidersFrom(
      CalendarModule.forRoot({ provide: DateAdapter, useFactory: adapterFactory })
    ),
    {
      provide: APP_INITIALIZER,
      useFactory: (auth: AuthService) => () => auth.configure(),
      deps: [AuthService],
      multi: true,
    },
  ],
};
