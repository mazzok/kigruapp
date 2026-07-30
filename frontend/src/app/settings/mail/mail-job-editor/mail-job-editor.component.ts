import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MailJobService } from '../../../shared/services/mail-job.service';
import { MailJob, RecipientKind, RecipientSelection, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
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
    MatCheckboxModule, MatSlideToggleModule, MatIconModule, MatTooltipModule,
    CronScheduleBuilderComponent,
  ],
  templateUrl: './mail-job-editor.component.html',
  styleUrl: './mail-job-editor.component.scss',
})
export class MailJobEditorComponent implements OnInit {
  jobs: MailJob[] = [];
  templates: MailTemplate[] = [];
  accounts: MailAccount[] = [];
  /** Selectable pools; each is the field instances of that pool's single template definition. */
  groups: FieldInstanceDTO[] = [];
  parentTeams: FieldInstanceDTO[] = [];
  boardTeams: FieldInstanceDTO[] = [];
  teamRoles: FieldInstanceDTO[] = [];
  boardRoles: FieldInstanceDTO[] = [];

  /**
   * Currently picked options, encoded as "<KIND>:<fieldInstanceId>". The kind
   * travels in the value because board and parent teams sit in separate
   * optgroups but resolve to the same kind.
   */
  recipientOptionValues: string[] = [];

  /** Counts pool loads so we know when it is safe to prune stale selections (see {@link pruneStaleRecipientSelections}). */
  private poolsLoadedCount = 0;
  private static readonly POOL_COUNT = 5;

  selectedId: string | null = null;
  /** When false the form is hidden and a placeholder is shown instead. */
  editing = false;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    templateId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    cron: new FormControl(DEFAULT_CRON, Validators.required),
    allParents: new FormControl<boolean>(true, { nonNullable: true }),
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
    this.loadPool('groups', 'group', (i) => (this.groups = i));
    this.loadPool('parent-teams', 'parent-team', (i) => (this.parentTeams = i));
    this.loadPool('board', 'board', (i) => (this.boardTeams = i));
    this.loadPool('parent-team-roles', 'parent-team-role', (i) => (this.teamRoles = i));
    this.loadPool('board-roles', 'board-role', (i) => (this.boardRoles = i));
  }

  /**
   * Each pool is an organisation tag holding exactly one active template
   * definition; the pickable entries are that definition's field instances.
   */
  private loadPool(tag: string, fieldName: string, assign: (instances: FieldInstanceDTO[]) => void): void {
    this.organisationService.getByTag(tag).subscribe({
      next: (org) => {
        const templateDef = org?.definitions?.find((d) => d.fieldName === fieldName && !d.outdatedAt);
        if (!templateDef?.id) {
          this.onPoolLoaded();
          return;
        }
        this.fieldInstanceService.listByDefinitionId(templateDef.id).subscribe((instances) => {
          assign(instances);
          this.onPoolLoaded();
        });
      },
      error: () => {
        assign([]);
        this.onPoolLoaded();
      },
    });
  }

  /** Once every pool has loaded, drop any selection whose instance no longer exists in its pool. */
  private onPoolLoaded(): void {
    this.poolsLoadedCount++;
    if (this.poolsLoadedCount === MailJobEditorComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
  }

  /**
   * Removes recipientOptionValues entries whose "<KIND>:<id>" no longer resolves
   * to an instance in the corresponding loaded pool (e.g. a team or role that was
   * deleted after the job was saved). Without this, a stale value stays selected
   * internally but renders as unselected (mat-select finds no matching option),
   * and every future save silently re-sends it, causing an opaque 400.
   */
  private pruneStaleRecipientSelections(): void {
    const validValues = new Set<string>([
      ...this.groups.map((i) => this.optionValue('GROUP', i.id ?? '')),
      ...this.parentTeams.map((i) => this.optionValue('TEAM', i.id ?? '')),
      ...this.boardTeams.map((i) => this.optionValue('TEAM', i.id ?? '')),
      ...this.teamRoles.map((i) => this.optionValue('ROLE', i.id ?? '')),
      ...this.boardRoles.map((i) => this.optionValue('ROLE', i.id ?? '')),
    ]);
    this.recipientOptionValues = this.recipientOptionValues.filter((v) => validValues.has(v));
  }

  onRecipientSelectionChange(values: string[]): void {
    this.recipientOptionValues = values ?? [];
  }

  /** Encodes one pickable entry as the option's value. */
  optionValue(kind: RecipientKind, instanceId: string): string {
    return `${kind}:${instanceId}`;
  }

  private toSelections(values: string[]): RecipientSelection[] {
    return values.map((v) => {
      const separator = v.indexOf(':');
      return {
        kind: v.slice(0, separator) as RecipientKind,
        fieldInstanceId: v.slice(separator + 1),
      };
    });
  }

  /** Display name of a pickable entry (its value's label), with a safe fallback. */
  instanceLabel(i: FieldInstanceDTO): string {
    const label = (i.value as { label?: string } | null)?.label;
    return label || i.label?.['de'] || i.fieldName;
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
      allParents: job.allParents,
    });
    this.recipientOptionValues = (job.recipientSelections ?? [])
      .map((s) => this.optionValue(s.kind, s.fieldInstanceId));
    // Pools may already be loaded (typical case); prune now so a stale selection
    // never flashes as "selected". If pools are still loading, onPoolLoaded()
    // prunes once they finish.
    if (this.poolsLoadedCount === MailJobEditorComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({
      name: '', templateId: '', subject: '', senderAccountId: '', cron: DEFAULT_CRON,
      allParents: true,
    });
    this.recipientOptionValues = [];
  }

  /** Close the editor and return to the placeholder (no job selected). */
  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({
      name: '', templateId: '', subject: '', senderAccountId: '', cron: DEFAULT_CRON,
      allParents: true,
    });
    this.recipientOptionValues = [];
  }

  save(): void {
    const v = this.form.value;
    const request: SaveMailJobRequest = {
      name: v.name ?? '',
      templateId: v.templateId ?? '',
      subject: v.subject ?? '',
      senderAccountId: v.senderAccountId ?? '',
      cron: v.cron ?? '',
      allParents: v.allParents ?? true,
      recipientSelections: v.allParents ? [] : this.toSelections(this.recipientOptionValues),
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
