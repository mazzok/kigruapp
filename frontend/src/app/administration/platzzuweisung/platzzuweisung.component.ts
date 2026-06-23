import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { ChildDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

@Component({
  selector: 'app-platzzuweisung',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule, MatSelectModule, MatFormFieldModule, MatProgressSpinnerModule,
  ],
  template: `
    <div class="page-container">
      <h2>Platzzuweisung</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
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

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>
      }
    </div>
  `,
  styles: [`
    .page-container { padding: 24px; }
    .full-width { width: 100%; }
    mat-select { min-width: 160px; }
  `],
})
export class PlatzzuweisungComponent implements OnInit {
  displayedColumns = ['name', 'alter', 'gruppe'];
  children: ChildDTO[] = [];
  groups: FieldInstanceDTO[] = [];
  loading = true;
  private groupDefinitionId: string | null = null;

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
  ) {}

  ngOnInit(): void {
    this.personService.getChildren().subscribe((children) => {
      this.children = children;
      this.checkDone();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef) {
        this.checkDone();
        return;
      }
      this.groupDefinitionId = templateDef.id!;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.groups = instances;
        this.checkDone();
      });
    });
  }

  private loaded = 0;
  private checkDone(): void {
    this.loaded++;
    if (this.loaded >= 2) this.loading = false;
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
    if (!groupInstanceId || !this.groupDefinitionId) return;
    this.personService.assignGroup(child.id, this.groupDefinitionId, groupInstanceId).subscribe(() => {
      child.groupDefinitionId = this.groupDefinitionId!;
      child.groupInstanceId = groupInstanceId;
    });
  }
}
