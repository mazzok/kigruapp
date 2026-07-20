import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { SemesterService } from '../../shared/services/semester.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { KostenValueService } from '../../shared/services/kosten-value.service';
import { Semester } from '../../shared/models/semester.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { KostenValue } from '../../shared/models/kosten-value.model';

@Component({
  selector: 'app-kosten-pro-semester',
  standalone: true,
  imports: [
    CommonModule, MatTableModule, MatSelectModule, MatFormFieldModule,
    MatInputModule, MatProgressSpinnerModule,
  ],
  template: `
    <div class="page-container">
      <h2>Kosten pro Semester</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
        <div class="filters">
          <mat-form-field appearance="outline">
            <mat-label>Semester</mat-label>
            <mat-select [value]="selectedSemesterId" (selectionChange)="onSemesterChange($event.value)">
              @for (semester of semesters; track semester.id) {
                <mat-option [value]="semester.id">{{ getSemesterLabel(semester) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Gruppe</mat-label>
            <mat-select [value]="selectedGroupId" (selectionChange)="onGroupChange($event.value)">
              @for (group of groups; track group.id) {
                <mat-option [value]="group.id">{{ $any(group.value).label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>

        <table mat-table [dataSource]="kostenValues" class="mat-elevation-z2 full-width">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Bezeichnung</th>
            <td mat-cell *matCellDef="let row">{{ row.label }}</td>
          </ng-container>

          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Betrag</th>
            <td mat-cell *matCellDef="let row">
              <mat-form-field appearance="outline" class="amount-field">
                <input matInput type="number" step="0.01"
                  [value]="row.amount"
                  (change)="onAmountChange(row, parseAmount($any($event.target).value))">
                <span matTextSuffix>{{ row.currency.symbol }}</span>
              </mat-form-field>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>
      }
    </div>
  `,
  styles: [`
    .page-container { padding: 24px; }
    .full-width { width: 100%; }
    .filters { display: flex; gap: 16px; margin-bottom: 16px; }
    .amount-field { width: 140px; }
  `],
})
export class KostenProSemesterComponent implements OnInit {
  displayedColumns = ['label', 'amount'];
  semesters: Semester[] = [];
  groups: FieldInstanceDTO[] = [];
  kostenValues: KostenValue[] = [];
  selectedSemesterId: string | null = null;
  selectedGroupId: string | null = null;
  loading = true;

  constructor(
    private semesterService: SemesterService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private kostenValueService: KostenValueService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters;
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadValues();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef) return;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.groups = instances;
        this.selectedGroupId = instances[0]?.id ?? null;
        this.loadValues();
      });
    });
  }

  private loadValues(): void {
    if (!this.selectedSemesterId || !this.selectedGroupId) return;
    this.loading = true;
    this.kostenValueService.getForSemesterAndGroup(this.selectedSemesterId, this.selectedGroupId).subscribe((values) => {
      this.kostenValues = values;
      this.loading = false;
    });
  }

  onSemesterChange(semesterId: string): void {
    this.selectedSemesterId = semesterId;
    this.loadValues();
  }

  onGroupChange(groupId: string): void {
    this.selectedGroupId = groupId;
    this.loadValues();
  }

  getSemesterLabel(semester: Semester): string {
    const startYear = new Date(semester.start).getFullYear();
    const endYear = new Date(semester.end).getFullYear();
    return `${startYear}/${endYear}`;
  }

  parseAmount(value: string): number | null {
    if (value === '' || value == null) return null;
    const parsed = Number(value);
    return Number.isNaN(parsed) ? null : parsed;
  }

  onAmountChange(value: KostenValue, amount: number | null): void {
    if (!this.selectedSemesterId || !this.selectedGroupId) return;
    this.kostenValueService.upsert({
      semesterId: this.selectedSemesterId,
      groupId: this.selectedGroupId,
      definitionId: value.definitionId,
      amount,
    }).subscribe(() => {
      value.amount = amount;
    });
  }
}
