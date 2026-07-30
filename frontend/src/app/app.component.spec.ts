import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { OAuthService } from 'angular-oauth2-oidc';
import { CurrentUserService } from './core/services/current-user.service';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

describe('AppComponent', () => {
  beforeEach(async () => {
    const oauthSpy = jasmine.createSpyObj('OAuthService', [
      'configure', 'loadDiscoveryDocumentAndTryLogin', 'setupAutomaticSilentRefresh',
      'hasValidAccessToken', 'getAccessToken', 'getIdentityClaims',
    ]);
    oauthSpy.loadDiscoveryDocumentAndTryLogin.and.returnValue(Promise.resolve(true));

    const currentUserSpy = jasmine.createSpyObj('CurrentUserService', [
      'loadCurrentUser',
    ]);
    currentUserSpy.loadCurrentUser.and.returnValue({ subscribe: () => {} });

    await TestBed.configureTestingModule({
      imports: [AppComponent, NoopAnimationsModule],
      providers: [
        { provide: OAuthService, useValue: oauthSpy },
        { provide: CurrentUserService, useValue: currentUserSpy },
        provideRouter([]),
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should default the admin section to expanded', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.adminSectionExpanded).toBeTrue();
  });

  it('should toggle the admin section state', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;

    app.toggleAdminSection();
    expect(app.adminSectionExpanded).toBeFalse();

    app.toggleAdminSection();
    expect(app.adminSectionExpanded).toBeTrue();
  });

  it('hides admin links when the section is collapsed', () => {
    TestBed.overrideProvider(CurrentUserService, {
      useValue: { isAdmin: true, loadCurrentUser: () => of(null) },
    });
    TestBed.overrideProvider(OAuthService, {
      useValue: { ...jasmine.createSpyObj('OAuthService', ['hasValidAccessToken', 'getAccessToken', 'getIdentityClaims']), events: of() },
    });

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('a[href^="/administration"]').length).toBeGreaterThan(0);

    fixture.componentInstance.toggleAdminSection();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('a[href^="/administration"]').length).toBe(0);
  });
});
