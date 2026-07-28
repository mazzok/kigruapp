import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { StundenuebersichtComponent } from './stundenuebersicht.component';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';
import { HourEntry, HourSummary } from '../../shared/models/hour-entry.model';

describe('StundenuebersichtComponent', () => {
  let fixture: ComponentFixture<StundenuebersichtComponent>;
  let component: StundenuebersichtComponent;
  let hourService: jasmine.SpyObj<HourEntryService>;

  const entry: HourEntry = {
    id: 'e1', personId: 'p1', semesterId: 's1',
    roleFieldInstanceId: null, roleLabel: 'Kochen',
    date: '2026-10-05', minutes: 90, comment: '',
  };
  const summary: HourSummary[] = [
    { personId: 'p1', name: 'Anna Muster', totalMinutes: 90, entries: [entry] },
  ];

  beforeEach(async () => {
    hourService = jasmine.createSpyObj<HourEntryService>('HourEntryService',
      ['summary', 'update', 'delete']);
    hourService.summary.and.returnValue(of(summary));
    hourService.update.and.returnValue(of(entry));
    hourService.delete.and.returnValue(of(void 0));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll']);
    semesterService.getAll.and.returnValue(of([
      { id: 's1', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z' } as any,
    ]));

    const notify = jasmine.createSpyObj<NotificationService>('NotificationService',
      ['success', 'error', 'extractError']);

    await TestBed.configureTestingModule({
      imports: [StundenuebersichtComponent],
      providers: [
        provideNoopAnimations(),
        { provide: HourEntryService, useValue: hourService },
        { provide: SemesterService, useValue: semesterService },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenuebersichtComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the summary for the default (newest) semester', () => {
    expect(component.selectedSemesterId).toBe('s1');
    expect(hourService.summary).toHaveBeenCalledWith('s1');
    expect(component.summaries.length).toBe(1);
  });

  it('formats the person total as HH:MM', () => {
    expect(component.formatMinutes(component.summaries[0].totalMinutes)).toBe('01:30');
  });

  it('updates an entry keeping its role and reloads', () => {
    component.startEdit(entry);
    component.editForm.setValue({ date: new Date(2026, 9, 6), time: '00:30', comment: 'fix' });
    component.saveEdit(entry);
    expect(hourService.update).toHaveBeenCalledWith('e1', {
      roleFieldInstanceId: null, date: '2026-10-06', minutes: 30, comment: 'fix',
    });
    expect(hourService.summary).toHaveBeenCalledTimes(2); // init + nach Update
  });
});
