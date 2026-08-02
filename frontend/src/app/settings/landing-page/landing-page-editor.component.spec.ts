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

  it('zeigt im Quelltextmodus rohe Tokens statt Pillen', () => {
    component.toggleSourceMode();

    expect(component.sourceMode).toBeTrue();
    expect(component.sourceHtml).toBe('<p>Hallo {{person.firstName}}</p>');
  });

  it('übernimmt Änderungen aus dem Quelltext zurück in den Editor', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Neu {{person.firstName}}</p>';
    component.toggleSourceMode();

    expect(component.sourceMode).toBeFalse();
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('Neu');
  });

  it('speichert den im Quelltext bearbeiteten Inhalt korrekt', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Aus dem Quelltext</p>';
    component.toggleSourceMode();

    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Aus dem Quelltext</p>');
  });

  it('speichert auch dann korrekt, wenn der Quelltextmodus noch aktiv ist', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Direkt gespeichert</p>';

    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Direkt gespeichert</p>');
  });

  it('gruppiert die Kacheln nach Familie mit deutscher Überschrift', () => {
    expect(component.groupedPlaceholders.length).toBe(1);
    expect(component.groupedPlaceholders[0].group).toBe('person');
    expect(component.groupedPlaceholders[0].label).toBe('Person');
    expect(component.groupedPlaceholders[0].tiles.length).toBe(1);
  });

  it('hängt einen Platzhalter an, wenn noch kein Quill-Editor existiert', () => {
    component.quillInstance = null;
    component.form.patchValue({ bodyHtml: '<p>Text</p>' });

    component.insertPlaceholder({ token: '{{person.firstName}}', label: 'Vorname', group: 'person' });

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  });

  it('fügt einen Platzhalter an der Cursorposition ein', () => {
    const insertEmbed = jasmine.createSpy('insertEmbed');
    component.quillInstance = {
      insertEmbed,
      setSelection: jasmine.createSpy('setSelection'),
      getSelection: () => ({ index: 3, length: 0 }),
      getLength: () => 10,
      root: { innerHTML: '<p>x</p>' },
    };

    component.insertPlaceholder({ token: '{{stunden.bilanz}}', label: 'Bilanz', group: 'stunden' });

    expect(insertEmbed).toHaveBeenCalledWith(3, 'mail-token', {
      token: '{{stunden.bilanz}}',
      label: 'Bilanz',
    });
  });

  it('legt beim Drag den Token in die DataTransfer-Nutzlast', () => {
    const setData = jasmine.createSpy('setData');
    const event = { dataTransfer: { setData, effectAllowed: '' } } as unknown as DragEvent;

    component.onChipDragStart(event, { token: '{{person.firstName}}', label: 'Vorname', group: 'person' });

    expect(setData).toHaveBeenCalledWith('application/x-landing-token', '{{person.firstName}}');
  });

  it('ignoriert einen Drop ohne passenden Token', () => {
    const insertEmbed = jasmine.createSpy('insertEmbed');
    component.quillInstance = { insertEmbed, setSelection: () => {}, getLength: () => 1, root: { innerHTML: '' } };
    const event = {
      dataTransfer: { getData: () => '{{unbekannt.feld}}' },
      preventDefault: () => {},
      clientX: 0,
      clientY: 0,
    } as unknown as DragEvent;

    component.onEditorDrop(event);

    expect(insertEmbed).not.toHaveBeenCalled();
  });
});
