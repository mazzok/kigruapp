import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

@Component({
  selector: 'app-bilanz-cell-detail-card',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <div class="card">
      <div class="head">
        <div class="title">{{ monthLabel }} {{ year }}</div>
        @if (cell.active && !cell.reason) {
          <div class="chips">
            <span class="chip mode">Aliquot: {{ modeLabel(cell.aliquotMode) }}</span>
            @if (cell.entryDate) { <span class="chip io">Eintritt {{ cell.entryDate | date:'dd.MM.' }}</span> }
            @if (cell.exitDate) { <span class="chip io">Austritt {{ cell.exitDate | date:'dd.MM.' }}</span> }
          </div>
        }
      </div>

      @if (cell.reason === 'NO_PLACE') {
        <div class="empty">Kein Platz in diesem Monat.</div>
      } @else if (cell.reason === 'FUTURE') {
        <div class="empty">Zukünftiger Monat — noch nicht abgerechnet.</div>
      } @else {
        <table>
          <thead>
            <tr><th>Position</th><th>Basis</th><th>Rabatt</th><th>Aliquot</th><th>Effektiv</th></tr>
          </thead>
          <tbody>
            @for (l of cell.lines; track $index) {
              <tr>
                <td class="pos">{{ l.label }}
                  @if (l.overridden) { <div class="ovr-note">Rabatt &amp; Aliquot umgangen</div> }
                </td>
                @if (l.overridden) {
                  <td colspan="3" class="ovr"><span class="badge">manuell gesetzt</span></td>
                } @else {
                  <td class="base">{{ l.baseAmount }} {{ l.currencySymbol }}</td>
                  <td>
                    @if (l.discountPercent > 0) {
                      <span class="redu">−{{ l.discountPercent }} %</span>
                      @if (l.discountOrdinal > 0) { <span class="sub">{{ l.discountOrdinal }}. Kind</span> }
                    } @else { <span class="none">—</span> }
                  </td>
                  <td>
                    @if (l.fullMonth) { <span class="none">voller Monat</span> }
                    @else { <span class="ali">×{{ l.presentDays }}/{{ l.daysInMonth }}</span> }
                  </td>
                }
                <td class="eff">{{ l.effectiveAmount }} {{ l.currencySymbol }}</td>
              </tr>
            }
          </tbody>
        </table>

        @if (cell.mixedCurrency) {
          <div class="warn"><mat-icon>warning</mat-icon> Gemischte Währungen — keine gemeinsame Summe.</div>
        } @else {
          <div class="foot"><span>Summe</span><strong>{{ cell.amount }} {{ cell.currencySymbol }}</strong></div>
        }
      }
    </div>
  `,
  styles: [`
    .card { background: #fff; border: 1px solid #e6e8eb; border-radius: 10px;
      box-shadow: 0 8px 28px rgba(20,24,31,.16); min-width: 320px; overflow: hidden;
      font-size: 13px; color: #1f2328; }
    .head { padding: 11px 14px; border-bottom: 1px solid #eef0f2; display: flex; flex-direction: column; gap: 5px; }
    .title { font-weight: 650; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: #eef0f3; color: #61656c; }
    .chip.mode { background: #e8f0fc; color: #1a66d6; }
    .chip.io { background: #e7f4ec; color: #1f7a43; }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: right; font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em;
      color: #8a8f98; padding: 8px 14px 5px; }
    th:first-child { text-align: left; }
    td { text-align: right; padding: 5px 14px; border-top: 1px solid #f2f3f5; vertical-align: top;
      font-variant-numeric: tabular-nums; }
    td.pos { text-align: left; font-weight: 550; }
    .base { color: #61656c; }
    .redu { color: #1f7a43; font-weight: 550; }
    .sub { display: block; color: #1f7a43; font-size: 11px; }
    .ali { color: #1f2328; font-weight: 600; }
    .none { color: #8a8f98; }
    .eff { font-weight: 700; }
    .ovr { text-align: right; }
    .badge { font-size: 10.5px; font-weight: 600; color: #a85a00; background: #fbefe0;
      padding: 1px 7px; border-radius: 999px; }
    .ovr-note { color: #a85a00; font-size: 11px; }
    .foot { display: flex; justify-content: space-between; align-items: baseline;
      padding: 10px 14px; border-top: 1px solid #eef0f2; background: #fbfcfd; }
    .foot strong { font-size: 15px; font-variant-numeric: tabular-nums; }
    .empty { padding: 16px 14px; color: #61656c; }
    .warn { display: flex; align-items: center; gap: 6px; padding: 9px 14px;
      background: #fff7ec; color: #b0640a; font-size: 12px; border-top: 1px solid #eef0f2; }
    .warn mat-icon { font-size: 17px; width: 17px; height: 17px; }
  `],
})
export class BilanzCellDetailCardComponent {
  @Input({ required: true }) cell!: BilanzMonthCell;
  @Input({ required: true }) monthLabel!: string;
  @Input({ required: true }) year!: number;

  modeLabel(mode: string | null): string {
    switch (mode) {
      case 'PER_DAY': return 'Taggenau';
      case 'WHOLE_MONTH': return 'Ganze Monate';
      default: return 'Keine';
    }
  }
}
