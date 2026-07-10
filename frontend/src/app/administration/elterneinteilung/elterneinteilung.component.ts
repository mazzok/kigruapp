import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { forkJoin, of } from 'rxjs';
import { switchMap, catchError } from 'rxjs/operators';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';

interface ParentRow {
  person: PersonDTO;
  name: string;
}

@Component({
  selector: 'app-elterneinteilung',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTableModule, MatChipsModule,
    MatFormFieldModule, MatSelectModule,
    MatProgressSpinnerModule, MatTooltipModule,
    MatDialogModule,
  ],
  templateUrl: './elterneinteilung.component.html',
  styleUrl: './elterneinteilung.component.scss',
})
export class ElterneinteilungComponent implements OnInit {
  teams: FieldInstanceDTO[] = [];
  roles: FieldInstanceDTO[] = [];
  parentTeamsDefinitionId: string | null = null;
  parentTeamRolesDefinitionId: string | null = null;
  allParents: ParentRow[] = [];
  displayedParents: ParentRow[] = [];
  filterTeamId: string | null = null;
  loading = false;
  displayedColumns = ['name', 'teams'];

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    forkJoin({
      teamsOrg: this.orgService.getByTag('parent-teams').pipe(
        catchError(() => of({ id: '', tag: 'parent-teams', definitions: [], entries: [] } as any))
      ),
      rolesOrg: this.orgService.getByTag('parent-team-roles').pipe(
        catchError(() => of({ id: '', tag: 'parent-team-roles', definitions: [], entries: [] } as any))
      ),
    }).pipe(
      switchMap(({ teamsOrg, rolesOrg }) => {
        const teamDef = teamsOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team' && !d.outdatedAt
        );
        const roleDef = rolesOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team-role' && !d.outdatedAt
        );
        this.parentTeamsDefinitionId = teamDef?.id ?? null;
        this.parentTeamRolesDefinitionId = roleDef?.id ?? null;

        const teams$ = teamDef
          ? this.fieldInstanceService.listByDefinitionId(teamDef.id!)
          : of([] as FieldInstanceDTO[]);
        const roles$ = roleDef
          ? this.fieldInstanceService.listByDefinitionId(roleDef.id!)
          : of([] as FieldInstanceDTO[]);

        return forkJoin({ teams: teams$, roles: roles$ });
      }),
      switchMap(({ teams, roles }) => {
        this.teams = teams;
        this.roles = roles;
        return this.personService.list();
      }),
      switchMap((persons) => {
        if (persons.length === 0) return of([] as PersonDTO[]);
        return forkJoin(persons.map((p) => this.personService.getFull(p.id!)));
      }),
    ).subscribe({
      next: (fullPersons) => {
        this.allParents = fullPersons
          .filter((p) => !this.isChild(p))
          .map((p) => ({ person: p, name: this.getPersonName(p) }));
        this.applyFilter();
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  private isChild(person: PersonDTO): boolean {
    return person.basicProperties.some(
      (f) => f.fieldName === 'personType' && f.value === 'CHILD'
    );
  }

  private getPersonName(person: PersonDTO): string {
    const firstName = person.basicProperties.find((f) => f.fieldName === 'firstName')?.value as string ?? '';
    const lastName = person.basicProperties.find((f) => f.fieldName === 'lastName')?.value as string ?? '';
    return `${firstName} ${lastName}`.trim() || person.id!;
  }

  isAssigned(person: PersonDTO, team: FieldInstanceDTO): boolean {
    return (person.assignedDuty ?? []).some((d) => d.id === team.id);
  }

  isRoleAssigned(person: PersonDTO, role: FieldInstanceDTO): boolean {
    return (person.assignedRole ?? []).some((r) => r.id === role.id);
  }

  getVisibleRoles(person: PersonDTO): FieldInstanceDTO[] {
    const assignedTeamIds = new Set((person.assignedDuty ?? []).map((d) => d.id));
    return this.roles.filter(
      (r) => assignedTeamIds.has((r.value as Record<string, unknown>)?.['teamInstanceId'] as string)
    );
  }

  getTeamColor(team: FieldInstanceDTO | undefined): string {
    return (team?.value as Record<string, unknown>)?.['color'] as string ?? '#9e9e9e';
  }

  getTeamForRole(role: FieldInstanceDTO): FieldInstanceDTO | undefined {
    const teamId = (role.value as Record<string, unknown>)?.['teamInstanceId'] as string;
    return this.teams.find((t) => t.id === teamId);
  }

  getRolesForTeam(team: FieldInstanceDTO): FieldInstanceDTO[] {
    return this.roles.filter(
      (r) => (r.value as Record<string, unknown>)?.['teamInstanceId'] === team.id
    );
  }

  getAssignedTeams(person: PersonDTO): FieldInstanceDTO[] {
    return this.teams.filter((t) => this.isAssigned(person, t));
  }

  getAssignedCount(role: FieldInstanceDTO): number {
    return this.allParents.filter((row) =>
      (row.person.assignedRole ?? []).some((r) => r.id === role.id)
    ).length;
  }

  isRoleDisabled(person: PersonDTO, role: FieldInstanceDTO): boolean {
    const max = (role.value as Record<string, unknown>)?.['max'] as number | null;
    if (max == null) return false;
    if (this.isRoleAssigned(person, role)) return false;
    return this.getAssignedCount(role) >= max;
  }

  getRoleTooltip(person: PersonDTO, role: FieldInstanceDTO): string {
    if (!this.isRoleDisabled(person, role)) return '';
    const max = (role.value as Record<string, unknown>)?.['max'] as number;
    return `Maximale Anzahl (${max}) erreicht`;
  }

  toggleTeam(row: ParentRow, team: FieldInstanceDTO): void {
    if (!this.parentTeamsDefinitionId) return;
    const isCurrentlyAssigned = this.isAssigned(row.person, team);

    if (isCurrentlyAssigned) {
      const rolesInTeam = this.roles.filter(
        (r) => (r.value as Record<string, unknown>)?.['teamInstanceId'] === team.id
      );
      const assignedRolesInTeam = rolesInTeam.filter((r) => this.isRoleAssigned(row.person, r));

      if (assignedRolesInTeam.length > 0) {
        const roleNames = assignedRolesInTeam
          .map((r) => (r.value as Record<string, unknown>)?.['label'] as string ?? r.id)
          .join(', ');
        const teamLabel = this.getTeamLabel(team);
        const dialogRef = this.dialog.open(ConfirmDialogComponent, {
          data: {
            message: `${row.name} hat im Team "${teamLabel}" folgende Rollen zugewiesen: ${roleNames}. Team abwählen entfernt diese Rollen. Fortfahren?`,
          },
        });
        dialogRef.afterClosed().subscribe((confirmed: boolean) => {
          if (confirmed) {
            this.doToggleTeam(row, team, isCurrentlyAssigned, assignedRolesInTeam);
          }
        });
        return;
      }
    }

    this.doToggleTeam(row, team, isCurrentlyAssigned, []);
  }

  private doToggleTeam(
    row: ParentRow,
    team: FieldInstanceDTO,
    wasAssigned: boolean,
    rolesToRemove: FieldInstanceDTO[],
  ): void {
    this.personService.assignTeam(row.person.id!, this.parentTeamsDefinitionId!, team.id!).subscribe(() => {
      if (wasAssigned) {
        row.person.assignedDuty = (row.person.assignedDuty ?? []).filter((d) => d.id !== team.id);
        for (const role of rolesToRemove) {
          this.personService.assignRole(
            row.person.id!, this.parentTeamRolesDefinitionId!, role.id!
          ).subscribe(() => {
            row.person.assignedRole = (row.person.assignedRole ?? []).filter((r) => r.id !== role.id);
          });
        }
      } else {
        row.person.assignedDuty = [...(row.person.assignedDuty ?? []), team];
      }
      this.applyFilter();
    });
  }

  toggleRole(row: ParentRow, role: FieldInstanceDTO): void {
    if (!this.parentTeamRolesDefinitionId) return;
    if (this.isRoleDisabled(row.person, role)) return;
    this.personService.assignRole(row.person.id!, this.parentTeamRolesDefinitionId, role.id!).subscribe(() => {
      if (this.isRoleAssigned(row.person, role)) {
        row.person.assignedRole = (row.person.assignedRole ?? []).filter((r) => r.id !== role.id);
      } else {
        row.person.assignedRole = [...(row.person.assignedRole ?? []), role];
      }
    });
  }

  onFilterChange(): void {
    this.applyFilter();
  }

  private applyFilter(): void {
    if (!this.filterTeamId) {
      this.displayedParents = [...this.allParents];
    } else {
      this.displayedParents = this.allParents.filter((row) =>
        (row.person.assignedDuty ?? []).some((d) => d.id === this.filterTeamId)
      );
    }
  }

  getTeamLabel(team: FieldInstanceDTO): string {
    return (team.value as Record<string, unknown>)?.['label'] as string ?? team.id ?? '';
  }

  getRoleLabel(role: FieldInstanceDTO): string {
    return (role.value as Record<string, unknown>)?.['label'] as string ?? role.id ?? '';
  }
}
