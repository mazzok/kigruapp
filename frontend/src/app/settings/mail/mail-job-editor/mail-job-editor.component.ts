import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MailJobService } from '../../../shared/services/mail-job.service';
import { MailJob, RecipientMode, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate } from '../../../shared/models/mail-template.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldDefinition } from '../../../shared/models/field-definition.model';

@Component({
  selector: 'app-mail-job-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatListModule,
    MatCheckboxModule, MatRadioModule, MatSlideToggleModule,
  ],
  templateUrl: './mail-job-editor.component.html',
  styleUrl: './mail-job-editor.component.scss',
})
export class MailJobEditorComponent implements OnInit {
  jobs: MailJob[] = [];
  templates: MailTemplate[] = [];
  accounts: MailAccount[] = [];
  groups: FieldDefinition[] = [];
  selectedId: string | null = null;
  showAdvancedCron = false;

  readonly cronPresets: { label: string; cron: string }[] = [
    { label: 'Täglich um 08:00', cron: '0 0 8 * * ?' },
    { label: 'Wöchentlich Montag 08:00', cron: '0 0 8 ? * MON' },
    { label: 'Monatlich am 1. um 08:00', cron: '0 0 8 1 * ?' },
  ];

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    templateId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    cron: new FormControl('', Validators.required),
    recipientMode: new FormControl<RecipientMode>('ALL_PARENTS', { nonNullable: true }),
    recipientGroupDefinitionIds: new FormControl<string[]>([], { nonNullable: true }),
  });

  constructor(
    private mailJobService: MailJobService,
    private mailTemplateService: MailTemplateService,
    private mailAccountService: MailAccountService,
    private organisationService: OrganisationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
    this.organisationService.getByTag('groups').subscribe((org) => (this.groups = org.definitions));
  }

  isGroupSelected(groupId: string | undefined): boolean {
    if (!groupId) return false;
    return (this.form.value.recipientGroupDefinitionIds ?? []).includes(groupId);
  }

  toggleGroup(groupId: string | undefined, checked: boolean): void {
    if (!groupId) return;
    const current = this.form.value.recipientGroupDefinitionIds ?? [];
    const next = checked ? [...current, groupId] : current.filter((id) => id !== groupId);
    this.form.patchValue({ recipientMode: 'GROUPS', recipientGroupDefinitionIds: next });
  }

  selectAllParents(): void {
    this.form.patchValue({ recipientMode: 'ALL_PARENTS', recipientGroupDefinitionIds: [] });
  }

  selectCronPreset(cron: string): void {
    this.form.patchValue({ cron });
  }

  toggleAdvancedCron(): void {
    this.showAdvancedCron = !this.showAdvancedCron;
  }

  toggleActive(job: MailJob): void {
    const action$ = job.active ? this.mailJobService.deactivate(job.id) : this.mailJobService.activate(job.id);
    action$.subscribe(() => this.load());
  }

  /** Visual distinction for non-SUCCESS outcomes (R12/Observability). */
  statusClass(status: string | null): string {
    if (!status) return '';
    return status === 'SUCCESS' ? 'status-success' : 'status-attention';
  }

  load(): void {
    this.mailJobService.list().subscribe((jobs) => {
      this.jobs = jobs;
    });
  }

  selectForEdit(job: MailJob): void {
    this.selectedId = job.id;
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
    this.form.reset({
      name: '', templateId: '', subject: '', senderAccountId: '', cron: '',
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
    const save$ = this.selectedId
      ? this.mailJobService.update(this.selectedId, request)
      : this.mailJobService.create(request);
    save$.subscribe(() => {
      this.newJob();
      this.load();
    });
  }

  delete(job: MailJob): void {
    this.mailJobService.delete(job.id).subscribe(() => {
      if (this.selectedId === job.id) {
        this.newJob();
      }
      this.load();
    });
  }
}
