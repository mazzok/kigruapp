import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { HoursEntryDialogComponent, HoursEntryDialogData } from './hours-entry-dialog.component';
import { HourEntry, RoleOption } from '../../shared/models/hour-entry.model';

describe('HoursEntryDialogComponent', () => {
  let fixture: ComponentFixture<HoursEntryDialogComponent>;
  const dialogRef = { close: jasmine.createSpy('close') };

  const options: RoleOption[] = [
    { fieldInstanceId: 'r1', definitionId: 'd1', label: 'Garten' },
    { fieldInstanceId: null, definitionId: null, label: 'Kochen' },
  ];

  async function setup(data: HoursEntryDialogData) {
    dialogRef.close.calls.reset();
    await TestBed.resetTestingModule().configureTestingModule({
      imports: [HoursEntryDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HoursEntryDialogComponent);
    fixture.detectChanges();
  }

  it('startet leer beim Anlegen', async () => {
    await setup({ entry: null, options });
    expect(fixture.componentInstance.form.value.time).toBe('');
  });

  it('übernimmt die Werte beim Bearbeiten', async () => {
    const entry: HourEntry = {
      id: '1', personId: 'p1', semesterId: 's1', roleFieldInstanceId: 'r1',
      roleLabel: 'Garten', date: '2026-10-12', minutes: 180, comment: 'Hecke',
    };
    await setup({ entry, options });

    expect(fixture.componentInstance.form.value.time).toBe('03:00');
    expect(fixture.componentInstance.form.value.comment).toBe('Hecke');
  });

  it('schließt mit dem Request, wenn das Formular gültig ist', async () => {
    await setup({ entry: null, options });
    fixture.componentInstance.form.setValue({
      roleKey: 'r1', date: new Date(2026, 9, 12), time: '02:30', comment: '',
    });

    fixture.componentInstance.save();

    expect(dialogRef.close).toHaveBeenCalledWith({
      roleFieldInstanceId: 'r1', date: '2026-10-12', minutes: 150, comment: '',
    });
  });

  it('schließt nicht bei ungültiger Dauer', async () => {
    await setup({ entry: null, options });
    fixture.componentInstance.form.setValue({
      roleKey: 'r1', date: new Date(2026, 9, 12), time: 'abc', comment: '',
    });

    fixture.componentInstance.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });
});
