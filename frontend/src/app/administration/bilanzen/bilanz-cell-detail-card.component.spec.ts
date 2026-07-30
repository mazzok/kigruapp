import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BilanzCellDetailCardComponent } from './bilanz-cell-detail-card.component';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

function cell(partial: Partial<BilanzMonthCell>): BilanzMonthCell {
  return {
    month: 10, amount: 77.41, currencySymbol: '€', mixedCurrency: false,
    future: false, editable: true, active: true, entryMarker: true, exitMarker: false,
    reason: null, aliquotMode: 'PER_DAY', entryDate: '2026-10-17', exitDate: null,
    lines: [], ...partial,
  };
}

describe('BilanzCellDetailCardComponent', () => {
  let fixture: ComponentFixture<BilanzCellDetailCardComponent>;
  let component: BilanzCellDetailCardComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [BilanzCellDetailCardComponent] }).compileComponents();
    fixture = TestBed.createComponent(BilanzCellDetailCardComponent);
    component = fixture.componentInstance;
    component.monthLabel = 'Okt';
    component.year = 2026;
  });

  it('renders NO_PLACE reason instead of a table', () => {
    component.cell = cell({ active: false, reason: 'NO_PLACE', lines: [] });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Kein Platz');
    expect((fixture.nativeElement as HTMLElement).querySelector('table')).toBeNull();
  });

  it('renders a breakdown row with discount and aliquot for an active cell', () => {
    component.cell = cell({
      lines: [{
        label: 'Materialbeitrag', currencySymbol: '€', baseAmount: 50,
        discountPercent: 20, discountOrdinal: 2, presentDays: 15, daysInMonth: 31,
        fullMonth: false, overridden: false, effectiveAmount: 19.35,
      }],
    });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Materialbeitrag');
    expect(text).toContain('20');       // Rabatt %
    expect(text).toContain('2. Kind');  // Ordinal
    expect(text).toContain('15/31');    // Aliquot
    expect(text).toContain('19');       // effektiv
  });

  it('marks overridden lines', () => {
    component.cell = cell({
      lines: [{
        label: 'Materialbeitrag', currencySymbol: '€', baseAmount: 100,
        discountPercent: 0, discountOrdinal: 0, presentDays: 31, daysInMonth: 31,
        fullMonth: true, overridden: true, effectiveAmount: 100,
      }],
    });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('manuell');
  });
});
