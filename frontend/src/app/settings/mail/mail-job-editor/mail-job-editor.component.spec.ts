import { of } from 'rxjs';
import { MailJobEditorComponent } from './mail-job-editor.component';
import { MailJobService } from '../../../shared/services/mail-job.service';
import { MailJob, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate } from '../../../shared/models/mail-template.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { OrganisationDTO } from '../../../shared/models/organisation.model';

class FakeMailJobService {
  jobs: MailJob[] = [
    {
      id: 'j1', name: 'Willkommen-Job', templateId: 't1', subject: 'Willkommen',
      senderAccountId: 'acc1', cron: '0 0 8 * * ?', recipientMode: 'ALL_PARENTS',
      recipientGroupDefinitionIds: [], active: false, lastRunAt: null, lastRunStatus: null,
      lastRunError: null, createdAt: '2026-01-01', updatedAt: '2026-01-01',
    },
  ];
  createCalls: SaveMailJobRequest[] = [];
  updateCalls: { id: string; request: SaveMailJobRequest }[] = [];
  deleteCalls: string[] = [];
  activateCalls: string[] = [];
  deactivateCalls: string[] = [];

  list() {
    return of(this.jobs);
  }
  create(request: SaveMailJobRequest) {
    this.createCalls.push(request);
    return of({ id: 'j2', ...request, active: false, lastRunAt: null, lastRunStatus: null, lastRunError: null, createdAt: '2026-01-02', updatedAt: '2026-01-02' } as MailJob);
  }
  update(id: string, request: SaveMailJobRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, active: false, lastRunAt: null, lastRunStatus: null, lastRunError: null, createdAt: '2026-01-01', updatedAt: '2026-01-02' } as MailJob);
  }
  delete(id: string) {
    this.deleteCalls.push(id);
    return of(undefined);
  }
  activate(id: string) {
    this.activateCalls.push(id);
    return of({} as MailJob);
  }
  deactivate(id: string) {
    this.deactivateCalls.push(id);
    return of({} as MailJob);
  }
}

class FakeMailTemplateService {
  templates: MailTemplate[] = [
    { id: 't1', name: 'Willkommen', bodyHtml: '<p>Hallo</p>', createdAt: '2026-01-01', updatedAt: '2026-01-01' },
  ];
  list() {
    return of(this.templates);
  }
}

class FakeMailAccountService {
  accounts: MailAccount[] = [{ id: 'acc1', fromAddress: 'kita@example.test', fromName: 'Kita', enabled: true }];
  list() {
    return of(this.accounts);
  }
}

class FakeOrganisationService {
  org: OrganisationDTO = {
    id: 'org1',
    tag: 'groups',
    definitions: [
      { id: 'g1', fieldName: 'group', label: { de: 'Rote Gruppe' }, jsonSchema: {}, required: false },
      { id: 'g2', fieldName: 'group', label: { de: 'Blaue Gruppe' }, jsonSchema: {}, required: false },
    ],
    entries: [],
  };
  getByTag(tag: string) {
    return of(this.org);
  }
}

describe('MailJobEditorComponent', () => {
  let component: MailJobEditorComponent;
  let jobService: FakeMailJobService;
  let templateService: FakeMailTemplateService;
  let accountService: FakeMailAccountService;
  let organisationService: FakeOrganisationService;

  beforeEach(() => {
    jobService = new FakeMailJobService();
    templateService = new FakeMailTemplateService();
    accountService = new FakeMailAccountService();
    organisationService = new FakeOrganisationService();
    component = new MailJobEditorComponent(
      jobService as unknown as MailJobService,
      templateService as unknown as MailTemplateService,
      accountService as unknown as MailAccountService,
      organisationService as unknown as OrganisationService,
    );
    component.ngOnInit();
  });

  it('loads and lists jobs', () => {
    expect(component.jobs.length).toBe(1);
    expect(component.jobs[0].name).toBe('Willkommen-Job');
  });

  it('populates template and sender dropdowns from services', () => {
    expect(component.templates.length).toBe(1);
    expect(component.accounts.length).toBe(1);
  });

  it('saving a new job calls create', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
    });

    component.save();

    expect(jobService.createCalls.length).toBe(1);
    expect(jobService.createCalls[0].name).toBe('Neu');
  });

  it('saving an existing (selected) job calls update', () => {
    component.selectForEdit(jobService.jobs[0]);
    component.form.patchValue({ name: 'Geändert' });

    component.save();

    expect(jobService.updateCalls.length).toBe(1);
    expect(jobService.updateCalls[0].id).toBe('j1');
  });

  it('delete calls service delete', () => {
    component.delete(jobService.jobs[0]);

    expect(jobService.deleteCalls).toEqual(['j1']);
  });

  it('renders group checkboxes from the organisation service', () => {
    expect(component.groups.length).toBe(2);
    expect(component.groups[0].label['de']).toBe('Rote Gruppe');
  });

  it('selecting groups sets GROUPS mode and populates recipientGroupDefinitionIds', () => {
    component.toggleGroup('g1', true);
    component.toggleGroup('g2', true);

    expect(component.form.value.recipientMode).toBe('GROUPS');
    expect(component.form.value.recipientGroupDefinitionIds).toEqual(['g1', 'g2']);
  });

  it('selecting ALL clears group selection and sets ALL_PARENTS mode', () => {
    component.toggleGroup('g1', true);

    component.selectAllParents();

    expect(component.form.value.recipientMode).toBe('ALL_PARENTS');
    expect(component.form.value.recipientGroupDefinitionIds).toEqual([]);
  });

  it('selecting a preset sets the cron control value', () => {
    component.selectCronPreset('0 0 8 ? * MON');

    expect(component.form.value.cron).toBe('0 0 8 ? * MON');
  });

  it('the advanced toggle reveals the raw cron field', () => {
    expect(component.showAdvancedCron).toBeFalse();

    component.toggleAdvancedCron();

    expect(component.showAdvancedCron).toBeTrue();
  });

  it('the raw cron field edits the same control as presets', () => {
    component.selectCronPreset('0 0 8 * * ?');
    component.form.patchValue({ cron: '0 0 9 * * ?' }); // simulates typing in the raw field

    expect(component.form.value.cron).toBe('0 0 9 * * ?');
  });

  it('toggling an inactive job calls activate', () => {
    component.toggleActive({ ...jobService.jobs[0], active: false });

    expect(jobService.activateCalls).toEqual(['j1']);
    expect(jobService.deactivateCalls).toEqual([]);
  });

  it('toggling an active job calls deactivate', () => {
    component.toggleActive({ ...jobService.jobs[0], active: true });

    expect(jobService.deactivateCalls).toEqual(['j1']);
    expect(jobService.activateCalls).toEqual([]);
  });

  it('displays a distinct style for non-SUCCESS statuses', () => {
    expect(component.statusClass('SUCCESS')).toBe('status-success');
    expect(component.statusClass('NO_RECIPIENTS')).toBe('status-attention');
    expect(component.statusClass('SKIPPED_OVERLAP')).toBe('status-attention');
    expect(component.statusClass('FAILED')).toBe('status-attention');
    expect(component.statusClass('PARTIAL')).toBe('status-attention');
  });
});
