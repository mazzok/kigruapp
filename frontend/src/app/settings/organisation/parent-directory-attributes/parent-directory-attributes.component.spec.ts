import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ParentDirectoryAttributesComponent } from './parent-directory-attributes.component';
import { ParentDirectorySettingsService } from './parent-directory-settings.service';
import { NotificationService } from '../../../shared/services/notification.service';

describe('ParentDirectoryAttributesComponent', () => {
  let fixture: ComponentFixture<ParentDirectoryAttributesComponent>;
  let component: ParentDirectoryAttributesComponent;
  let service: jasmine.SpyObj<ParentDirectorySettingsService>;
  let notify: jasmine.SpyObj<NotificationService>;

  const catalog = {
    attributes: [
      { key: 'childName', label: 'Vorname', scope: 'CHILD' as const, selected: true, locked: true },
      { key: 'childEntryDate', label: 'Eintritt', scope: 'CHILD' as const, selected: false, locked: false },
      { key: 'firstName', label: 'Vorname', scope: 'PARENT' as const, selected: true, locked: false },
      { key: 'team', label: 'Team', scope: 'PARENT' as const, selected: false, locked: false },
      { key: 'address', label: 'Adresse', scope: 'FAMILY' as const, selected: true, locked: false },
    ],
  };

  async function setup(response = of(catalog)): Promise<void> {
    service = jasmine.createSpyObj<ParentDirectorySettingsService>(
      'ParentDirectorySettingsService', ['load', 'save']);
    service.load.and.returnValue(response);
    service.save.and.returnValue(of(void 0));
    notify = jasmine.createSpyObj<NotificationService>(
      'NotificationService', ['success', 'error', 'extractError']);
    notify.extractError.and.returnValue('Fehler');

    await TestBed.configureTestingModule({
      imports: [ParentDirectoryAttributesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: ParentDirectorySettingsService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ParentDirectoryAttributesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('gruppiert die Attribute nach Bereich', async () => {
    await setup();

    expect(component.childAttributes.map((a) => a.key)).toEqual(['childName', 'childEntryDate']);
    expect(component.parentAttributes.map((a) => a.key)).toEqual(['firstName', 'team']);
    expect(component.familyAttributes.map((a) => a.key)).toEqual(['address']);
  });

  it('sperrt childName gegen Abwahl', async () => {
    await setup();

    const boxes: HTMLInputElement[] =
      Array.from(fixture.nativeElement.querySelectorAll('input[type=checkbox]'));
    const locked = boxes.find((b) => b.id === 'attr-childName-input');

    expect(locked?.disabled).toBe(true);
    expect(locked?.checked).toBe(true);
  });

  it('speichert die ausgewaehlten Schluessel', async () => {
    await setup();

    component.toggle(component.parentAttributes[1], true);
    component.save();

    expect(service.save).toHaveBeenCalledWith(['childName', 'firstName', 'team', 'address']);
    expect(notify.success).toHaveBeenCalled();
  });

  it('meldet einen Fehler beim Speichern', async () => {
    await setup();
    service.save.and.returnValue(throwError(() => new Error('kaputt')));

    component.save();

    expect(notify.error).toHaveBeenCalledWith('Fehler');
  });
});
