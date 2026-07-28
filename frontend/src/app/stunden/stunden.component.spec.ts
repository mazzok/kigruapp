import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { StundenComponent } from './stunden.component';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { NotificationService } from '../shared/services/notification.service';
import { HourEntry, RoleOption } from '../shared/models/hour-entry.model';

describe('StundenComponent', () => {
  let fixture: ComponentFixture<StundenComponent>;
  let component: StundenComponent;
  let service: jasmine.SpyObj<HourEntryService>;

  const entry: HourEntry = {
    id: 'e1', personId: 'p1', semesterId: 's1',
    roleFieldInstanceId: null, roleLabel: 'Kochen',
    date: '2026-10-05', minutes: 90, comment: 'Suppe',
  };
  const options: RoleOption[] = [
    { fieldInstanceId: 'r1', definitionId: 'd1', label: 'Gartenteam' },
    { fieldInstanceId: null, definitionId: null, label: 'Kochen' },
  ];

  beforeEach(async () => {
    service = jasmine.createSpyObj<HourEntryService>('HourEntryService',
      ['listMine', 'roleOptions', 'create', 'update', 'delete']);
    service.listMine.and.returnValue(of([entry]));
    service.roleOptions.and.returnValue(of(options));
    service.create.and.returnValue(of(entry));
    service.update.and.returnValue(of(entry));
    service.delete.and.returnValue(of(void 0));

    const notify = jasmine.createSpyObj<NotificationService>('NotificationService',
      ['success', 'error', 'extractError']);

    await TestBed.configureTestingModule({
      imports: [StundenComponent],
      providers: [
        provideNoopAnimations(),
        { provide: HourEntryService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the own entries on init', () => {
    expect(service.listMine).toHaveBeenCalled();
    expect(component.entries.length).toBe(1);
  });

  it('renders list shorthand as DD.MM.YYYY – Rolle', () => {
    expect(component.shorthand(entry)).toBe('05.10.2026 – Kochen');
  });

  it('opens an empty editor on newEntry()', () => {
    component.newEntry();
    expect(component.editing).toBeTrue();
    expect(component.selectedId).toBeNull();
  });

  it('saves a new entry via create() with parsed minutes and iso date', () => {
    component.newEntry();
    component.form.setValue({ roleKey: '__kochen__', date: new Date(2026, 9, 6), time: '01:00', comment: 'x' });
    component.save();
    expect(service.create).toHaveBeenCalledWith({
      roleFieldInstanceId: null, date: '2026-10-06', minutes: 60, comment: 'x',
    });
  });

  it('does not save when the time is invalid', () => {
    component.newEntry();
    component.form.setValue({ roleKey: '__kochen__', date: new Date(2026, 9, 6), time: '99:99', comment: '' });
    component.save();
    expect(service.create).not.toHaveBeenCalled();
  });
});
