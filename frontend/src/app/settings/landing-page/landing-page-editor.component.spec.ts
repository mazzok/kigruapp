import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { LandingPageEditorComponent } from './landing-page-editor.component';
import { LandingPageService } from '../../shared/services/landing-page.service';
import { NotificationService } from '../../shared/services/notification.service';

describe('LandingPageEditorComponent', () => {
  let fixture: ComponentFixture<LandingPageEditorComponent>;
  let component: LandingPageEditorComponent;
  let service: jasmine.SpyObj<LandingPageService>;
  let notify: jasmine.SpyObj<NotificationService>;

  beforeEach(() => {
    service = jasmine.createSpyObj('LandingPageService', ['get', 'save', 'placeholders', 'context']);
    notify = jasmine.createSpyObj('NotificationService', ['success', 'error', 'extractError']);
    service.get.and.returnValue(of({ bodyHtml: '<p>Hallo {{person.firstName}}</p>', updatedAt: null }));
    service.placeholders.and.returnValue(of([
      { token: '{{person.firstName}}', label: 'Vorname', group: 'person' },
    ]));
    service.context.and.returnValue(of({ '{{person.firstName}}': 'Anna' }));
    service.save.and.returnValue(of({ bodyHtml: '<p>x</p>', updatedAt: null }));

    TestBed.configureTestingModule({
      imports: [LandingPageEditorComponent, NoopAnimationsModule],
      providers: [
        { provide: LandingPageService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    });
    fixture = TestBed.createComponent(LandingPageEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('lädt den Inhalt und wandelt Tokens in Pillen', () => {
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('>Vorname<');
  });

  it('lädt die Platzhalter-Kacheln', () => {
    expect(component.placeholders.length).toBe(1);
    expect(component.placeholders[0].token).toBe('{{person.firstName}}');
  });

  it('speichert mit zurückgewandelten Tokens statt Pillen', () => {
    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Hallo {{person.firstName}}</p>');
  });

  it('meldet den Erfolg über den Notification-Service', () => {
    component.save();

    expect(notify.success).toHaveBeenCalled();
  });
});
