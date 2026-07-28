import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MailJobEditorComponent } from './mail-job-editor.component';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailJobService } from '../../../shared/services/mail-job.service';
import { MailJob, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate } from '../../../shared/models/mail-template.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { OrganisationDTO } from '../../../shared/models/organisation.model';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';

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
    // A single "group" template definition; the actual groups are its instances.
    definitions: [
      { id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false },
    ],
    entries: [],
  };
  getByTag(tag: string) {
    return of(this.org);
  }
}

class FakeFieldInstanceService {
  instances: FieldInstanceDTO[] = [
    { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
    { id: 'g2', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Blaue Gruppe' }, definitionOutdated: false },
  ];
  listByDefinitionIdCalls: string[] = [];
  listByDefinitionId(definitionId: string) {
    this.listByDefinitionIdCalls.push(definitionId);
    return of(this.instances);
  }
}

class FakeNotificationService {
  successCalls: string[] = [];
  errorCalls: string[] = [];
  success(message: string) {
    this.successCalls.push(message);
  }
  error(message: string) {
    this.errorCalls.push(message);
  }
  extractError(err: unknown) {
    return err instanceof HttpErrorResponse ? String(err.error) : 'error';
  }
}

describe('MailJobEditorComponent', () => {
  let component: MailJobEditorComponent;
  let jobService: FakeMailJobService;
  let templateService: FakeMailTemplateService;
  let accountService: FakeMailAccountService;
  let organisationService: FakeOrganisationService;
  let fieldInstanceService: FakeFieldInstanceService;
  let notify: FakeNotificationService;

  beforeEach(() => {
    jobService = new FakeMailJobService();
    templateService = new FakeMailTemplateService();
    accountService = new FakeMailAccountService();
    organisationService = new FakeOrganisationService();
    fieldInstanceService = new FakeFieldInstanceService();
    notify = new FakeNotificationService();
    component = new MailJobEditorComponent(
      jobService as unknown as MailJobService,
      templateService as unknown as MailTemplateService,
      accountService as unknown as MailAccountService,
      organisationService as unknown as OrganisationService,
      fieldInstanceService as unknown as FieldInstanceService,
      notify as unknown as NotificationService,
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
    expect(notify.successCalls).toEqual(['Job gespeichert']);
  });

  it('shows an error popup when saving fails (surfacing the backend reason)', () => {
    jobService.create = () =>
      throwError(() => new HttpErrorResponse({ status: 400, error: 'invalid cron expression: nope' }));
    component.newJob();
    component.form.patchValue({
      name: 'Neu', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: 'nope',
    });

    component.save();

    expect(notify.errorCalls).toEqual(['invalid cron expression: nope']);
    expect(notify.successCalls).toEqual([]);
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

  it('loads the actual groups (field instances of the "group" template), not the template itself', () => {
    expect(fieldInstanceService.listByDefinitionIdCalls).toEqual(['def-group']);
    expect(component.groups.length).toBe(2);
    expect(component.groupLabel(component.groups[0])).toBe('Rote Gruppe');
    expect(component.groupLabel(component.groups[1])).toBe('Blaue Gruppe');
  });

  it('selecting groups sets GROUPS mode and populates recipientGroupDefinitionIds', () => {
    component.onGroupsChange(['g1', 'g2']);

    expect(component.form.value.recipientMode).toBe('GROUPS');
    expect(component.form.value.recipientGroupDefinitionIds).toEqual(['g1', 'g2']);
  });

  it('clearing the group selection falls back to ALL_PARENTS mode', () => {
    component.onGroupsChange(['g1']);

    component.onGroupsChange([]);

    expect(component.form.value.recipientMode).toBe('ALL_PARENTS');
    expect(component.form.value.recipientGroupDefinitionIds).toEqual([]);
  });

  it('selecting ALL clears group selection and sets ALL_PARENTS mode', () => {
    component.onGroupsChange(['g1']);

    component.selectAllParents();

    expect(component.form.value.recipientMode).toBe('ALL_PARENTS');
    expect(component.form.value.recipientGroupDefinitionIds).toEqual([]);
  });

  it('starts with the editor closed and opens it via newJob / selectForEdit', () => {
    expect(component.editing).toBe(false);

    component.newJob();
    expect(component.editing).toBe(true);

    component.closeEditor();
    expect(component.editing).toBe(false);

    component.selectForEdit(jobService.jobs[0]);
    expect(component.editing).toBe(true);
  });

  it('closes the editor after a successful save', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
    });

    component.save();

    expect(component.editing).toBe(false);
  });

  it('shows the last-run timestamp as DD.MM.YYYY HH:MM in local time', () => {
    const iso = '2026-03-09T07:05:00Z';
    const d = new Date(iso);
    const p = (n: number) => `${n}`.padStart(2, '0');
    const expected = `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
    const job = { ...jobService.jobs[0], lastRunStatus: 'SUCCESS', lastRunAt: iso };
    expect(component.statusLabel(job)).toBe(expected);
  });

  it('treats a timezone-less timestamp as UTC (identical to an explicit Z)', () => {
    const naive = { ...jobService.jobs[0], lastRunStatus: 'SUCCESS', lastRunAt: '2026-03-09T07:05:00' };
    const withZ = { ...jobService.jobs[0], lastRunStatus: 'SUCCESS', lastRunAt: '2026-03-09T07:05:00Z' };
    expect(component.statusLabel(naive)).toBe(component.statusLabel(withZ));
  });

  it('a new job starts with a valid default cron so the form is submittable', () => {
    component.newJob();

    expect(component.form.value.cron).toBe('0 0 8 * * ?');
  });

  it('the schedule builder writes the generated cron into the form control', () => {
    // simulates the CronScheduleBuilder (ControlValueAccessor) emitting a value
    component.form.controls.cron.setValue('0 30 7 ? * MON,WED');

    expect(component.form.value.cron).toBe('0 30 7 ? * MON,WED');
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
