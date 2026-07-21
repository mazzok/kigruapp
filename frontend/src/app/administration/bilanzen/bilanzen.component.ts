import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin, of } from 'rxjs';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { BilanzService } from '../../shared/services/bilanz.service';
import { SemesterService } from '../../shared/services/semester.service';
import { BilanzMatrix, BilanzChildRow, BilanzMonthCell } from '../../shared/models/bilanz.model';
import {
  BilanzCellDialogComponent,
  BilanzCellDialogData,
  BilanzCellDialogResult,
} from './bilanz-cell-dialog.component';

@Component({
  selector: 'app-bilanzen',
  standalone: true,
  imports: [
    CommonModule, MatTableModule, MatSelectModule, MatFormFieldModule,
    MatButtonModule, MatIconModule, MatTooltipModule, MatProgressSpinnerModule,
    MatDialogModule,
  ],
  template: `
    <div class="page-container">
      <div class="header">
        <h2>Bilanzen</h2>
        <div class="header-actions">
          <mat-form-field appearance="outline" class="year-field">
            <mat-label>Jahr</mat-label>
            <mat-select [value]="selectedYear" (selectionChange)="onYearChange($event.value)">
              @for (y of years; track y) {
                <mat-option [value]="y">{{ y }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <button mat-raised-button [color]="editing ? 'primary' : undefined" (click)="toggleEdit()">
            <mat-icon>edit</mat-icon> Editieren
          </button>
        </div>
      </div>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else if (matrix) {
        <div class="table-scroll">
          <table mat-table [dataSource]="matrix.children" class="mat-elevation-z2">
            <ng-container matColumnDef="child" sticky>
              <th mat-header-cell *matHeaderCellDef>Kind</th>
              <td mat-cell *matCellDef="let row">{{ row.name }}</td>
            </ng-container>

            @for (m of monthColumns; track m) {
              <ng-container [matColumnDef]="'m' + m">
                <th mat-header-cell *matHeaderCellDef>{{ monthLabels[m - 1] }}</th>
                <td mat-cell *matCellDef="let row"
                    [ngClass]="'cell-' + cellState(row.months[m - 1])"
                    (click)="onCellClick(row, row.months[m - 1])">
                  <span class="cell-content">
                    @if (row.months[m - 1].mixedCurrency) {
                      <mat-icon class="warn" matTooltip="Gemischte Währungen">warning</mat-icon>
                    } @else if (cellState(row.months[m - 1]) === 'active') {
                      {{ row.months[m - 1].amount }} {{ row.months[m - 1].currencySymbol }}
                    } @else if (cellState(row.months[m - 1]) === 'inactive') {
                      0
                    }
                    @if (row.months[m - 1].entryMarker) { <mat-icon class="marker">login</mat-icon> }
                    @if (row.months[m - 1].exitMarker) { <mat-icon class="marker">logout</mat-icon> }
                    @if (showPencil(row.months[m - 1])) { <mat-icon class="pencil">edit</mat-icon> }
                  </span>
                </td>
              </ng-container>
            }

            <ng-container matColumnDef="total" stickyEnd>
              <th mat-header-cell *matHeaderCellDef>Summe</th>
              <td mat-cell *matCellDef="let row"><strong>{{ row.total }}</strong></td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
          </table>
        </div>
      }
    </div>
  `,
  styles: [`
    .page-container { padding: 24px; }
    .header { display: flex; justify-content: space-between; align-items: center; }
    .header-actions { display: flex; gap: 16px; align-items: center; }
    .year-field { width: 120px; }
    .table-scroll { overflow-x: auto; }
    .cell-content { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
    .cell-future { background: #f0f0f0; color: #bbb; }
    .cell-inactive { background: #fff7e6; color: #999; }
    .cell-active { cursor: default; }
    .marker { font-size: 16px; width: 16px; height: 16px; opacity: 0.7; }
    .pencil { font-size: 16px; width: 16px; height: 16px; cursor: pointer; }
    .warn { color: #c77700; }
  `],
})
export class BilanzenComponent implements OnInit {
  matrix: BilanzMatrix | null = null;
  years: number[] = [];
  selectedYear = new Date().getFullYear();
  editing = false;
  loading = true;

  monthColumns = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
  monthLabels = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];
  displayedColumns = ['child', 'm1', 'm2', 'm3', 'm4', 'm5', 'm6', 'm7', 'm8', 'm9', 'm10', 'm11', 'm12', 'total'];

  constructor(
    private bilanzService: BilanzService,
    private semesterService: SemesterService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      const yearSet = new Set<number>();
      yearSet.add(new Date().getFullYear());
      for (const s of semesters) {
        yearSet.add(new Date(s.start).getFullYear());
        yearSet.add(new Date(s.end).getFullYear());
      }
      this.years = Array.from(yearSet).sort((a, b) => b - a);
      this.loadMatrix();
    });
  }

  private loadMatrix(): void {
    this.loading = true;
    this.bilanzService.getMatrix(this.selectedYear).subscribe((matrix) => {
      this.matrix = matrix;
      this.loading = false;
    });
  }

  onYearChange(year: number): void {
    this.selectedYear = year;
    this.loadMatrix();
  }

  toggleEdit(): void {
    this.editing = !this.editing;
  }

  cellState(cell: BilanzMonthCell): 'future' | 'inactive' | 'active' {
    if (cell.future) return 'future';
    if (!cell.active) return 'inactive';
    return 'active';
  }

  showPencil(cell: BilanzMonthCell): boolean {
    return this.editing && cell.editable;
  }

  onCellClick(row: BilanzChildRow, cell: BilanzMonthCell): void {
    if (!this.editing || !cell.editable) return;
    this.bilanzService.getCell(row.personId, this.selectedYear, cell.month).subscribe((loaded) => {
      const data: BilanzCellDialogData = {
        childName: row.name,
        year: this.selectedYear,
        month: cell.month,
        cell: loaded,
      };
      this.dialog.open(BilanzCellDialogComponent, { data, width: '480px' })
        .afterClosed().subscribe((result: BilanzCellDialogResult | undefined) => {
          if (!result || result.changed.length === 0) return;
          const puts = result.changed.map((c) =>
            this.bilanzService.upsertOverride({
              personId: c.personId,
              year: this.selectedYear,
              month: cell.month,
              definitionId: c.definitionId,
              amount: c.amount,
            }));
          forkJoin(puts.length ? puts : [of(void 0)]).subscribe(() => this.loadMatrix());
        });
    });
  }
}
