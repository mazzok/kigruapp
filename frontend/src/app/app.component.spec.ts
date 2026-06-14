import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { OAuthService } from 'angular-oauth2-oidc';

describe('AppComponent', () => {
  beforeEach(async () => {
    const oauthSpy = jasmine.createSpyObj('OAuthService', [
      'configure', 'loadDiscoveryDocumentAndTryLogin', 'setupAutomaticSilentRefresh',
      'hasValidAccessToken', 'getAccessToken', 'getIdentityClaims',
    ]);
    oauthSpy.loadDiscoveryDocumentAndTryLogin.and.returnValue(Promise.resolve(true));

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        { provide: OAuthService, useValue: oauthSpy },
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });
});
