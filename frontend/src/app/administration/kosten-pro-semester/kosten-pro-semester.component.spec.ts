import { of } from 'rxjs';
import { KostenProSemesterComponent } from './kosten-pro-semester.component';
import { SemesterService } from '../../shared/services/semester.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { KostenValueService } from '../../shared/services/kosten-value.service';
import { KostenDiscountService } from '../../shared/services/kosten-discount.service';
import { AliquotConfigService } from '../../shared/services/aliquot-config.service';
import { KostenDefinitionService } from '../../shared/services/kosten-definition.service';
import { Semester } from '../../shared/models/semester.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { KostenValue, UpsertKostenValueRequest } from '../../shared/models/kosten-value.model';
import { KostenDiscount } from '../../shared/models/kosten-discount.model';
import { AliquotConfig } from '../../shared/models/aliquot-config.model';
import { KostenDefinition } from '../../shared/models/kosten-definition.model';
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

class FakeKostenDiscountService {
  config: KostenDiscount = {
    semesterId: '', applyToAll: false, order: 'MOST_EXPENSIVE_FIRST', tiers: [], eligibleDefinitionIds: [],
  };
  saveCalls: { semesterId: string; dto: KostenDiscount }[] = [];
  get(_semesterId: string) {
    return of(this.config);
  }
  save(semesterId: string, dto: KostenDiscount) {
    this.saveCalls.push({ semesterId, dto });
    return of(dto);
  }
}

class FakeAliquotConfigService {
  config: AliquotConfig = { semesterId: '', stundenMode: 'NONE', kostenMode: 'NONE' };
  saveCalls: { semesterId: string; dto: AliquotConfig }[] = [];
  get(_semesterId: string) {
    return of(this.config);
  }
  save(semesterId: string, dto: AliquotConfig) {
    this.saveCalls.push({ semesterId, dto });
    return of(dto);
  }
}

class FakeKostenDefinitionService {
  defs: KostenDefinition[] = [];
  getAll() {
    return of(this.defs);
  }
}

describe('KostenProSemesterComponent', () => {
  let component: KostenProSemesterComponent;
  let semesterService: FakeSemesterService;
  let orgService: FakeOrganisationService;
  let fieldInstanceService: FakeFieldInstanceService;
  let kostenValueService: FakeKostenValueService;
  let kostenDiscountService: FakeKostenDiscountService;
  let aliquotConfigService: FakeAliquotConfigService;
  let kostenDefinitionService: FakeKostenDefinitionService;

  beforeEach(() => {
    semesterService = new FakeSemesterService();
    orgService = new FakeOrganisationService();
    fieldInstanceService = new FakeFieldInstanceService();
    kostenValueService = new FakeKostenValueService();
    kostenDiscountService = new FakeKostenDiscountService();
    aliquotConfigService = new FakeAliquotConfigService();
    kostenDefinitionService = new FakeKostenDefinitionService();

    component = new KostenProSemesterComponent(
      semesterService as unknown as SemesterService,
      orgService as unknown as OrganisationService,
      fieldInstanceService as unknown as FieldInstanceService,
      kostenValueService as unknown as KostenValueService,
      kostenDiscountService as unknown as KostenDiscountService,
      aliquotConfigService as unknown as AliquotConfigService,
      kostenDefinitionService as unknown as KostenDefinitionService,
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

  it('saveKostenDiscount posts to /kosten-discount with applyToAll, order, tiers, eligibleDefinitionIds', () => {
    component.selectedSemesterId = 's1';
    component.kdApplyToAll = false;
    component.kdOrder = 'LEAST_EXPENSIVE_FIRST';
    component.kdTiers = [{ fromChild: 2, percent: 50 }];
    component.kdEligibleIds = ['d1'];

    component.saveKostenDiscount();

    expect(component.kdError).toBeNull();
    expect(kostenDiscountService.saveCalls.length).toBe(1);
    expect(kostenDiscountService.saveCalls[0].semesterId).toBe('s1');
    expect(kostenDiscountService.saveCalls[0].dto).toEqual({
      semesterId: 's1',
      applyToAll: false,
      order: 'LEAST_EXPENSIVE_FIRST',
      tiers: [{ fromChild: 2, percent: 50 }],
      eligibleDefinitionIds: ['d1'],
    });
  });

  it('saveKostenDiscount rejects invalid tiers and sets kdError', () => {
    component.selectedSemesterId = 's1';
    component.kdTiers = [{ fromChild: 1, percent: 10 }];

    component.saveKostenDiscount();

    expect(component.kdError).not.toBeNull();
    expect(kostenDiscountService.saveCalls.length).toBe(0);
  });

  it('saveKostenAliquot posts to /aliquot-config echoing loaded stundenMode with chosen kostenMode', () => {
    component.selectedSemesterId = 's1';
    component.stundenMode = 'PER_DAY';
    component.kostenMode = 'WHOLE_MONTH';

    component.saveKostenAliquot();

    expect(aliquotConfigService.saveCalls.length).toBe(1);
    expect(aliquotConfigService.saveCalls[0].semesterId).toBe('s1');
    expect(aliquotConfigService.saveCalls[0].dto).toEqual({
      semesterId: 's1',
      stundenMode: 'PER_DAY',
      kostenMode: 'WHOLE_MONTH',
    });
  });

  it('recomputeKdPreview yields default plus one row per tier', () => {
    component.kdTiers = [{ fromChild: 2, percent: 50 }];

    component.recomputeKdPreview();

    expect(component.kdPreview.length).toBe(2);
    expect(component.kdPreview[0]).toEqual({ child: 1, percent: 0 });
    expect(component.kdPreview[1]).toEqual({ child: 2, percent: 50 });
  });
});
