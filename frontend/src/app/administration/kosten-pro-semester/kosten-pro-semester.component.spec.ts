import { of } from 'rxjs';
import { KostenProSemesterComponent } from './kosten-pro-semester.component';
import { SemesterService } from '../../shared/services/semester.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { KostenValueService } from '../../shared/services/kosten-value.service';
import { Semester } from '../../shared/models/semester.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { KostenValue, UpsertKostenValueRequest } from '../../shared/models/kosten-value.model';
import { OrganisationDTO } from '../../shared/models/organisation.model';

class FakeSemesterService {
  semesters: Semester[] = [];
  getAll() {
    return of(this.semesters);
  }
}

class FakeOrganisationService {
  org: OrganisationDTO = { id: 'org-groups', tag: 'groups', definitions: [], entries: [] };
  getByTag(_tag: string) {
    return of(this.org);
  }
}

class FakeFieldInstanceService {
  groups: FieldInstanceDTO[] = [];
  listByDefinitionId(_definitionId: string) {
    return of(this.groups);
  }
}

class FakeKostenValueService {
  upsertCalls: UpsertKostenValueRequest[] = [];
  values: KostenValue[] = [];
  getForSemesterAndGroup(_semesterId: string, _groupId: string) {
    return of(this.values);
  }
  upsert(request: UpsertKostenValueRequest) {
    this.upsertCalls.push(request);
    return of(undefined);
  }
}

describe('KostenProSemesterComponent', () => {
  let component: KostenProSemesterComponent;
  let semesterService: FakeSemesterService;
  let orgService: FakeOrganisationService;
  let fieldInstanceService: FakeFieldInstanceService;
  let kostenValueService: FakeKostenValueService;

  beforeEach(() => {
    semesterService = new FakeSemesterService();
    orgService = new FakeOrganisationService();
    fieldInstanceService = new FakeFieldInstanceService();
    kostenValueService = new FakeKostenValueService();

    component = new KostenProSemesterComponent(
      semesterService as unknown as SemesterService,
      orgService as unknown as OrganisationService,
      fieldInstanceService as unknown as FieldInstanceService,
      kostenValueService as unknown as KostenValueService,
    );
  });

  it('defaults to the first semester and first group on init', () => {
    semesterService.semesters = [
      { id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ];
    orgService.org = {
      id: 'org-groups', tag: 'groups',
      definitions: [{ id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false }],
      entries: [],
    };
    fieldInstanceService.groups = [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Baeren' }, definitionOutdated: false },
    ];

    component.ngOnInit();

    expect(component.selectedSemesterId).toBe('s1');
    expect(component.selectedGroupId).toBe('g1');
  });

  it('loads kosten-values for the selected semester and group', () => {
    semesterService.semesters = [
      { id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ];
    orgService.org = {
      id: 'org-groups', tag: 'groups',
      definitions: [{ id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false }],
      entries: [],
    };
    fieldInstanceService.groups = [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Baeren' }, definitionOutdated: false },
    ];
    kostenValueService.values = [
      { definitionId: 'd1', label: 'Elternbeitrag', currency: { id: 'c1', code: 'EUR', symbol: '€' }, amount: 340 },
    ];

    component.ngOnInit();

    expect(component.kostenValues.length).toBe(1);
    expect(component.kostenValues[0].amount).toBe(340);
  });

  it('reloads kosten-values when the semester changes', () => {
    component.selectedGroupId = 'g1';
    component.onSemesterChange('s2');

    expect(component.selectedSemesterId).toBe('s2');
  });

  it('reloads kosten-values when the group changes', () => {
    component.selectedSemesterId = 's1';
    component.onGroupChange('g2');

    expect(component.selectedGroupId).toBe('g2');
  });

  it('upserts an amount', () => {
    component.selectedSemesterId = 's1';
    component.selectedGroupId = 'g1';
    const value: KostenValue = { definitionId: 'd1', label: 'Elternbeitrag', currency: { id: 'c1', code: 'EUR', symbol: '€' }, amount: null };

    component.onAmountChange(value, 340);

    expect(kostenValueService.upsertCalls).toEqual([
      { semesterId: 's1', groupId: 'g1', definitionId: 'd1', amount: 340 },
    ]);
  });
});
