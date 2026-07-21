import { of } from 'rxjs';
import { BilanzenComponent } from './bilanzen.component';
import { BilanzService } from '../../shared/services/bilanz.service';
import { SemesterService } from '../../shared/services/semester.service';
import { MatDialog } from '@angular/material/dialog';
import { Semester } from '../../shared/models/semester.model';
import { BilanzMatrix, BilanzMonthCell, BilanzCell } from '../../shared/models/bilanz.model';

function cell(partial: Partial<BilanzMonthCell>): BilanzMonthCell {
  return {
    month: 1, amount: 0, currencySymbol: '€', mixedCurrency: false,
    future: false, editable: false, active: false,
    entryMarker: false, exitMarker: false, ...partial,
  };
}

class FakeBilanzService {
  matrix: BilanzMatrix = { year: 2026, currentYearMonth: '2026-07', families: [] };
  cell: BilanzCell = { lines: [], sum: 0, mixedCurrency: false };
  upsertCalls: unknown[] = [];
  getMatrix(_year: number) { return of(this.matrix); }
  getCell(_familyId: string, _year: number, _month: number) { return of(this.cell); }
  upsertOverride(req: unknown) { this.upsertCalls.push(req); return of(undefined); }
}

class FakeSemesterService {
  semesters: Semester[] = [];
  getAll() { return of(this.semesters); }
}

class FakeMatDialog {
  lastConfig: unknown;
  result: unknown = undefined;
  open(_cmp: unknown, config: unknown) {
    this.lastConfig = config;
    return { afterClosed: () => of(this.result) };
  }
}

describe('BilanzenComponent', () => {
  let component: BilanzenComponent;
  let bilanz: FakeBilanzService;
  let semester: FakeSemesterService;
  let dialog: FakeMatDialog;

  beforeEach(() => {
    bilanz = new FakeBilanzService();
    semester = new FakeSemesterService();
    dialog = new FakeMatDialog();
    component = new BilanzenComponent(
      bilanz as unknown as BilanzService,
      semester as unknown as SemesterService,
      dialog as unknown as MatDialog,
    );
  });

  it('defaults the year to the current calendar year and loads the matrix', () => {
    semester.semesters = [
      { id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ];
    component.ngOnInit();
    expect(component.selectedYear).toBe(new Date().getFullYear());
    expect(component.matrix).toBeTruthy();
  });

  it('derives the year list from semesters plus the current year, descending', () => {
    semester.semesters = [
      { id: 's1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: 'x' },
    ];
    component.ngOnInit();
    expect(component.years).toContain(2024);
    expect(component.years).toContain(2025);
    expect(component.years).toContain(new Date().getFullYear());
    // sorted descending
    expect(component.years[0]).toBeGreaterThanOrEqual(component.years[component.years.length - 1]);
  });

  // --- cellState: three visual states ---
  it('classifies a future cell as future', () => {
    expect(component.cellState(cell({ future: true, active: false }))).toBe('future');
    expect(component.cellState(cell({ future: true, active: true }))).toBe('future');
  });
  it('classifies a non-future cell without active children as inactive', () => {
    expect(component.cellState(cell({ future: false, active: false }))).toBe('inactive');
  });
  it('classifies a non-future cell with active children as active', () => {
    expect(component.cellState(cell({ future: false, active: true }))).toBe('active');
  });

  // --- pencil visibility (editing toggle only affects active cells) ---
  it('shows a pencil only on editable cells while editing', () => {
    component.editing = true;
    expect(component.showPencil(cell({ future: false, active: true, editable: true }))).toBe(true);
    expect(component.showPencil(cell({ future: true, editable: false }))).toBe(false);
    expect(component.showPencil(cell({ future: false, active: false, editable: false }))).toBe(false);
  });
  it('never shows a pencil when not editing', () => {
    component.editing = false;
    expect(component.showPencil(cell({ future: false, active: true, editable: true }))).toBe(false);
  });

  it('toggleEdit flips the editing flag', () => {
    expect(component.editing).toBe(false);
    component.toggleEdit();
    expect(component.editing).toBe(true);
  });

  it('onYearChange reloads the matrix for the chosen year', () => {
    const spy = spyOn(bilanz, 'getMatrix').and.callThrough();
    component.onYearChange(2024);
    expect(component.selectedYear).toBe(2024);
    expect(spy).toHaveBeenCalledWith(2024);
  });

  // --- cell click: opens dialog only for editable cells while editing ---
  it('does not open the dialog for a non-editable cell', () => {
    const spy = spyOn(dialog, 'open').and.callThrough();
    component.editing = true;
    component.onCellClick(
      { familyId: 'f1', name: 'Meier', months: [], total: 0 },
      cell({ editable: false }));
    expect(spy).not.toHaveBeenCalled();
  });

  it('opens the dialog and PUTs only changed lines on OK, then reloads', () => {
    component.editing = true;
    component.selectedYear = 2020;
    bilanz.cell = {
      lines: [
        { personId: 'p1', childName: 'Anna', definitionId: 'd1', label: 'Elternbeitrag', currencySymbol: '€', defaultAmount: 100, effectiveAmount: 100 },
      ],
      sum: 100, mixedCurrency: false,
    };
    dialog.result = { changed: [{ personId: 'p1', definitionId: 'd1', amount: 500 }] };
    const reload = spyOn(bilanz, 'getMatrix').and.callThrough();

    component.onCellClick(
      { familyId: 'f1', name: 'Meier', months: [], total: 0 },
      cell({ month: 3, editable: true, active: true }));

    expect(bilanz.upsertCalls).toEqual([
      { personId: 'p1', year: 2020, month: 3, definitionId: 'd1', amount: 500 },
    ]);
    expect(reload).toHaveBeenCalledWith(2020);
  });

  it('does not PUT anything when the dialog is cancelled', () => {
    component.editing = true;
    bilanz.cell = { lines: [], sum: 0, mixedCurrency: false };
    dialog.result = undefined; // cancelled
    component.onCellClick(
      { familyId: 'f1', name: 'Meier', months: [], total: 0 },
      cell({ month: 3, editable: true, active: true }));
    expect(bilanz.upsertCalls.length).toBe(0);
  });
});
