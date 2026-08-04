import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { LandingComponent } from './landing.component';
import { LandingPageService } from '../shared/services/landing-page.service';

describe('LandingComponent', () => {
  let fixture: ComponentFixture<LandingComponent>;
  let service: jasmine.SpyObj<LandingPageService>;

  function setup(): void {
    TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [{ provide: LandingPageService, useValue: service }],
    });
    fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('LandingPageService', ['get', 'context']);
  });

  it('zeigt den Inhalt mit ersetzten Tokens', () => {
    service.get.and.returnValue(of({ bodyHtml: '<p>Hallo {{person.firstName}}</p>', updatedAt: null }));
    service.context.and.returnValue(of({ '{{person.firstName}}': 'Anna' }));

    setup();

    expect(fixture.nativeElement.textContent).toContain('Hallo Anna');
  });

  it('zeigt den Leerzustand, wenn kein Inhalt gepflegt ist', () => {
    service.get.and.returnValue(of({ bodyHtml: '', updatedAt: null }));
    service.context.and.returnValue(of({}));

    setup();

    expect(fixture.componentInstance.isEmpty).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Noch keine Startseite');
  });

  it('zeigt den Inhalt auch dann, wenn der Kontext fehlschlägt', () => {
    service.get.and.returnValue(of({ bodyHtml: '<p>Fix {{person.firstName}}</p>', updatedAt: null }));
    service.context.and.returnValue(throwError(() => new Error('boom')));

    setup();

    expect(fixture.nativeElement.textContent).toContain('Fix');
    expect(fixture.nativeElement.textContent).toContain('–');
  });

  it('zeigt den Leerzustand, wenn der Inhalt nicht geladen werden kann', () => {
    service.get.and.returnValue(throwError(() => new Error('boom')));
    service.context.and.returnValue(of({}));

    setup();

    expect(fixture.componentInstance.isEmpty).toBeTrue();
  });
});
