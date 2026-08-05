import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNativeDateAdapter } from '@angular/material/core';

import { CookingDutyDialogComponent, CookingDutyDialogData } from './cooking-duty-dialog.component';
import { closedDatesFrom } from './cooking-closure.util';

describe('Kochdienst und Schließtage', () => {
  describe('closedDatesFrom', () => {
    it('expandiert Zeitraeume auf einzelne Tage', () => {
      const closed = closedDatesFrom(
        [{ id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def' }], []);

      expect([...closed].sort()).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
    });

    it('nimmt Feiertage mit auf', () => {
      const closed = closedDatesFrom([], [{ date: '2026-10-26', name: 'Nationalfeiertag' }]);

      expect(closed.has('2026-10-26')).toBe(true);
    });

    it('bleibt bei leeren Eingaben leer', () => {
      expect(closedDatesFrom([], []).size).toBe(0);
    });
  });

  describe('CookingDutyDialogComponent', () => {
    function createDialog(closedDates: string[]): CookingDutyDialogComponent {
      const data: CookingDutyDialogData = {
        groups: [], foodProperties: [], familyParents: [],
        currentUserId: 'p1', canEdit: true, closedDates, reminderAvailable: true,
      };
      TestBed.configureTestingModule({
        imports: [CookingDutyDialogComponent, NoopAnimationsModule],
        providers: [
          // Im echten Betrieb kommt der Adapter aus app.config; der TestBed hat ihn nicht.
          provideNativeDateAdapter(),
          { provide: MatDialogRef, useValue: { close: () => undefined } },
          { provide: MAT_DIALOG_DATA, useValue: data },
        ],
      });
      const fixture = TestBed.createComponent(CookingDutyDialogComponent);
      fixture.detectChanges();
      return fixture.componentInstance;
    }

    it('sperrt geschlossene Tage im Datepicker', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(new Date(2026, 8, 8))).toBe(false);
    });

    it('laesst offene Tage zu', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(new Date(2026, 8, 9))).toBe(true);
    });

    it('laesst null durch, damit das Feld leer bleiben kann', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(null)).toBe(true);
    });
  });
});
