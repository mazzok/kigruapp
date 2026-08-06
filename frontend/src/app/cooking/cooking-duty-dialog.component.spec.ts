import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNativeDateAdapter } from '@angular/material/core';
import {
  CookingDutyDialogComponent,
  CookingDutyDialogData,
} from './cooking-duty-dialog.component';

describe('CookingDutyDialogComponent — Erinnerung', () => {
  let fixture: ComponentFixture<CookingDutyDialogComponent>;
  let component: CookingDutyDialogComponent;

  const baseData: CookingDutyDialogData = {
    groups: [{ id: 'g1', fieldName: 'group', label: { de: 'Gruppe 1' }, jsonSchema: {}, required: false }],
    foodProperties: [],
    familyParents: [{ id: 'p1', firstName: 'Anna', lastName: 'Muster' }],
    currentUserId: 'p1',
    canEdit: true,
    reminderAvailable: true,
    closurePeriods: [],
    closureDefinitions: [],
    holidays: [],
    calendarFrom: '2026-09-01',
    calendarTo: '2026-09-30',
  };

  async function createComponent(data: CookingDutyDialogData): Promise<void> {
    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CookingDutyDialogComponent, NoopAnimationsModule],
      providers: [
        provideNativeDateAdapter(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingDutyDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('zeigt die Checkbox nur bei aktiver Erinnerungsfunktion', async () => {
    await createComponent({ ...baseData, reminderAvailable: false });

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-toggle"]')).toBeNull();

    await createComponent(baseData);

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-toggle"]')).not.toBeNull();
  });

  it('blendet das Tage-Feld erst nach dem Anhaken ein, vorbelegt mit 3', async () => {
    await createComponent(baseData);

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-days"]')).toBeNull();

    component.form.patchValue({ reminderEnabled: true });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-days"]')).not.toBeNull();
    expect(component.form.value.reminderDaysBefore).toBe(3);
  });

  it('erzwingt die Grenzen 1 und 14', async () => {
    await createComponent(baseData);
    component.form.patchValue({ reminderEnabled: true });

    component.form.patchValue({ reminderDaysBefore: 0 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 15 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 14 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeTrue();
  });

  it('gibt die Vorlaufzeit-Validierung nach dem Abhaken frei', async () => {
    await createComponent(baseData);
    component.form.patchValue({ reminderEnabled: true, reminderDaysBefore: 99 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderEnabled: false });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeTrue();

    const inTenDays = new Date();
    inTenDays.setDate(inTenDays.getDate() + 10);
    component.form.patchValue({ date: inTenDays, person: 'p1', group_g1: true });
    expect(component.form.valid).toBeTrue();

    component.save();

    const dialogRef = TestBed.inject(MatDialogRef) as unknown as jasmine.SpyObj<MatDialogRef<CookingDutyDialogComponent>>;
    const result = dialogRef.close.calls.mostRecent().args[0];
    expect(result.reminderEnabled).toBeFalse();
    expect(result.reminderDaysBefore).toBeNull();
  });

  it('erzwingt die Grenzen 1 und 14 weiterhin, solange die Erinnerung angehakt bleibt', async () => {
    await createComponent(baseData);
    component.form.patchValue({ reminderEnabled: true });

    component.form.patchValue({ reminderDaysBefore: 0 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();
    expect(component.form.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 15 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 1 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeTrue();
  });

  it('berechnet das Erinnerungsdatum aus Dienstdatum und Vorlaufzeit', async () => {
    await createComponent(baseData);
    const inTwentyDays = new Date();
    inTwentyDays.setDate(inTwentyDays.getDate() + 20);

    component.form.patchValue({ date: inTwentyDays, reminderEnabled: true, reminderDaysBefore: 5 });

    const expected = new Date(inTwentyDays);
    expected.setDate(expected.getDate() - 5);
    expect(component.reminderDate).toContain(String(expected.getFullYear()));
    expect(component.reminderInPast).toBeFalse();
  });

  it('warnt, wenn das Erinnerungsdatum in der Vergangenheit liegt', async () => {
    await createComponent(baseData);
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);

    component.form.patchValue({ date: tomorrow, reminderEnabled: true, reminderDaysBefore: 5 });
    fixture.detectChanges();

    expect(component.reminderInPast).toBeTrue();
    expect(fixture.nativeElement.querySelector('[data-testid="reminder-warning"]')).not.toBeNull();
  });

  it('uebernimmt gespeicherte Werte beim Bearbeiten', async () => {
    await createComponent({
      ...baseData,
      existingDuty: {
        id: 'd1', personId: 'p1', familyId: 'f1', personName: 'Anna',
        date: '2026-09-15', groups: [], description: '', foodProperties: {},
        reminderEnabled: true, reminderDaysBefore: 7,
      },
    });

    expect(component.form.value.reminderEnabled).toBeTrue();
    expect(component.form.value.reminderDaysBefore).toBe(7);
  });

  it('liefert die Erinnerungswerte im Ergebnis', async () => {
    await createComponent(baseData);
    const inTenDays = new Date();
    inTenDays.setDate(inTenDays.getDate() + 10);
    component.form.patchValue({
      date: inTenDays, person: 'p1', reminderEnabled: true, reminderDaysBefore: 4,
      group_g1: true,
    });

    component.save();

    const dialogRef = TestBed.inject(MatDialogRef) as unknown as jasmine.SpyObj<MatDialogRef<CookingDutyDialogComponent>>;
    expect(dialogRef.close).toHaveBeenCalled();
    const result = dialogRef.close.calls.mostRecent().args[0];
    expect(result.reminderEnabled).toBeTrue();
    expect(result.reminderDaysBefore).toBe(4);
  });

  describe('Wer kocht', () => {
    it('zeigt Vor- und Nachname der Familienmitglieder an', async () => {
      await createComponent(baseData);
      fixture.detectChanges();

      const option = fixture.nativeElement.textContent as string;
      expect(component.getParentName(baseData.familyParents[0])).toBe('Muster Anna');
      expect(option).toContain('Muster Anna');
    });

    it('zeigt keine Namen an, wenn firstName/lastName fehlen', async () => {
      await createComponent({ ...baseData, familyParents: [{ id: 'p2', firstName: null, lastName: null }] });

      expect(component.getParentName({ id: 'p2', firstName: null, lastName: null })).toBe('');
    });
  });

  describe('Datumsauswahl ueber closure-calendar', () => {
    it('setzt das Datum-Formularfeld bei Auswahl im Kalender', async () => {
      await createComponent(baseData);

      component.onDateSelected(['2026-09-15']);

      expect(component.form.value.date).toEqual(new Date('2026-09-15T00:00:00'));
    });

    it('leert das Datum-Formularfeld, wenn die Auswahl aufgehoben wird', async () => {
      await createComponent(baseData);
      component.onDateSelected(['2026-09-15']);

      component.onDateSelected([]);

      expect(component.form.value.date).toBeNull();
    });

    it('setzt die initiale Kalenderauswahl beim Bearbeiten auf das bestehende Datum', async () => {
      await createComponent({
        ...baseData,
        existingDuty: {
          id: 'd1', personId: 'p1', familyId: 'f1', personName: 'Anna',
          date: '2026-09-15', groups: [], description: '', foodProperties: {},
          reminderEnabled: false, reminderDaysBefore: null,
        },
      });

      expect(component.initialDateSelection).toEqual(['2026-09-15']);
    });

    it('hat keine initiale Kalenderauswahl beim Neuanlegen', async () => {
      await createComponent(baseData);

      expect(component.initialDateSelection).toEqual([]);
    });
  });
});
