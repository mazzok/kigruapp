import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MailJobService } from '../../../shared/services/mail-job.service';
import { MailJob, RecipientMode, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate } from '../../../shared/models/mail-template.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { CronScheduleBuilderComponent } from './cron-schedule-builder.component';

/** Valid Quartz default: daily at 08:00. Keeps the form valid before the user touches the schedule. */
const DEFAULT_CRON = '0 0 8 * * ?';

@Component({
  selector: 'app-mail-job-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatListModule,
    MatRadioModule, MatSlideToggleModule, MatIconModule, MatTooltipModule,
    CronScheduleBuilderComponent,
  ],
  templateUrl: './mail-job-editor.component.html',
  styleUrl: './mail-job-editor.component.scss',
})
export class MailJobEditorComponent implements OnInit {
  jobs: MailJob[] = [];
  templates: MailTemplate[] = [];
  accounts: MailAccount[] = [];
  /** Selectable groups = the field instances of the "group" template definition. */
  groups: FieldInstanceDTO[] = [];
  selectedId: string | null = null;
  /** When false the form is hidden and a placeholder is shown instead. */
  editing = false;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    templateId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    cron: new FormControl(DEFAULT_CRON, Validators.required),
    recipientMode: new FormControl<RecipientMode>('ALL_PARENTS', { nonNullable: true }),
    recipientGroupDefinitionIds: new FormControl<string[]>([], { nonNullable: true }),
  });

  constructor(
    private mailJobService: MailJobService,
    private mailTemplateService: MailTemplateService,
    private mailAccountService: MailAccountService,
    private organisationService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
    // The actual groups (Bären, Löwen, …) are field instances of the single
    // "group" template definition — mirror the pattern used across the app.
    this.organisationService.getByTag('groups').subscribe((org) => {
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef?.id) return;
      this.fieldInstanceService.listByDefinitionId(templateDef.id).subscribe((instances) => (this.groups = instances));
    });
  }

  /** Groups picked in the dropdown; empty array switches back to "all parents". */
  onGroupsChange(ids: string[]): void {
    const next = ids ?? [];
    this.form.patchValue({
      recipientMode: next.length ? 'GROUPS' : 'ALL_PARENTS',
      recipientGroupDefinitionIds: next,
    });
  }

  selectAllParents(): void {
    this.form.patchValue({ recipientMode: 'ALL_PARENTS', recipientGroupDefinitionIds: [] });
  }

  /** Display name of a group instance (its value's label), with a safe fallback. */
  groupLabel(g: FieldInstanceDTO): string {
    const label = (g.value as { label?: string } | null)?.label;
    return label || g.label?.['de'] || g.fieldName;
  }

  toggleActive(job: MailJob): void {
    const activating = !job.active;
    const action$ = job.active ? this.mailJobService.deactivate(job.id) : this.mailJobService.activate(job.id);
    action$.subscribe({
      next: () => {
        this.notify.success(activating ? 'Job aktiviert' : 'Job deaktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load();
      },
    });
  }

  /** Visual distinction for non-SUCCESS outcomes (R12/Observability). */
  statusClass(status: string | null): string {
    if (!status) return '';
    return status === 'SUCCESS' ? 'status-success' : 'status-attention';
  }

  /** Chip label: WHEN the job last ran, as DD.MM.YYYY HH:MM. */
  statusLabel(job: MailJob): string {
    return this.formatRunTime(job.lastRunAt) || (job.lastRunStatus ?? '');
  }

  /** Format an ISO timestamp as DD.MM.YYYY HH:MM in the browser's local time (24h). */
  private formatRunTime(iso: string | null): string {
    if (!iso) return '';
    // Backend timestamps are UTC Instants. If the value carries no timezone
    // marker, parse it as UTC (append Z) so the getters below convert to local.
    const hasTz = /[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso);
    const d = new Date(hasTz ? iso : `${iso}Z`);
    if (isNaN(d.getTime())) return '';
    const p = (n: number) => `${n}`.padStart(2, '0');
    return `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }

  /** Tooltip detail for a job's last run: outcome and, on failure, the error. */
  statusTooltip(job: MailJob): string {
    if (!job.lastRunStatus) return '';
    const outcome = job.lastRunStatus === 'SUCCESS' ? 'Letzter Lauf erfolgreich' : 'Letzter Lauf fehlgeschlagen';
    return job.lastRunError ? `${outcome} — ${job.lastRunError}` : outcome;
  }

  load(): void {
    this.mailJobService.list().subscribe((jobs) => {
      this.jobs = jobs;
    });
  }

  selectForEdit(job: MailJob): void {
    this.selectedId = job.id;
    this.editing = true;
    this.form.patchValue({
      name: job.name,
      templateId: job.templateId,
      subject: job.subject,
      senderAccountId: job.senderAccountId,
      cron: job.cron,
      recipientMode: job.recipientMode,
      recipientGroupDefinitionIds: job.recipientGroupDefinitionIds,
    });
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({
      name: '', templateId: '', subject: '', senderAccountId: '', cron: DEFAULT_CRON,
      recipientMode: 'ALL_PARENTS', recipientGroupDefinitionIds: [],
    });
  }

  /** Close the editor and return to the placeholder (no job selected). */
  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({
      name: '', templateId: '', subject: '', senderAccountId: '', cron: DEFAULT_CRON,
      recipientMode: 'ALL_PARENTS', recipientGroupDefinitionIds: [],
    });
  }

  save(): void {
    const v = this.form.value;
    const request: SaveMailJobRequest = {
      name: v.name ?? '',
      templateId: v.templateId ?? '',
      subject: v.subject ?? '',
      senderAccountId: v.senderAccountId ?? '',
      cron: v.cron ?? '',
      recipientMode: v.recipientMode ?? 'ALL_PARENTS',
      recipientGroupDefinitionIds: v.recipientGroupDefinitionIds ?? [],
    };
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.mailJobService.update(this.selectedId, request)
      : this.mailJobService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Job aktualisiert' : 'Job gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(job: MailJob): void {
    this.mailJobService.delete(job.id).subscribe({
      next: () => {
        this.notify.success('Job gelöscht');
        if (this.selectedId === job.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
