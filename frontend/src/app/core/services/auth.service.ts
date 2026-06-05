import { Injectable } from '@angular/core';
import { OAuthService, AuthConfig } from 'angular-oauth2-oidc';

const authConfig: AuthConfig = {
  issuer: '/auth/realms/kigruapp',
  redirectUri: window.location.origin,
  clientId: 'kigruapp-frontend',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(private oauthService: OAuthService) {
    this.oauthService.configure(authConfig);
    this.oauthService.setupAutomaticSilentRefresh();
  }

  async login(): Promise<boolean> {
    await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    if (!this.oauthService.hasValidAccessToken()) {
      this.oauthService.initCodeFlow();
      return false;
    }
    return true;
  }

  logout(): void {
    this.oauthService.logOut();
  }

  get accessToken(): string {
    return this.oauthService.getAccessToken();
  }

  get isAuthenticated(): boolean {
    return this.oauthService.hasValidAccessToken();
  }

  get userName(): string {
    const claims = this.oauthService.getIdentityClaims() as any;
    return claims?.preferred_username ?? '';
  }
}
