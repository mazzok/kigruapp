import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ChildDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { Semester } from '../../shared/models/semester.model';

@Component({
  selector: 'app-platzzuweisung',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule, MatSelectModule, MatFormFieldModule, MatProgressSpinnerModule,
    MatDatepickerModule, MatInputModule,
  ],
  template: `
    <div class="page-container">
      <h2>Platzzuweisung</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
        <mat-form-field appearance="outline" class="semester-select">
          <mat-label>Semester</mat-label>
          <mat-select [value]="selectedSemesterId" (selectionChange)="onSemesterChange($event.value)">
            @for (semester of semesters; track semester.id) {
              <mat-option [value]="semester.id">{{ getSemesterLabel(semester) }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <table mat-table [dataSource]="children" class="mat-elevation-z2 full-width">
          <ng-container matColumnDef="name">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let child">
              {{ child.lastName }}, {{ child.firstName }}
            </td>
          </ng-container>

          <ng-container matColumnDef="alter">
            <th mat-header-cell *matHeaderCellDef>Alter</th>
            <td mat-cell *matCellDef="let child">
              {{ getAge(child.dateOfBirth) ?? '—' }}
            </td>
          </ng-container>

          <ng-container matColumnDef="gruppe">
            <th mat-header-cell *matHeaderCellDef>Gruppe</th>
            <td mat-cell *matCellDef="let child">
              <mat-select
                [value]="child.groupInstanceId"
                (selectionChange)="onGroupChange(child, $event.value)"
                placeholder="—">
                <mat-option [value]="null">—</mat-option>
                @for (group of groups; track group.id) {
                  <mat-option [value]="group.id">{{ $any(group.value).label }}</mat-option>
                }
              </mat-select>
            </td>
          </ng-container>

          <ng-container matColumnDef="eintritt">
            <th mat-header-cell *matHeaderCellDef>Eintritt</th>
            <td mat-cell *matCellDef="let child">
              <mat-form-field appearance="outline" class="date-field">
                <input matInput [matDatepicker]="entryPicker"
                  [value]="parseDate(child.entryDate)"
                  [disabled]="isEntryDateDisabled(child)"
                  (dateChange)="onEntryDateChange(child, formatDate($event.value))">
                <mat-datepicker-toggle matIconSuffix [for]="entryPicker" [disabled]="isEntryDateDisabled(child)"></mat-datepicker-toggle>
                <mat-datepicker #entryPicker></mat-datepicker>
              </mat-form-field>
            </td>
          </ng-container>

          <ng-container matColumnDef="austritt">
            <th mat-header-cell *matHeaderCellDef>Austritt</th>
            <td mat-cell *matCellDef="let child">
              <mat-form-field appearance="outline" class="date-field">
                <input matInput [matDatepicker]="exitPicker"
                  [value]="parseDate(child.exitDate)"
                  [disabled]="isExitDateDisabled(child)"
                  (dateChange)="onExitDateChange(child, formatDate($event.value))">
                <mat-datepicker-toggle matIconSuffix [for]="exitPicker" [disabled]="isExitDateDisabled(child)"></mat-datepicker-toggle>
                <mat-datepicker #exitPicker></mat-datepicker>
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
    .semester-select { min-width: 200px; margin-bottom: 16px; display: block; }
    .date-field { width: 160px; }
    mat-select { min-width: 160px; }
  `],
})
export class PlatzzuweisungComponent implements OnInit {
  displayedColumns = ['name', 'alter', 'gruppe', 'eintritt', 'austritt'];
  children: ChildDTO[] = [];
  groups: FieldInstanceDTO[] = [];
  semesters: Semester[] = [];
  selectedSemesterId: string | null = null;
  loading = true;
  private groupDefinitionId: string | null = null;

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private semesterService: SemesterService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters;
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadChildren();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef) {
        return;
      }
      this.groupDefinitionId = templateDef.id!;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.groups = instances;
      });
    });
  }

  private loadChildren(): void {
    if (!this.selectedSemesterId) return;
    this.loading = true;
    this.personService.getChildren(this.selectedSemesterId).subscribe((children) => {
      this.children = children;
      this.loading = false;
    });
  }

  onSemesterChange(semesterId: string): void {
    this.selectedSemesterId = semesterId;
    this.loadChildren();
  }

  getSemesterLabel(semester: Semester): string {
    const startYear = new Date(semester.start).getFullYear();
    const endYear = new Date(semester.end).getFullYear();
    return `${startYear}/${endYear}`;
  }

  getAge(dateOfBirth: string | null): number | null {
    if (!dateOfBirth) return null;
    const today = new Date();
    const dob = new Date(dateOfBirth);
    let age = today.getFullYear() - dob.getFullYear();
    const m = today.getMonth() - dob.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < dob.getDate())) {
      age--;
    }
    return age;
  }

  onGroupChange(child: ChildDTO, groupInstanceId: string | null): void {
    if (!groupInstanceId || !this.groupDefinitionId || !this.selectedSemesterId) return;
    this.personService.assignGroup(child.id, this.groupDefinitionId, groupInstanceId, this.selectedSemesterId).subscribe(() => {
      child.groupDefinitionId = this.groupDefinitionId!;
      child.groupInstanceId = groupInstanceId;
      child.entryDate = null;
      child.exitDate = null;
    });
  }

  parseDate(dateStr: string | null): Date | null {
    if (!dateStr) return null;
    const [y, m, d] = dateStr.split('-').map(Number);
    return new Date(y, m - 1, d);
  }

  formatDate(date: Date | null): string | null {
    if (!date) return null;
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  isEntryDateDisabled(child: ChildDTO): boolean {
    return !child.groupInstanceId;
  }

  isExitDateDisabled(child: ChildDTO): boolean {
    return !child.groupInstanceId || !child.entryDate;
  }

  onEntryDateChange(child: ChildDTO, entryDate: string | null): void {
    if (!this.selectedSemesterId) return;
    this.personService.setEnrollmentDates(child.id, entryDate, child.exitDate, this.selectedSemesterId).subscribe(() => {
      child.entryDate = entryDate;
    });
  }

  onExitDateChange(child: ChildDTO, exitDate: string | null): void {
    if (!this.selectedSemesterId) return;
    this.personService.setEnrollmentDates(child.id, child.entryDate, exitDate, this.selectedSemesterId).subscribe(() => {
      child.exitDate = exitDate;
    });
  }
}
