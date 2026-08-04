import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { OAuthService } from 'angular-oauth2-oidc';
import { CurrentUserService } from './core/services/current-user.service';
import { HoursSummaryService } from './shared/services/hours-summary.service';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

describe('AppComponent', () => {
  let hoursSummaryStub: { summary$: unknown; reload: jasmine.Spy; clear: jasmine.Spy };

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

    hoursSummaryStub = {
      summary$: of(null),
      reload: jasmine.createSpy('reload'),
      clear: jasmine.createSpy('clear'),
    };

    await TestBed.configureTestingModule({
      imports: [AppComponent, NoopAnimationsModule],
      providers: [
        { provide: OAuthService, useValue: oauthSpy },
        { provide: CurrentUserService, useValue: currentUserSpy },
        { provide: HoursSummaryService, useValue: hoursSummaryStub },
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

  /** Gemeinsame Vorbereitung: angemeldeter Benutzer mit Familie, ngOnInit lauffähig. */
  function authenticatedFixture() {
    TestBed.overrideProvider(OAuthService, {
      useValue: {
        ...jasmine.createSpyObj('OAuthService', ['configure', 'setupAutomaticSilentRefresh']),
        hasValidAccessToken: () => true,
        getAccessToken: () => 'token',
        getIdentityClaims: () => ({ preferred_username: 'elternteil' }),
        loadDiscoveryDocumentAndTryLogin: () => Promise.resolve(true),
        events: of(),
      },
    });
    TestBed.overrideProvider(CurrentUserService, {
      useValue: { isAdmin: false, loadCurrentUser: () => of({ id: 'p1' }) },
    });

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('loads the hours summary once the current user is known', () => {
    authenticatedFixture();

    expect(hoursSummaryStub.reload).toHaveBeenCalledTimes(1);
  });

  it('renders the hours ring in the toolbar', () => {
    const fixture = authenticatedFixture();

    expect(fixture.nativeElement.querySelector('app-hours-ring')).not.toBeNull();
  });
});
