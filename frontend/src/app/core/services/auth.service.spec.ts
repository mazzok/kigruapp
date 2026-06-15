import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let oauthSpy: jasmine.SpyObj<OAuthService>;

  beforeEach(() => {
    oauthSpy = jasmine.createSpyObj('OAuthService', [
      'configure',
      'loadDiscoveryDocumentAndTryLogin',
      'setupAutomaticSilentRefresh',
      'initCodeFlow',
      'logOut',
      'getAccessToken',
      'hasValidAccessToken',
      'getIdentityClaims',
    ]);
    oauthSpy.loadDiscoveryDocumentAndTryLogin.and.returnValue(Promise.resolve(true));

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        { provide: OAuthService, useValue: oauthSpy },
      ],
    });
    service = TestBed.inject(AuthService);
  });

  it('configure() calls OAuthService.configure and loads discovery', async () => {
    await service.configure();
    expect(oauthSpy.configure).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      clientId: 'kigruapp-frontend',
      responseType: 'code',
    }));
    expect(oauthSpy.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalled();
  });

  it('isAuthenticated delegates to hasValidAccessToken', () => {
    oauthSpy.hasValidAccessToken.and.returnValue(true);
    expect(service.isAuthenticated).toBeTrue();
  });

  it('accessToken delegates to getAccessToken', () => {
    oauthSpy.getAccessToken.and.returnValue('token123');
    expect(service.accessToken).toBe('token123');
  });

  it('userName reads preferred_username from claims', () => {
    oauthSpy.getIdentityClaims.and.returnValue({ preferred_username: 'testuser' } as object);
    expect(service.userName).toBe('testuser');
  });

  it('userName returns empty string when no claims', () => {
    oauthSpy.getIdentityClaims.and.returnValue(null as unknown as object);
    expect(service.userName).toBe('');
  });
});
