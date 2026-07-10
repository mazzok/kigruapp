import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { MatDialog } from '@angular/material/dialog';
import { OrganisationDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

class FakeOrganisationService {
  updateCalls: { id: string; body: unknown }[] = [];
  orgsByTag: Record<string, OrganisationDTO> = {
    groups: { id: 'org-groups', tag: 'groups', definitions: [], entries: [] },
    'duty-settings': { id: 'org-duty', tag: 'duty-settings', definitions: [], entries: [] },
    'parent-teams': { id: 'org-teams', tag: 'parent-teams', definitions: [], entries: [] },
    'parent-team-roles': { id: 'org-roles', tag: 'parent-team-roles', definitions: [], entries: [] },
  };
  getByTag(tag: string) {
    return of(this.orgsByTag[tag]);
  }
  update(id: string, body: unknown) {
    this.updateCalls.push({ id, body });
    return of(this.orgsByTag['parent-teams']);
  }
}

class FakeFieldDefinitionService {
  createCalls: FieldDefinition[] = [];
  create(def: FieldDefinition) {
    this.createCalls.push(def);
    return of({ ...def, id: 'def-team-new' });
  }
}

class FakeFieldInstanceService {
  createCalls: { definitionId: string; value: unknown }[] = [];
  create(definitionId: string, value: unknown) {
    this.createCalls.push({ definitionId, value });
    return of({ id: 'instance-1' });
  }
  listByDefinitionId(_definitionId: string) {
    return of([] as FieldInstanceDTO[]);
  }
  delete(_id: string) {
    return of(undefined);
  }
}

describe('OrganisationComponent - Team-Farbe', () => {
  let component: OrganisationComponent;
  let orgService: FakeOrganisationService;
  let fieldDefService: FakeFieldDefinitionService;
  let fieldInstanceService: FakeFieldInstanceService;

  beforeEach(() => {
    orgService = new FakeOrganisationService();
    fieldDefService = new FakeFieldDefinitionService();
    fieldInstanceService = new FakeFieldInstanceService();
    const fakeDialog = { open: () => ({ afterClosed: () => of(null) }) } as unknown as MatDialog;

    component = new OrganisationComponent(
      orgService as unknown as OrganisationService,
      fieldDefService as unknown as FieldDefinitionService,
      fieldInstanceService as unknown as FieldInstanceService,
      fakeDialog,
    );
  });

  it('sends label and color when creating the first parent-team FieldDefinition', () => {
    component.ngOnInit();
    component.parentTeamsForm.setValue({ labelDe: 'Garten', color: '#ff0000' });

    component.addParentTeam();

    expect(fieldDefService.createCalls.length).toBe(1);
    const jsonSchema = fieldDefService.createCalls[0].jsonSchema as { properties: Record<string, unknown> };
    expect(jsonSchema.properties['color']).toEqual({ type: 'string' });
    expect(fieldInstanceService.createCalls[0].value).toEqual({ label: 'Garten', color: '#ff0000' });
  });

  it('sends label and color when adding a team to an existing definition', () => {
    orgService.orgsByTag['parent-teams'] = {
      id: 'org-teams',
      tag: 'parent-teams',
      definitions: [{
        id: 'def-team-1', fieldName: 'parent-team',
        label: { de: 'Elterneinteilung' }, jsonSchema: {}, required: false,
      }],
      entries: [],
    };
    component.ngOnInit();
    component.parentTeamsForm.setValue({ labelDe: 'Kueche', color: '#00ff00' });

    component.addParentTeam();

    expect(fieldInstanceService.createCalls[0]).toEqual({
      definitionId: 'def-team-1',
      value: { label: 'Kueche', color: '#00ff00' },
    });
  });
});
