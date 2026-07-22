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
import { SemesterService } from '../../shared/services/semester.service';
import { PersonDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { Semester } from '../../shared/models/semester.model';
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
  // Board (Vorstand) — read-only display only (edited via Administration > Vorstand); never part of
  // the toggleable parent-teams set.
  boardTeamInstanceId: string | null = null;
  boardRoleInstanceIds = new Set<string>();
  allParents: ParentRow[] = [];
  displayedParents: ParentRow[] = [];
  filterTeamId: string | null = null;
  semesters: Semester[] = [];
  selectedSemesterId: string | null = null;
  loading = false;
  displayedColumns = ['name', 'teams'];

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private semesterService: SemesterService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters;
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.load();
    });
  }

  onSemesterChange(): void {
    this.load();
  }

  getSemesterLabel(semester: Semester): string {
    const startYear = new Date(semester.start).getFullYear();
    const endYear = new Date(semester.end).getFullYear();
    return `${startYear}/${endYear}`;
  }

  private load(): void {
    if (!this.selectedSemesterId) return;
    const semesterId = this.selectedSemesterId;
    this.loading = true;
    forkJoin({
      teamsOrg: this.orgService.getByTag('parent-teams').pipe(
        catchError(() => of({ id: '', tag: 'parent-teams', definitions: [], entries: [] } as any))
      ),
      rolesOrg: this.orgService.getByTag('parent-team-roles').pipe(
        catchError(() => of({ id: '', tag: 'parent-team-roles', definitions: [], entries: [] } as any))
      ),
      boardOrg: this.orgService.getByTag('board').pipe(
        catchError(() => of({ id: '', tag: 'board', definitions: [], entries: [] } as any))
      ),
      boardRolesOrg: this.orgService.getByTag('board-roles').pipe(
        catchError(() => of({ id: '', tag: 'board-roles', definitions: [], entries: [] } as any))
      ),
    }).pipe(
      switchMap(({ teamsOrg, rolesOrg, boardOrg, boardRolesOrg }) => {
        const teamDef = teamsOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team' && !d.outdatedAt
        );
        const roleDef = rolesOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team-role' && !d.outdatedAt
        );
        const boardDef = boardOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'board' && !d.outdatedAt
        );
        const boardRoleDef = boardRolesOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'board-role' && !d.outdatedAt
        );
        this.parentTeamsDefinitionId = teamDef?.id ?? null;
        this.parentTeamRolesDefinitionId = roleDef?.id ?? null;

        const teams$ = teamDef
          ? this.fieldInstanceService.listByDefinitionId(teamDef.id!)
          : of([] as FieldInstanceDTO[]);
        const roles$ = roleDef
          ? this.fieldInstanceService.listByDefinitionId(roleDef.id!)
          : of([] as FieldInstanceDTO[]);
        const boardTeams$ = boardDef
          ? this.fieldInstanceService.listByDefinitionId(boardDef.id!)
          : of([] as FieldInstanceDTO[]);
        const boardRoles$ = boardRoleDef
          ? this.fieldInstanceService.listByDefinitionId(boardRoleDef.id!)
          : of([] as FieldInstanceDTO[]);

        return forkJoin({ teams: teams$, roles: roles$, boardTeams: boardTeams$, boardRoles: boardRoles$ });
      }),
      switchMap(({ teams, roles, boardTeams, boardRoles }) => {
        this.teams = teams;
        this.roles = roles;
        this.boardTeamInstanceId = boardTeams[0]?.id ?? null;
        this.boardRoleInstanceIds = new Set(boardRoles.map((r) => r.id!).filter((id): id is string => !!id));
        return this.personService.list();
      }),
      switchMap((persons) => {
        if (persons.length === 0) return of([] as PersonDTO[]);
        return forkJoin(persons.map((p) => this.personService.getFull(p.id!, semesterId)));
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

  getTeamColor(team: FieldInstanceDTO | undefined): string {
    return (team?.value as Record<string, unknown>)?.['color'] as string ?? '#9e9e9e';
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
    if (!this.parentTeamsDefinitionId || !this.selectedSemesterId) return;
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
    const semesterId = this.selectedSemesterId!;
    this.personService.assignTeam(row.person.id!, this.parentTeamsDefinitionId!, team.id!, semesterId).subscribe(() => {
      if (wasAssigned) {
        row.person.assignedDuty = (row.person.assignedDuty ?? []).filter((d) => d.id !== team.id);
        for (const role of rolesToRemove) {
          this.personService.assignRole(
            row.person.id!, this.parentTeamRolesDefinitionId!, role.id!, semesterId
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
    if (!this.parentTeamRolesDefinitionId || !this.selectedSemesterId) return;
    if (this.isRoleDisabled(row.person, role)) return;
    this.personService.assignRole(row.person.id!, this.parentTeamRolesDefinitionId, role.id!, this.selectedSemesterId).subscribe(() => {
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

  /** The person's board-team membership (read-only), or null. Selected by board instance id (D2 distinct-class). */
  getBoardTeam(person: PersonDTO): FieldInstanceDTO | null {
    if (!this.boardTeamInstanceId) return null;
    return (person.assignedDuty ?? []).find((d) => d.id === this.boardTeamInstanceId) ?? null;
  }

  /** The person's board roles (read-only). Selected by board-role instance ids (D2 distinct-class). */
  getBoardRoles(person: PersonDTO): FieldInstanceDTO[] {
    return (person.assignedRole ?? []).filter((r) => r.id != null && this.boardRoleInstanceIds.has(r.id));
  }

  getBoardLabel(team: FieldInstanceDTO): string {
    return (team.value as Record<string, unknown>)?.['label'] as string ?? team.id ?? '';
  }
}
