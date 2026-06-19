import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { ChildDTO } from '../../shared/models/person.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';

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
                [value]="child.groupDefinitionId"
                (selectionChange)="onGroupChange(child, $event.value)"
                placeholder="—">
                <mat-option [value]="null">—</mat-option>
                @for (group of groups; track group.id) {
                  <mat-option [value]="group.id">{{ group.label['de'] }}</mat-option>
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
  groups: FieldDefinition[] = [];
  loading = true;

  // Map from groupDefinitionId → fieldInstanceId (looked up from groups org)
  private instanceIdByDefId = new Map<string, string>();

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
  ) {}

  ngOnInit(): void {
    this.personService.getChildren().subscribe((children) => {
      this.children = children;
      this.checkDone();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      this.groups = org.definitions.filter((d) => !d.outdatedAt);
      // Build map: definitionId → fieldInstanceId by fetching field_instances
      // The instanceId for each group is stored on the ChildDTO from the backend
      // We'll resolve it by looking at existing children's groupInstanceId
      this.checkDone();
    });
  }

  private loaded = 0;
  private checkDone(): void {
    this.loaded++;
    if (this.loaded >= 2) {
      this.buildInstanceMap();
      this.loading = false;
    }
  }

  private buildInstanceMap(): void {
    // Collect known mappings from already-assigned children
    for (const child of this.children) {
      if (child.groupDefinitionId && child.groupInstanceId) {
        this.instanceIdByDefId.set(child.groupDefinitionId, child.groupInstanceId);
      }
    }
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

  onGroupChange(child: ChildDTO, groupDefinitionId: string | null): void {
    if (!groupDefinitionId) return; // removing group is out of scope

    const fieldInstanceId = this.instanceIdByDefId.get(groupDefinitionId);
    if (!fieldInstanceId) {
      console.error('No FieldInstance found for group definition', groupDefinitionId);
      return;
    }

    this.personService.assignGroup(child.id, groupDefinitionId, fieldInstanceId).subscribe(() => {
      child.groupDefinitionId = groupDefinitionId;
      child.groupInstanceId = fieldInstanceId;
    });
  }
}
