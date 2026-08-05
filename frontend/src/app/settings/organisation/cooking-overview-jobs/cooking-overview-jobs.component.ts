import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CookingOverviewJobService } from '../../../shared/services/cooking-overview-job.service';
import { CookingOverviewJob, SaveCookingOverviewJobRequest } from '../../../shared/models/cooking-overview-job.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { RecipientKind, RecipientSelection } from '../../../shared/models/mail-job.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailTemplateFormComponent } from '../../mail/mail-template-editor/mail-template-form.component';
import { CronScheduleBuilderComponent } from '../../mail/mail-job-editor/cron-schedule-builder.component';

const DEFAULT_CRON = '0 0 8 * * ?';

export interface TeamWithRoles {
  team: FieldInstanceDTO;
  roles: FieldInstanceDTO[];
}

/**
 * Kochdienst-Uebersichtsjobs: links die Jobs, rechts Job-Formular (Cron +
 * Empfaenger, wie MailJobEditorComponent) und die fest zugeordnete Vorlage.
 * Beides geht in einem Request an den Server.
 */
@Component({
  selector: 'app-cooking-overview-jobs',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatSlideToggleModule, MatTooltipModule,
    MailTemplateFormComponent, CronScheduleBuilderComponent,
  ],
  templateUrl: './cooking-overview-jobs.component.html',
  styleUrl: './cooking-overview-jobs.component.scss',
})
export class CookingOverviewJobsComponent implements OnInit {
  jobs: CookingOverviewJob[] = [];
  accounts: MailAccount[] = [];

  /** Selectable pools; each is the field instances of that pool's single template definition. */
  groups: FieldInstanceDTO[] = [];
  parentTeams: FieldInstanceDTO[] = [];
  boardTeams: FieldInstanceDTO[] = [];
  teamRoles: FieldInstanceDTO[] = [];
  boardRoles: FieldInstanceDTO[] = [];

  /** Jedes Team, zusammen mit den Rollen, die zu ihm gehoeren (siehe {@link buildTeamGroups}). */
  parentTeamGroups: TeamWithRoles[] = [];
  boardTeamGroups: TeamWithRoles[] = [];

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
  editing = false;

  /** Wert fuer die Maske (Eingang) und der zuletzt gemeldete Wert (Ausgang). */
  templateValue = { name: '', bodyHtml: '' };
  private currentTemplate = { name: '', bodyHtml: '' };

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    cron: new FormControl(DEFAULT_CRON, Validators.required),
    allParents: new FormControl<boolean>(true, { nonNullable: true }),
    active: new FormControl<boolean>(false, { nonNullable: true }),
  });

  constructor(
    private jobService: CookingOverviewJobService,
    private mailAccountService: MailAccountService,
    private organisationService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
    this.loadPool('groups', 'group', (i) => (this.groups = i));
    this.loadPool('parent-teams', 'parent-team', (i) => (this.parentTeams = i));
    this.loadPool('board', 'board', (i) => (this.boardTeams = i));
    this.loadPool('parent-team-roles', 'parent-team-role', (i) => (this.teamRoles = i));
    this.loadPool('board-roles', 'board-role', (i) => (this.boardRoles = i));
  }

  load(): void {
    this.jobService.list().subscribe((jobs) => (this.jobs = jobs));
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
    this.buildTeamGroups();
    this.poolsLoadedCount++;
    if (this.poolsLoadedCount === CookingOverviewJobsComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
  }

  private buildTeamGroups(): void {
    this.parentTeamGroups = this.parentTeams.map((team) => ({
      team,
      roles: this.teamRoles.filter((r) => this.roleTeamInstanceId(r) === team.id),
    }));
    this.boardTeamGroups = this.boardTeams.map((team) => ({
      team,
      roles: this.boardRoles,
    }));
  }

  private roleTeamInstanceId(role: FieldInstanceDTO): string | undefined {
    return (role.value as { teamInstanceId?: string } | null)?.teamInstanceId;
  }

  /**
   * Removes recipientOptionValues entries whose "<KIND>:<id>" no longer resolves
   * to an instance in the corresponding loaded pool.
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

  onTemplateChange(value: { name: string; bodyHtml: string }): void {
    this.currentTemplate = value;
  }

  get canSave(): boolean {
    return this.form.valid
      && this.currentTemplate.name.trim().length > 0
      && this.currentTemplate.bodyHtml.trim().length > 0;
  }

  selectForEdit(job: CookingOverviewJob): void {
    this.selectedId = job.id;
    this.editing = true;
    this.form.patchValue({
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      cron: job.cron,
      allParents: job.allParents,
      active: job.active,
    });
    this.recipientOptionValues = (job.recipientSelections ?? [])
      .map((s) => this.optionValue(s.kind, s.fieldInstanceId));
    if (this.poolsLoadedCount === CookingOverviewJobsComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
    this.templateValue = { name: job.templateName, bodyHtml: job.templateBodyHtml };
    this.currentTemplate = { ...this.templateValue };
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({
      name: '', senderAccountId: '', subject: '', cron: DEFAULT_CRON, allParents: true, active: false,
    });
    this.recipientOptionValues = [];
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({
      name: '', senderAccountId: '', subject: '', cron: DEFAULT_CRON, allParents: true, active: false,
    });
    this.recipientOptionValues = [];
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  private toRequest(): SaveCookingOverviewJobRequest {
    const v = this.form.value;
    return {
      name: v.name ?? '',
      senderAccountId: v.senderAccountId ?? '',
      subject: v.subject ?? '',
      cron: v.cron ?? DEFAULT_CRON,
      allParents: v.allParents ?? true,
      recipientSelections: v.allParents ? [] : this.toSelections(this.recipientOptionValues),
      active: v.active ?? false,
      templateName: this.currentTemplate.name,
      templateBodyHtml: this.currentTemplate.bodyHtml,
    };
  }

  save(): void {
    const request = this.toRequest();
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.jobService.update(this.selectedId, request)
      : this.jobService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Übersichtsjob aktualisiert' : 'Übersichtsjob gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  /** Der Schalter in der Liste speichert den Job unveraendert mit gekipptem active. */
  toggleActive(job: CookingOverviewJob): void {
    const activating = !job.active;
    this.jobService.update(job.id, {
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      cron: job.cron,
      allParents: job.allParents,
      recipientSelections: job.recipientSelections,
      active: activating,
      templateName: job.templateName,
      templateBodyHtml: job.templateBodyHtml,
    }).subscribe({
      next: () => {
        this.notify.success(activating ? 'Übersichtsjob aktiviert' : 'Übersichtsjob deaktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load();
      },
    });
  }

  delete(job: CookingOverviewJob): void {
    this.jobService.delete(job.id).subscribe({
      next: () => {
        this.notify.success('Übersichtsjob gelöscht');
        if (this.selectedId === job.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
