import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CookingReminderJobService } from '../../../shared/services/cooking-reminder-job.service';
import { CookingReminderJob, SaveCookingReminderJobRequest } from '../../../shared/models/cooking-reminder-job.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailTemplateFormComponent } from '../../mail/mail-template-editor/mail-template-form.component';

const DEFAULT_SEND_TIME = '07:00';

/**
 * Kochdienst-Erinnerungen: links die Jobs, rechts Job-Formular und die fest
 * zugeordnete Vorlage. Beides geht in einem Request an den Server.
 */
@Component({
  selector: 'app-cooking-reminder-jobs',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatSlideToggleModule, MatTooltipModule,
    MailTemplateFormComponent,
  ],
  templateUrl: './cooking-reminder-jobs.component.html',
  styleUrl: './cooking-reminder-jobs.component.scss',
})
export class CookingReminderJobsComponent implements OnInit {
  jobs: CookingReminderJob[] = [];
  accounts: MailAccount[] = [];

  selectedId: string | null = null;
  editing = false;

  /** Wert für die Maske (Eingang) und der zuletzt gemeldete Wert (Ausgang). */
  templateValue = { name: '', bodyHtml: '' };
  private currentTemplate = { name: '', bodyHtml: '' };

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    sendTime: new FormControl(DEFAULT_SEND_TIME, Validators.required),
    active: new FormControl<boolean>(false, { nonNullable: true }),
  });

  constructor(
    private jobService: CookingReminderJobService,
    private mailAccountService: MailAccountService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
  }

  load(): void {
    this.jobService.list().subscribe((jobs) => (this.jobs = jobs));
  }

  onTemplateChange(value: { name: string; bodyHtml: string }): void {
    this.currentTemplate = value;
  }

  get canSave(): boolean {
    return this.form.valid
      && this.currentTemplate.name.trim().length > 0
      && this.currentTemplate.bodyHtml.trim().length > 0;
  }

  selectForEdit(job: CookingReminderJob): void {
    this.selectedId = job.id;
    this.editing = true;
    this.form.patchValue({
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      sendTime: job.sendTime,
      active: job.active,
    });
    this.templateValue = { name: job.templateName, bodyHtml: job.templateBodyHtml };
    this.currentTemplate = { ...this.templateValue };
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({ name: '', senderAccountId: '', subject: '', sendTime: DEFAULT_SEND_TIME, active: false });
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({ name: '', senderAccountId: '', subject: '', sendTime: DEFAULT_SEND_TIME, active: false });
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  private toRequest(active: boolean): SaveCookingReminderJobRequest {
    const v = this.form.value;
    return {
      name: v.name ?? '',
      senderAccountId: v.senderAccountId ?? '',
      subject: v.subject ?? '',
      sendTime: v.sendTime ?? DEFAULT_SEND_TIME,
      active,
      templateName: this.currentTemplate.name,
      templateBodyHtml: this.currentTemplate.bodyHtml,
    };
  }

  save(): void {
    const request = this.toRequest(this.form.value.active ?? false);
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.jobService.update(this.selectedId, request)
      : this.jobService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Erinnerung aktualisiert' : 'Erinnerung gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  /** Der Schalter in der Liste speichert den Job unverändert mit gekipptem active. */
  toggleActive(job: CookingReminderJob): void {
    const activating = !job.active;
    this.jobService.update(job.id, {
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      sendTime: job.sendTime,
      active: activating,
      templateName: job.templateName,
      templateBodyHtml: job.templateBodyHtml,
    }).subscribe({
      next: () => {
        this.notify.success(activating ? 'Erinnerung aktiviert' : 'Erinnerung deaktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load();
      },
    });
  }

  delete(job: CookingReminderJob): void {
    this.jobService.delete(job.id).subscribe({
      next: () => {
        this.notify.success('Erinnerung gelöscht');
        if (this.selectedId === job.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
