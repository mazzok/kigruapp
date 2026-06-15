import { Injectable } from '@angular/core';
import { OAuthService, AuthConfig } from 'angular-oauth2-oidc';

const authConfig: AuthConfig = {
  issuer: 'http://localhost:8443/realms/kigruapp',
  redirectUri: window.location.origin + '/',
  clientId: 'kigruapp-frontend',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
  requireHttps: false,
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(private oauthService: OAuthService) {}

  async configure(): Promise<void> {
    this.oauthService.configure(authConfig);
    await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    this.oauthService.setupAutomaticSilentRefresh();
  }

  login(): void {
    this.oauthService.initCodeFlow();
  }

  logout(): void {
    this.oauthService.logOut();
  }

  get accessToken(): string {
    return this.oauthService.getAccessToken() ?? '';
  }

  get isAuthenticated(): boolean {
    return this.oauthService.hasValidAccessToken();
  }

  get userName(): string {
    const claims = this.oauthService.getIdentityClaims() as Record<string, string> | null;
    return claims?.['preferred_username'] ?? '';
  }

  get userEmail(): string {
    const claims = this.oauthService.getIdentityClaims() as Record<string, string> | null;
    return claims?.['email'] ?? '';
  }
}
