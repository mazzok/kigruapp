import { of } from 'rxjs';
import { BoardComponent } from './board.component';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../../settings/custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonService } from '../../shared/services/person.service';
import { SemesterService } from '../../shared/services/semester.service';
import { MatDialog } from '@angular/material/dialog';
import { OrganisationDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { PersonDTO } from '../../shared/models/person.model';
import { Semester } from '../../shared/models/semester.model';

function instance(partial: Partial<FieldInstanceDTO>): FieldInstanceDTO {
  return {
    id: 'i1', definitionId: 'd1', fieldName: 'x', label: {}, jsonSchema: {},
    required: false, value: {}, definitionOutdated: false, ...partial,
  };
}

function personDTO(partial: Partial<PersonDTO>): PersonDTO {
  return {
    id: 'p1', familyId: 'f1', basicProperties: [], roles: [], schedules: [],
    duties: [], finance: [], customProperties: [], assignedDuty: [], assignedRole: [], ...partial,
  };
}

class FakeOrganisationService {
  orgs: Record<string, OrganisationDTO> = {};
  updateCalls: { id: string; body: { definitionIds: string[] } }[] = [];
  getByTag(tag: string) { return of(this.orgs[tag]); }
  update(id: string, body: { definitionIds: string[] }) {
    this.updateCalls.push({ id, body });
    return of({ ...this.orgs[id], ...body } as unknown as OrganisationDTO);
  }
}

class FakeFieldDefinitionService {
  createCalls: FieldDefinition[] = [];
  create(def: FieldDefinition) {
    this.createCalls.push(def);
    return of({ ...def, id: 'newdef' });
  }
}

class FakeFieldInstanceService {
  instancesByDef: Record<string, FieldInstanceDTO[]> = {};
  createCalls: { definitionId: string; value: unknown }[] = [];
  updateCalls: { id: string; fi: { definitionId: string; value: unknown } }[] = [];
  listByDefinitionId(id: string) { return of(this.instancesByDef[id] ?? []); }
  create(definitionId: string, value: unknown) {
    this.createCalls.push({ definitionId, value });
    return of({ id: 'newinst' });
  }
  update(id: string, fi: { definitionId: string; value: unknown }) {
    this.updateCalls.push({ id, fi });
    return of(fi);
  }
}

class FakePersonService {
  deleteBoardRoleCalls: string[] = [];
  assignBoardRoleCalls: { personId: string; definitionId: string; fieldInstanceId: string; semesterId?: string }[] = [];
  assignBoardRoleResponse: PersonDTO = personDTO({});
  persons: { id: string }[] = [];
  fullById: Record<string, PersonDTO> = {};
  deleteBoardRole(id: string) { this.deleteBoardRoleCalls.push(id); return of(undefined); }
  assignBoardRole(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string) {
    this.assignBoardRoleCalls.push({ personId, definitionId, fieldInstanceId, semesterId });
    return of(this.assignBoardRoleResponse);
  }
  list() { return of(this.persons); }
  getFull(id: string, _semesterId?: string) { return of(this.fullById[id] ?? personDTO({ id })); }
}

class FakeSemesterService {
  semesters: Semester[] = [];
  getAll() { return of(this.semesters); }
}

class FakeMatDialog {
  result: unknown = true;
  open() { return { afterClosed: () => of(this.result) }; }
}

describe('BoardComponent — Definition tab', () => {
  let component: BoardComponent;
  let org: FakeOrganisationService;
  let fieldDef: FakeFieldDefinitionService;
  let fieldInst: FakeFieldInstanceService;
  let person: FakePersonService;
  let semester: FakeSemesterService;
  let dialog: FakeMatDialog;

  beforeEach(() => {
    org = new FakeOrganisationService();
    fieldDef = new FakeFieldDefinitionService();
    fieldInst = new FakeFieldInstanceService();
    person = new FakePersonService();
    semester = new FakeSemesterService();
    dialog = new FakeMatDialog();
    org.orgs['board'] = { id: 'bo', tag: 'board', definitions: [], entries: [] };
    org.orgs['board-roles'] = { id: 'bro', tag: 'board-roles', definitions: [], entries: [] };
    component = new BoardComponent(
      org as unknown as OrganisationService,
      fieldDef as unknown as FieldDefinitionService,
      fieldInst as unknown as FieldInstanceService,
      person as unknown as PersonService,
      semester as unknown as SemesterService,
      dialog as unknown as MatDialog,
    );
  });

  it('lazily creates the board definition and singleton instance when the board tag is empty', () => {
    component.ngOnInit();
    expect(fieldDef.createCalls.some((d) => d.fieldName === 'board')).toBe(true);
    expect(org.updateCalls.some((c) => c.id === 'bo')).toBe(true);
    expect(fieldInst.createCalls.length).toBeGreaterThan(0);
    expect(component.boardTeam).toBeTruthy();
  });

  it('persists a board label/color edit via update() on the board-team instance', () => {
    component.boardDefinitionId = 'bd1';
    component.boardTeam = instance({ id: 'bt1', definitionId: 'bd1', fieldName: 'board', value: { label: 'Vorstand', color: '#4285f4' } });
    component.boardForm.setValue({ labelDe: 'Vorstand neu', color: '#123456' });

    component.saveBoard();

    expect(fieldInst.updateCalls.length).toBe(1);
    expect(fieldInst.updateCalls[0].id).toBe('bt1');
    expect(fieldInst.updateCalls[0].fi).toEqual({ definitionId: 'bd1', value: { label: 'Vorstand neu', color: '#123456' } });
  });

  it('deletes a board role through personService.deleteBoardRole after confirmation', () => {
    dialog.result = true;
    const role = instance({ id: 'r1', definitionId: 'brd', fieldName: 'board-role', value: { label: 'Obmann' } });

    component.deleteRole(role);

    expect(person.deleteBoardRoleCalls).toEqual(['r1']);
  });

  it('does not delete a board role when the confirmation is cancelled', () => {
    dialog.result = false;
    const role = instance({ id: 'r1', definitionId: 'brd', fieldName: 'board-role', value: { label: 'Obmann' } });

    component.deleteRole(role);

    expect(person.deleteBoardRoleCalls).toEqual([]);
  });
});

describe('BoardComponent — Zuweisung tab', () => {
  let component: BoardComponent;
  let org: FakeOrganisationService;
  let fieldDef: FakeFieldDefinitionService;
  let fieldInst: FakeFieldInstanceService;
  let person: FakePersonService;
  let semester: FakeSemesterService;
  let dialog: FakeMatDialog;

  beforeEach(() => {
    org = new FakeOrganisationService();
    fieldDef = new FakeFieldDefinitionService();
    fieldInst = new FakeFieldInstanceService();
    person = new FakePersonService();
    semester = new FakeSemesterService();
    dialog = new FakeMatDialog();
    org.orgs['board'] = { id: 'bo', tag: 'board', definitions: [], entries: [] };
    org.orgs['board-roles'] = { id: 'bro', tag: 'board-roles', definitions: [], entries: [] };
    component = new BoardComponent(
      org as unknown as OrganisationService,
      fieldDef as unknown as FieldDefinitionService,
      fieldInst as unknown as FieldInstanceService,
      person as unknown as PersonService,
      semester as unknown as SemesterService,
      dialog as unknown as MatDialog,
    );
  });

  it('toggling a board role for a parent row assigns it with the selected semester and rehydrates the row', () => {
    component.selectedSemesterId = 's1';
    const role = instance({ id: 'r1', definitionId: 'brd', fieldName: 'board-role', value: { label: 'Obmann' } });
    component.boardRoles = [role];
    const row = { person: personDTO({ id: 'p1', assignedRole: [], assignedDuty: [] }), name: 'Max Muster' };
    person.assignBoardRoleResponse = personDTO({ id: 'p1', assignedRole: [role], assignedDuty: [instance({ id: 'bt1' })] });

    component.toggleBoardRole(row, role);

    expect(person.assignBoardRoleCalls).toEqual([
      { personId: 'p1', definitionId: 'brd', fieldInstanceId: 'r1', semesterId: 's1' },
    ]);
    expect(row.person.assignedRole.some((r) => r.id === 'r1')).toBe(true);
    expect(row.person.assignedDuty.some((d) => d.id === 'bt1')).toBe(true);
  });

  it('marks a board role as assigned only for a parent who holds it', () => {
    const role = instance({ id: 'r1', definitionId: 'brd', fieldName: 'board-role', value: { label: 'Obmann' } });
    const holder = personDTO({ id: 'p1', assignedRole: [role] });
    const other = personDTO({ id: 'p2', assignedRole: [] });
    expect(component.isBoardRoleAssigned(holder, role)).toBe(true);
    expect(component.isBoardRoleAssigned(other, role)).toBe(false);
  });

  it('disables board-role assignment when there are no semesters', () => {
    component.semesters = [];
    expect(component.assignmentDisabled()).toBe(true);

    component.semesters = [{ id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: 'x' }];
    expect(component.assignmentDisabled()).toBe(false);
  });
});
