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
      senderAccountId: 'acc1', cron: '0 0 8 * * ?', allParents: true,
      recipientSelections: [], active: false, lastRunAt: null, lastRunStatus: null,
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
  accounts: MailAccount[] = [{
    id: 'acc1', name: 'Haupt', host: 'smtp.example.test', port: 587,
    encryption: 'STARTTLS', username: '', fromAddress: 'kita@example.test',
    fromName: 'Kita', enabled: true, passwordSet: false,
  }];
  list() {
    return of(this.accounts);
  }
}

class FakeOrganisationService {
  orgs: Record<string, OrganisationDTO> = {
    groups: {
      id: 'org-groups', tag: 'groups', entries: [],
      definitions: [{ id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false }],
    },
    'parent-teams': {
      id: 'org-teams', tag: 'parent-teams', entries: [],
      definitions: [{ id: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false }],
    },
    board: {
      id: 'org-board', tag: 'board', entries: [],
      definitions: [{ id: 'def-board', fieldName: 'board', label: { de: 'Vorstand' }, jsonSchema: {}, required: false }],
    },
    'parent-team-roles': {
      id: 'org-team-roles', tag: 'parent-team-roles', entries: [],
      definitions: [{ id: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false }],
    },
    'board-roles': {
      id: 'org-board-roles', tag: 'board-roles', entries: [],
      definitions: [{ id: 'def-board-role', fieldName: 'board-role', label: { de: 'Vorstandsrollen' }, jsonSchema: {}, required: false }],
    },
  };
  getByTag(tag: string) {
    return of(this.orgs[tag]);
  }
}

class FakeFieldInstanceService {
  byDefinition: Record<string, FieldInstanceDTO[]> = {
    'def-group': [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
      { id: 'g2', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Blaue Gruppe' }, definitionOutdated: false },
    ],
    'def-team': [
      { id: 't1', definitionId: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false, value: { label: 'Gartenteam' }, definitionOutdated: false },
    ],
    'def-board': [
      { id: 'b1', definitionId: 'def-board', fieldName: 'board', label: { de: 'Vorstand' }, jsonSchema: {}, required: false, value: { label: 'Vorstand' }, definitionOutdated: false },
    ],
    'def-team-role': [
      { id: 'tr1', definitionId: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false, value: { label: 'Teamleitung' }, definitionOutdated: false },
    ],
    'def-board-role': [
      { id: 'br1', definitionId: 'def-board-role', fieldName: 'board-role', label: { de: 'Vorstandsrollen' }, jsonSchema: {}, required: false, value: { label: 'Obfrau' }, definitionOutdated: false },
    ],
  };
  listByDefinitionId(definitionId: string) {
    return of(this.byDefinition[definitionId] ?? []);
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

  it('loads all five recipient pools on init', () => {
    expect(component.groups.map((g) => g.id)).toEqual(['g1', 'g2']);
    expect(component.parentTeams.map((t) => t.id)).toEqual(['t1']);
    expect(component.boardTeams.map((t) => t.id)).toEqual(['b1']);
    expect(component.teamRoles.map((r) => r.id)).toEqual(['tr1']);
    expect(component.boardRoles.map((r) => r.id)).toEqual(['br1']);
  });

  it('formats the display label of a pickable entry', () => {
    expect(component.instanceLabel(component.groups[0])).toBe('Rote Gruppe');
    expect(component.instanceLabel(component.groups[1])).toBe('Blaue Gruppe');
  });

  it('maps encoded option values to recipientSelections on save', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Job', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
      allParents: false,
    });
    component.onRecipientSelectionChange(['GROUP:g1', 'TEAM:b1', 'ROLE:tr1']);

    component.save();

    expect(jobService.createCalls.length).toBe(1);
    expect(jobService.createCalls[0].allParents).toBeFalse();
    expect(jobService.createCalls[0].recipientSelections).toEqual([
      { kind: 'GROUP', fieldInstanceId: 'g1' },
      { kind: 'TEAM', fieldInstanceId: 'b1' },
      { kind: 'ROLE', fieldInstanceId: 'tr1' },
    ]);
  });

  it('decodes an existing job back into option values', () => {
    component.selectForEdit({
      ...jobService.jobs[0],
      allParents: false,
      recipientSelections: [
        { kind: 'TEAM', fieldInstanceId: 't1' },
        { kind: 'ROLE', fieldInstanceId: 'br1' },
      ],
    });

    expect(component.recipientOptionValues).toEqual(['TEAM:t1', 'ROLE:br1']);
    expect(component.form.value.allParents).toBeFalse();
  });

  it('keeps the selection when all-parents is toggled on and off', () => {
    component.newJob();
    component.onRecipientSelectionChange(['TEAM:t1']);

    component.form.patchValue({ allParents: true });
    expect(component.recipientOptionValues).toEqual(['TEAM:t1']);

    component.form.patchValue({ allParents: false });
    expect(component.recipientOptionValues).toEqual(['TEAM:t1']);
  });

  it('sends an empty selection when all parents is set', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Job', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
      allParents: true,
    });
    component.onRecipientSelectionChange(['TEAM:t1']);

    component.save();

    expect(jobService.createCalls[0].allParents).toBeTrue();
    expect(jobService.createCalls[0].recipientSelections).toEqual([]);
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
