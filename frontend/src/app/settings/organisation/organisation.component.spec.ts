import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { MatDialog } from '@angular/material/dialog';
import { OrganisationDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { Semester, CreateSemesterRequest } from '../../shared/models/semester.model';
import { Currency, CreateCurrencyRequest } from '../../shared/models/currency.model';
import { KostenDefinition, CreateKostenDefinitionRequest } from '../../shared/models/kosten-definition.model';
import { CurrencyService } from '../../shared/services/currency.service';
import { KostenDefinitionService } from '../../shared/services/kosten-definition.service';
import { RequiredHoursService } from '../../shared/services/required-hours.service';
import { RequiredHours } from '../../shared/models/required-hours.model';
import { AliquotConfigService } from '../../shared/services/aliquot-config.service';
import { AliquotConfig } from '../../shared/models/aliquot-config.model';

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

class FakeSemesterService {
  createCalls: CreateSemesterRequest[] = [];
  semesters: Semester[] = [];
  getAll() {
    return of(this.semesters);
  }
  create(request: CreateSemesterRequest) {
    this.createCalls.push(request);
    return of({ id: 'semester-new', ...request, createdAt: '2026-07-11T00:00:00Z' } as Semester);
  }
}

class FakeRequiredHoursService {
  getCalls: string[] = [];
  saveCalls: RequiredHours[] = [];
  config: RequiredHours = {
    semesterId: '', defaultMinutesPerMonth: 0, allGroups: true,
    order: 'MOST_EXPENSIVE_FIRST', groupRates: [], tiers: [],
  };
  get(semesterId: string) {
    this.getCalls.push(semesterId);
    return of(this.config);
  }
  save(_semesterId: string, dto: RequiredHours) {
    this.saveCalls.push(dto);
    return of(dto);
  }
}

class FakeAliquotConfigService {
  getCalls: string[] = [];
  saveCalls: AliquotConfig[] = [];
  config: AliquotConfig = { semesterId: '', stundenMode: 'NONE', kostenMode: 'NONE' };
  get(semesterId: string) {
    this.getCalls.push(semesterId);
    return of(this.config);
  }
  save(_semesterId: string, dto: AliquotConfig) {
    this.saveCalls.push(dto);
    return of(dto);
  }
}

class FakeCurrencyService {
  createCalls: CreateCurrencyRequest[] = [];
  currencies: Currency[] = [];
  getAll() {
    return of(this.currencies);
  }
  create(request: CreateCurrencyRequest) {
    this.createCalls.push(request);
    const created: Currency = { id: 'currency-new', ...request };
    this.currencies = [...this.currencies, created];
    return of(created);
  }
}

class FakeKostenDefinitionService {
  createCalls: CreateKostenDefinitionRequest[] = [];
  setActiveCalls: { id: string; active: boolean }[] = [];
  definitions: KostenDefinition[] = [];
  getAll() {
    return of(this.definitions);
  }
  create(request: CreateKostenDefinitionRequest) {
    this.createCalls.push(request);
    return of({
      id: 'def-new', label: request.label, active: true,
      currency: { id: request.currencyId, code: 'EUR', symbol: '€' },
    } as KostenDefinition);
  }
  setActive(id: string, active: boolean) {
    this.setActiveCalls.push({ id, active });
    return of({ id, label: 'x', active, currency: { id: 'c1', code: 'EUR', symbol: '€' } } as KostenDefinition);
  }
}

function makeComponent(overrides: {
  orgService?: FakeOrganisationService;
  fieldDefService?: FakeFieldDefinitionService;
  fieldInstanceService?: FakeFieldInstanceService;
  semesterService?: FakeSemesterService;
  currencyService?: FakeCurrencyService;
  kostenDefinitionService?: FakeKostenDefinitionService;
  requiredHoursService?: FakeRequiredHoursService;
  aliquotConfigService?: FakeAliquotConfigService;
} = {}): OrganisationComponent {
  const orgService = overrides.orgService ?? new FakeOrganisationService();
  const fieldDefService = overrides.fieldDefService ?? new FakeFieldDefinitionService();
  const fieldInstanceService = overrides.fieldInstanceService ?? new FakeFieldInstanceService();
  const semesterService = overrides.semesterService ?? new FakeSemesterService();
  const currencyService = overrides.currencyService ?? new FakeCurrencyService();
  const kostenDefinitionService = overrides.kostenDefinitionService ?? new FakeKostenDefinitionService();
  const requiredHoursService = overrides.requiredHoursService ?? new FakeRequiredHoursService();
  const aliquotConfigService = overrides.aliquotConfigService ?? new FakeAliquotConfigService();
  const fakeDialog = { open: () => ({ afterClosed: () => of(null) }) } as unknown as MatDialog;

  return new OrganisationComponent(
    orgService as unknown as OrganisationService,
    fieldDefService as unknown as FieldDefinitionService,
    fieldInstanceService as unknown as FieldInstanceService,
    semesterService as unknown as SemesterService,
    currencyService as unknown as CurrencyService,
    kostenDefinitionService as unknown as KostenDefinitionService,
    requiredHoursService as unknown as RequiredHoursService,
    aliquotConfigService as unknown as AliquotConfigService,
    fakeDialog,
  );
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
    component = makeComponent({ orgService, fieldDefService, fieldInstanceService });
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

describe('OrganisationComponent - Semester', () => {
  let component: OrganisationComponent;
  let semesterService: FakeSemesterService;

  beforeEach(() => {
    semesterService = new FakeSemesterService();
    component = makeComponent({ semesterService });
  });

  it('loads semesters on init', () => {
    semesterService.semesters = [
      { id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ];

    component.ngOnInit();

    expect(component.semesters.length).toBe(1);
  });

  it('derives the semester label from start/end years', () => {
    const label = component.getSemesterLabel({
      id: 's1', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z',
    });

    expect(label).toBe('2025/2026');
  });

  it('sends start and end when adding a semester', () => {
    component.ngOnInit();
    const start = new Date('2025-09-01T00:00:00Z');
    const end = new Date('2026-08-31T00:00:00Z');
    component.semesterForm.setValue({ start, end });

    component.addSemester();

    expect(semesterService.createCalls.length).toBe(1);
    expect(semesterService.createCalls[0].start).toBe(start.toISOString());
    expect(semesterService.createCalls[0].end).toBe(end.toISOString());
  });
});

describe('OrganisationComponent - Kosten-Definitionen', () => {
  let component: OrganisationComponent;
  let currencyService: FakeCurrencyService;
  let kostenDefinitionService: FakeKostenDefinitionService;

  beforeEach(() => {
    currencyService = new FakeCurrencyService();
    kostenDefinitionService = new FakeKostenDefinitionService();
    component = makeComponent({ currencyService, kostenDefinitionService });
  });

  it('loads currencies and kosten-definitions on init', () => {
    currencyService.currencies = [{ id: 'c1', code: 'EUR', symbol: '€' }];
    kostenDefinitionService.definitions = [
      { id: 'd1', label: 'Elternbeitrag', active: true, currency: { id: 'c1', code: 'EUR', symbol: '€' } },
    ];

    component.ngOnInit();

    expect(component.currencies.length).toBe(1);
    expect(component.kostenDefinitions.length).toBe(1);
  });

  it('creates a new currency and selects it in the definition form', () => {
    component.ngOnInit();
    component.newCurrencyForm.setValue({ code: 'CHF', symbol: 'Fr.' });

    component.addCurrency();

    expect(currencyService.createCalls).toEqual([{ code: 'CHF', symbol: 'Fr.' }]);
    expect(component.kostenDefForm.value.currencyId).toBe('currency-new');
  });

  it('sends label and currencyId when adding a kosten-definition', () => {
    component.ngOnInit();
    component.kostenDefForm.setValue({ label: 'Elternbeitrag', currencyId: 'c1' });

    component.addKostenDefinition();

    expect(kostenDefinitionService.createCalls).toEqual([{ label: 'Elternbeitrag', currencyId: 'c1' }]);
  });

  it('toggles a definition active flag', () => {
    kostenDefinitionService.definitions = [
      { id: 'd1', label: 'Elternbeitrag', active: true, currency: { id: 'c1', code: 'EUR', symbol: '€' } },
    ];
    component.ngOnInit();

    component.toggleKostenDefinitionActive({ id: 'd1', label: 'Elternbeitrag', active: true, currency: { id: 'c1', code: 'EUR', symbol: '€' } });

    expect(kostenDefinitionService.setActiveCalls).toEqual([{ id: 'd1', active: false }]);
  });
});

describe('OrganisationComponent - Zu leistende Stunden / Aliquot', () => {
  let component: OrganisationComponent;
  let aliquotConfigService: FakeAliquotConfigService;
  let requiredHoursService: FakeRequiredHoursService;

  beforeEach(() => {
    aliquotConfigService = new FakeAliquotConfigService();
    requiredHoursService = new FakeRequiredHoursService();
    component = makeComponent({ aliquotConfigService, requiredHoursService });
  });

  it('loads stunden mode and echoes back kosten mode when saving aliquot', () => {
    component.rhSelectedSemesterId = 'sem1';
    aliquotConfigService.config = { semesterId: 'sem1', stundenMode: 'PER_DAY', kostenMode: 'WHOLE_MONTH' };

    component.loadAliquot();

    expect(aliquotConfigService.getCalls).toEqual(['sem1']);
    expect(component.stundenMode).toBe('PER_DAY');
    expect(component.kostenMode).toBe('WHOLE_MONTH');

    component.stundenMode = 'NONE';
    component.saveAliquot();

    expect(aliquotConfigService.saveCalls.length).toBe(1);
    expect(aliquotConfigService.saveCalls[0]).toEqual({ semesterId: 'sem1', stundenMode: 'NONE', kostenMode: 'WHOLE_MONTH' });
  });

  it('builds one preview row per default plus one per tier', () => {
    component.rhAllGroups = true;
    component.rhDefaultHhmm = '08:00';
    component.rhTiers = [{ fromChild: 2, percent: 25 }];
    component.recomputeRhPreview();
    expect(component.rhPreview.length).toBe(2);

    component.rhTiers = [{ fromChild: 2, percent: 25 }, { fromChild: 3, percent: 50 }];
    component.recomputeRhPreview();
    expect(component.rhPreview.length).toBe(3);
  });

  it('rejects percent tiers outside 0..100 and does not save', () => {
    component.rhSelectedSemesterId = 'sem1';
    component.rhDefaultHhmm = '08:00';
    component.rhAllGroups = true;
    component.rhTiers = [{ fromChild: 2, percent: 120 }];

    component.saveRequiredHours();

    expect(requiredHoursService.saveCalls.length).toBe(0);
    expect(component.rhError).not.toBeNull();
  });

  it('befüllt beim Abhaken jede Gruppe mit dem bisherigen Default', () => {
    component.rhDefaultHhmm = '08:00';
    component.groupsDataSource.data = [
      { id: 'g1', definitionId: 'd1', fieldName: 'group', label: {}, jsonSchema: {}, required: false,
        value: { label: 'Käfergruppe', color: '#43a047' }, definitionOutdated: false },
      { id: 'g2', definitionId: 'd1', fieldName: 'group', label: {}, jsonSchema: {}, required: false,
        value: { label: 'Bärengruppe', color: '#fb8c00' }, definitionOutdated: false },
    ] as any;

    component.onRhAllGroupsChange(false);

    expect(component.rhGroupRates.length).toBe(2);
    expect(component.rhGroupRates[0].hhmm).toBe('08:00');
    expect(component.rhGroupRates[0].label).toBe('Käfergruppe');
  });

  it('blockiert das Speichern bei leerem Gruppenfeld', () => {
    requiredHoursService.saveCalls = [];
    component.rhSelectedSemesterId = 's1';
    component.rhDefaultHhmm = '08:00';
    component.rhAllGroups = false;
    component.rhGroupRates = [
      { groupInstanceId: 'g1', label: 'Käfergruppe', color: null, hhmm: '08:00' },
      { groupInstanceId: 'g2', label: 'Bärengruppe', color: null, hhmm: '' },
    ];

    component.saveRequiredHours();

    expect(requiredHoursService.saveCalls.length).toBe(0);
    expect(component.rhError).toContain('Bärengruppe');
  });

  it('speichert Gruppensätze und Reihenfolge', () => {
    requiredHoursService.saveCalls = [];
    component.rhSelectedSemesterId = 's1';
    component.rhDefaultHhmm = '08:00';
    component.rhAllGroups = false;
    component.rhOrder = 'LEAST_EXPENSIVE_FIRST';
    component.rhGroupRates = [{ groupInstanceId: 'g1', label: 'Käfergruppe', color: null, hhmm: '05:00' }];
    component.rhTiers = [{ fromChild: 2, percent: 25 }];

    component.saveRequiredHours();

    expect(requiredHoursService.saveCalls.length).toBe(1);
    expect(requiredHoursService.saveCalls[0].allGroups).toBe(false);
    expect(requiredHoursService.saveCalls[0].order).toBe('LEAST_EXPENSIVE_FIRST');
    expect(requiredHoursService.saveCalls[0].groupRates).toEqual([{ groupInstanceId: 'g1', minutesPerMonth: 300 }]);
    expect(requiredHoursService.saveCalls[0].tiers).toEqual([{ fromChild: 2, percent: 25 }]);
  });
});
