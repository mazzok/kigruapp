import { Injectable } from '@angular/core';

// TODO: Re-integrate angular-oauth2-oidc when upgrading to a compatible version
// For now, auth is stubbed to allow the app to load without Keycloak

@Injectable({ providedIn: 'root' })
export class AuthService {

  async login(): Promise<boolean> {
    return true;
  }

  logout(): void {}

  get accessToken(): string {
    return '';
  }

  get isAuthenticated(): boolean {
    return false;
  }

  get userName(): string {
    return '';
  }
}
