import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { StundenuebersichtComponent } from './stundenuebersicht.component';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';
import { FamilyHoursSummary, HourEntry } from '../../shared/models/hour-entry.model';

describe('StundenuebersichtComponent', () => {
  let fixture: ComponentFixture<StundenuebersichtComponent>;
  let component: StundenuebersichtComponent;
  let hourService: jasmine.SpyObj<HourEntryService>;

  const entry: HourEntry = {
    id: 'e1', personId: 'p1', semesterId: 's1',
    roleFieldInstanceId: null, roleLabel: 'Kochen',
    date: '2026-10-05', minutes: 90, comment: '',
  };
  const families: FamilyHoursSummary[] = [
    {
      familyId: 'f1', familyName: 'Muster', childCount: 2, familyMonthlyMinutes: 840,
      monthsInSemester: 6, sollMinutes: 5040, istMinutes: 90,
      members: [{ personId: 'p1', name: 'Anna Muster', totalMinutes: 90, entries: [entry] }],
    },
  ];

  beforeEach(async () => {
    hourService = jasmine.createSpyObj<HourEntryService>('HourEntryService',
      ['familySummary', 'update', 'delete']);
    hourService.familySummary.and.returnValue(of(families));
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

  it('loads family summaries for the default (newest) semester', () => {
    expect(component.selectedSemesterId).toBe('s1');
    expect(hourService.familySummary).toHaveBeenCalledWith('s1');
    expect(component.families.length).toBe(1);
  });

  it('marks a family with istMinutes below sollMinutes as negative', () => {
    expect(component.isNegative(component.families[0])).toBeTrue(); // 90 < 5040
  });

  it('does not mark a family with istMinutes at or above sollMinutes as negative', () => {
    const positive: FamilyHoursSummary = { ...families[0], istMinutes: 6000 };
    expect(component.isNegative(positive)).toBeFalse();
  });

  it('toggles family expansion', () => {
    expect(component.expandedFamilyId).toBeNull();
    component.toggleFamily('f1');
    expect(component.expandedFamilyId).toBe('f1');
    component.toggleFamily('f1');
    expect(component.expandedFamilyId).toBeNull();
  });

  it('builds a balance tooltip with the family breakdown', () => {
    const tooltip = component.balanceTooltip(component.families[0]);
    expect(tooltip).toContain('2 Kinder');
    expect(tooltip).toContain('14:00/Monat');
    expect(tooltip).toContain('6 Monate');
  });

  it('nennt den Monatswert nur, wenn es einen vollen Monat gibt', () => {
    const withMonthly = component.balanceTooltip({
      ...families[0], childCount: 2, familyMonthlyMinutes: 705, monthsInSemester: 8, sollMinutes: 5640,
    });
    expect(withMonthly).toContain('11:45');

    const withoutMonthly = component.balanceTooltip({
      ...families[0], childCount: 2, familyMonthlyMinutes: 0, monthsInSemester: 8, sollMinutes: 5000,
    });
    expect(withoutMonthly).not.toContain('/Monat');
    expect(withoutMonthly).toContain('2 Kinder');
  });

  it('formats a member total as HH:MM', () => {
    expect(component.formatMinutes(component.families[0].members[0].totalMinutes)).toBe('01:30');
  });

  it('updates an entry keeping its role and reloads families', () => {
    component.startEdit(entry);
    component.editForm.setValue({ date: new Date(2026, 9, 6), time: '00:30', comment: 'fix' });
    component.saveEdit(entry);
    expect(hourService.update).toHaveBeenCalledWith('e1', {
      roleFieldInstanceId: null, date: '2026-10-06', minutes: 30, comment: 'fix',
    });
    expect(hourService.familySummary).toHaveBeenCalledTimes(2); // init + nach Update
  });

  it('deletes an entry and reloads families', () => {
    component.delete(entry);
    expect(hourService.delete).toHaveBeenCalledWith('e1');
    expect(hourService.familySummary).toHaveBeenCalledTimes(2); // init + nach Delete
  });
});
