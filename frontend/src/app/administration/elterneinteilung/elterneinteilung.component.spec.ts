import { of } from 'rxjs';
import { ElterneinteilungComponent } from './elterneinteilung.component';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { MatDialog } from '@angular/material/dialog';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { PersonDTO } from '../../shared/models/person.model';

function team(id: string, label: string, color?: string): FieldInstanceDTO {
  return {
    id, definitionId: 'def-team', fieldName: 'parent-team',
    label: { de: 'Elterneinteilung' }, jsonSchema: {}, required: false,
    value: color ? { label, color } : { label },
    definitionOutdated: false,
  };
}

function role(id: string, label: string, teamInstanceId: string): FieldInstanceDTO {
  return {
    id, definitionId: 'def-role', fieldName: 'parent-team-role',
    label: { de: 'Rolle' }, jsonSchema: {}, required: false,
    value: { label, teamInstanceId },
    definitionOutdated: false,
  };
}

function person(assignedDuty: FieldInstanceDTO[] = []): PersonDTO {
  return {
    id: 'p1', familyId: 'f1',
    basicProperties: [], roles: [], schedules: [], duties: [], finance: [],
    customProperties: [],
    assignedDuty, assignedRole: [],
  };
}

describe('ElterneinteilungComponent - Team-Farbe & Gruppierung', () => {
  let component: ElterneinteilungComponent;

  beforeEach(() => {
    component = new ElterneinteilungComponent(
      {} as PersonService,
      {} as OrganisationService,
      {} as FieldInstanceService,
      {} as SemesterService,
      {} as MatDialog,
    );
  });

  it('returns the team color when set', () => {
    expect(component.getTeamColor(team('team-1', 'Garten', '#ff0000'))).toBe('#ff0000');
  });

  it('falls back to grey when the team has no color (legacy data)', () => {
    expect(component.getTeamColor(team('team-1', 'Garten'))).toBe('#9e9e9e');
  });

  it('falls back to grey when no team is given', () => {
    expect(component.getTeamColor(undefined)).toBe('#9e9e9e');
  });

  it('returns only the roles belonging to the given team', () => {
    const spielplatzRole = role('role-1', 'Spielplatz', 'team-1');
    const kuecheRole = role('role-2', 'Kueche', 'team-2');
    component.roles = [spielplatzRole, kuecheRole];
    expect(component.getRolesForTeam(team('team-1', 'Garten'))).toEqual([spielplatzRole]);
  });

  it('returns the currently assigned teams for a person', () => {
    const gartenTeam = team('team-1', 'Garten', '#ff0000');
    const kuecheTeam = team('team-2', 'Kueche', '#00ff00');
    component.teams = [gartenTeam, kuecheTeam];
    const p = person([gartenTeam]);
    expect(component.getAssignedTeams(p)).toEqual([gartenTeam]);
  });
});

class FakeSemesterServiceForElterneinteilung {
  getAll() {
    return of([]);
  }
}

class FakePersonServiceForElterneinteilung {
  assignTeamCalls: { personId: string; definitionId: string; fieldInstanceId: string; semesterId: string | undefined }[] = [];
  assignTeam(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string) {
    this.assignTeamCalls.push({ personId, definitionId, fieldInstanceId, semesterId });
    return of(undefined);
  }
}

describe('ElterneinteilungComponent - Semester', () => {
  let component: ElterneinteilungComponent;
  let personService: FakePersonServiceForElterneinteilung;

  beforeEach(() => {
    personService = new FakePersonServiceForElterneinteilung();
    component = new ElterneinteilungComponent(
      personService as unknown as PersonService,
      {} as OrganisationService,
      {} as FieldInstanceService,
      new FakeSemesterServiceForElterneinteilung() as unknown as SemesterService,
      {} as MatDialog,
    );
  });

  it('derives the semester label from start/end years', () => {
    const label = component.getSemesterLabel({
      id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z',
    });
    expect(label).toBe('2025/2026');
  });

  it('includes the selected semester when assigning a team', () => {
    component.selectedSemesterId = 'semester-1';
    component.parentTeamsDefinitionId = 'def-team';
    const gartenTeam = team('team-1', 'Garten', '#ff0000');
    const row = { person: person([]), name: 'Max Muster' };

    (component as any).doToggleTeam(row, gartenTeam, false, []);

    expect(personService.assignTeamCalls).toEqual([
      { personId: 'p1', definitionId: 'def-team', fieldInstanceId: 'team-1', semesterId: 'semester-1' },
    ]);
  });
});
