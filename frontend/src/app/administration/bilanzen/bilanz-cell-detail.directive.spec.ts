import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BilanzCellDetailDirective } from './bilanz-cell-detail.directive';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

const CELL: BilanzMonthCell = {
  month: 10, amount: 40, currencySymbol: '€', mixedCurrency: false,
  future: false, editable: true, active: true, entryMarker: false, exitMarker: false,
  reason: null, aliquotMode: 'NONE', entryDate: null, exitDate: null,
  lines: [{ label: 'Elternbeitrag', currencySymbol: '€', baseAmount: 40, discountPercent: 0,
    discountOrdinal: 0, presentDays: 31, daysInMonth: 31, fullMonth: true, overridden: false,
    effectiveAmount: 40 }],
};

@Component({
  standalone: true,
  imports: [BilanzCellDetailDirective],
  template: `<div [appBilanzCellDetail]="cell" detailMonthLabel="Okt" [detailYear]="2026"></div>`,
})
class HostComponent { cell = CELL; }

describe('BilanzCellDetailDirective', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('opens an overlay with the breakdown on mouseenter and closes on mouseleave', () => {
    const host: HTMLElement = fixture.nativeElement.querySelector('div');
    host.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(document.body.textContent).toContain('Elternbeitrag');

    host.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(document.body.textContent).not.toContain('Elternbeitrag');
  });
});
