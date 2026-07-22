import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../../settings/custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonService } from '../../shared/services/person.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';
import { OrganisationDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { PersonDTO } from '../../shared/models/person.model';
import { Semester } from '../../shared/models/semester.model';

const BOARD_TEAM_SCHEMA = {
  type: 'object',
  properties: { label: { type: 'string' }, color: { type: 'string' } },
  required: ['label'],
};

const BOARD_ROLE_SCHEMA = {
  type: 'object',
  properties: { label: { type: 'string' }, min: { type: 'number' }, max: { type: 'number' } },
};

const DEFAULT_BOARD_LABEL = 'Vorstand';
const DEFAULT_BOARD_COLOR = '#4285f4';

interface BoardParentRow {
  person: PersonDTO;
  name: string;
}

@Component({
  selector: 'app-board',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, FormsModule,
    MatTabsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatChipsModule, MatIconModule, MatDialogModule,
  ],
  templateUrl: './board.component.html',
  styleUrl: './board.component.scss',
})
export class BoardComponent implements OnInit {
  // Definition tab — board team singleton
  boardOrg: OrganisationDTO | null = null;
  boardDefinitionId: string | null = null;
  boardTeam: FieldInstanceDTO | null = null;
  boardForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    color: new FormControl('#4285f4', Validators.required),
  });

  // Definition tab — board roles
  boardRolesOrg: OrganisationDTO | null = null;
  boardRoleDefinitionId: string | null = null;
  boardRoles: FieldInstanceDTO[] = [];
  addRoleForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    min: new FormControl<number | null>(null),
    max: new FormControl<number | null>(null),
  });

  // Zuweisung tab — all parents in a table, board roles toggleable per parent (like Elterneinteilung)
  semesters: Semester[] = [];
  selectedSemesterId: string | null = null;
  parentRows: BoardParentRow[] = [];

  constructor(
    private orgService: OrganisationService,
    private fieldDefService: FieldDefinitionService,
    private fieldInstanceService: FieldInstanceService,
    private personService: PersonService,
    private semesterService: SemesterService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadBoard();
    this.loadBoardRoles();
    this.loadSemesters();
  }

  // --- Zuweisung tab ---

  loadSemesters(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters;
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadParentRows();
    });
  }

  loadParentRows(): void {
    if (!this.selectedSemesterId) {
      this.parentRows = [];
      return;
    }
    const semesterId = this.selectedSemesterId;
    this.personService.list().subscribe((persons) => {
      if (persons.length === 0) {
        this.parentRows = [];
        return;
      }
      forkJoin(persons.map((p) => this.personService.getFull(p.id!, semesterId))).subscribe((fulls) => {
        this.parentRows = fulls
          .filter((p) => !this.isChild(p))
          .map((p) => ({ person: p, name: this.getPersonName(p) }));
      });
    });
  }

  private isChild(person: PersonDTO): boolean {
    return person.basicProperties.some((f) => f.fieldName === 'personType' && f.value === 'CHILD');
  }

  private getPersonName(person: PersonDTO): string {
    const firstName = person.basicProperties.find((f) => f.fieldName === 'firstName')?.value as string ?? '';
    const lastName = person.basicProperties.find((f) => f.fieldName === 'lastName')?.value as string ?? '';
    return `${firstName} ${lastName}`.trim() || person.id;
  }

  onSemesterChange(): void {
    this.loadParentRows();
  }

  isBoardRoleAssigned(person: PersonDTO, role: FieldInstanceDTO): boolean {
    return (person.assignedRole ?? []).some((r) => r.id === role.id);
  }

  toggleBoardRole(row: BoardParentRow, role: FieldInstanceDTO): void {
    if (!this.selectedSemesterId) return;
    this.personService.assignBoardRole(row.person.id, role.definitionId, role.id!, this.selectedSemesterId)
      .subscribe((updated) => {
        row.person.assignedRole = updated.assignedRole ?? [];
        row.person.assignedDuty = updated.assignedDuty ?? [];
      });
  }

  getBoardColor(): string {
    return (this.boardTeam?.value as Record<string, unknown>)?.['color'] as string ?? DEFAULT_BOARD_COLOR;
  }

  assignmentDisabled(): boolean {
    return this.semesters.length === 0;
  }

  getSemesterLabel(semester: Semester): string {
    const startYear = new Date(semester.start).getFullYear();
    const endYear = new Date(semester.end).getFullYear();
    return `${startYear}/${endYear}`;
  }

  loadBoard(): void {
    this.orgService.getByTag('board').subscribe((board) => {
      this.boardOrg = board;
      const def = board.definitions.find((d) => d.fieldName === 'board' && !d.outdatedAt);
      if (!def) {
        this.createBoardTeam(board);
        return;
      }
      this.boardDefinitionId = def.id!;
      this.fieldInstanceService.listByDefinitionId(def.id!).subscribe((instances) => {
        if (instances.length === 0) {
          this.createBoardInstance(def.id!);
          return;
        }
        this.boardTeam = instances[0];
        this.patchBoardForm(this.boardTeam.value);
      });
    });
  }

  private createBoardTeam(board: OrganisationDTO): void {
    const def: FieldDefinition = {
      fieldName: 'board',
      label: { de: DEFAULT_BOARD_LABEL },
      jsonSchema: BOARD_TEAM_SCHEMA,
      required: false,
    };
    this.fieldDefService.create(def).pipe(
      switchMap((created) => {
        this.boardDefinitionId = created.id!;
        const definitionIds = [...board.definitions.map((d) => d.id!), created.id!];
        return this.orgService.update(board.id, { definitionIds }).pipe(
          switchMap(() => this.fieldInstanceService.create(created.id!, this.defaultBoardValue())),
        );
      }),
    ).subscribe((inst) => this.applyCreatedBoardTeam(inst.id));
  }

  private createBoardInstance(defId: string): void {
    this.fieldInstanceService.create(defId, this.defaultBoardValue())
      .subscribe((inst) => this.applyCreatedBoardTeam(inst.id));
  }

  private applyCreatedBoardTeam(id: string): void {
    const value = this.defaultBoardValue();
    this.boardTeam = {
      id,
      definitionId: this.boardDefinitionId!,
      fieldName: 'board',
      label: { de: DEFAULT_BOARD_LABEL },
      jsonSchema: BOARD_TEAM_SCHEMA,
      required: false,
      value,
      definitionOutdated: false,
    };
    this.patchBoardForm(value);
  }

  private defaultBoardValue(): { label: string; color: string } {
    return { label: DEFAULT_BOARD_LABEL, color: DEFAULT_BOARD_COLOR };
  }

  private patchBoardForm(value: unknown): void {
    const v = (value as Record<string, unknown>) ?? {};
    this.boardForm.patchValue({
      labelDe: (v['label'] as string) ?? DEFAULT_BOARD_LABEL,
      color: (v['color'] as string) ?? DEFAULT_BOARD_COLOR,
    });
  }

  saveBoard(): void {
    if (!this.boardForm.valid || !this.boardTeam || !this.boardDefinitionId) return;
    const value = { label: this.boardForm.value.labelDe!, color: this.boardForm.value.color! };
    this.fieldInstanceService.update(this.boardTeam.id!, { definitionId: this.boardDefinitionId, value })
      .subscribe(() => {
        this.boardTeam!.value = value;
      });
  }

  loadBoardRoles(): void {
    this.orgService.getByTag('board-roles').subscribe((org) => {
      this.boardRolesOrg = org;
      const def = org.definitions.find((d) => d.fieldName === 'board-role' && !d.outdatedAt);
      this.boardRoleDefinitionId = def?.id ?? null;
      if (!def) {
        this.boardRoles = [];
        return;
      }
      this.fieldInstanceService.listByDefinitionId(def.id!).subscribe((roles) => {
        this.boardRoles = roles;
      });
    });
  }

  addRole(): void {
    if (!this.addRoleForm.valid) return;
    const { labelDe, min, max } = this.addRoleForm.value;
    const value: Record<string, unknown> = { label: labelDe! };
    if (min != null) value['min'] = min;
    if (max != null) value['max'] = max;

    if (this.boardRoleDefinitionId) {
      this.fieldInstanceService.create(this.boardRoleDefinitionId, value).subscribe(() => {
        this.addRoleForm.reset();
        this.loadBoardRoles();
      });
      return;
    }

    const def: FieldDefinition = {
      fieldName: 'board-role',
      label: { de: 'Vorstandsrolle' },
      jsonSchema: BOARD_ROLE_SCHEMA,
      required: false,
    };
    this.fieldDefService.create(def).pipe(
      switchMap((created) => {
        this.boardRoleDefinitionId = created.id!;
        const org = this.boardRolesOrg;
        if (org) {
          const definitionIds = [...org.definitions.map((d) => d.id!), created.id!];
          return this.orgService.update(org.id, { definitionIds }).pipe(
            switchMap(() => this.fieldInstanceService.create(created.id!, value)),
          );
        }
        return this.fieldInstanceService.create(created.id!, value);
      }),
    ).subscribe(() => {
      this.addRoleForm.reset();
      this.loadBoardRoles();
    });
  }

  deleteRole(role: FieldInstanceDTO): void {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: { message: `Rolle "${this.getRoleLabel(role)}" löschen? Sie wird aus allen Zuweisungen entfernt.` },
    });
    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (!confirmed) return;
      this.personService.deleteBoardRole(role.id!).subscribe(() => this.loadBoardRoles());
    });
  }

  getRoleLabel(role: FieldInstanceDTO): string {
    return (role.value as Record<string, unknown>)?.['label'] as string ?? role.id ?? '';
  }
}
