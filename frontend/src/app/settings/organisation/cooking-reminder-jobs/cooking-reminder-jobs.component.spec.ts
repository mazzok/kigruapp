import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { CookingReminderJobsComponent } from './cooking-reminder-jobs.component';
import { CookingReminderJobService } from '../../../shared/services/cooking-reminder-job.service';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { CookingReminderJob } from '../../../shared/models/cooking-reminder-job.model';

const JOB: CookingReminderJob = {
  id: '1', name: 'Erinnerung', senderAccountId: 'a', subject: 'Dein Kochdienst',
  sendTime: '07:30', active: true, templateId: 't', templateName: 'Vorlage',
  templateBodyHtml: '<p>Am {{duty.date}} kochst du.</p>',
};

describe('CookingReminderJobsComponent', () => {
  let fixture: ComponentFixture<CookingReminderJobsComponent>;
  let component: CookingReminderJobsComponent;
  let jobService: jasmine.SpyObj<CookingReminderJobService>;

  beforeEach(async () => {
    jobService = jasmine.createSpyObj('CookingReminderJobService', ['list', 'create', 'update', 'delete']);
    jobService.list.and.returnValue(of([JOB]));
    jobService.create.and.returnValue(of(JOB));
    jobService.update.and.returnValue(of(JOB));
    jobService.delete.and.returnValue(of(void 0));

    const accountService = jasmine.createSpyObj('MailAccountService', ['list']);
    accountService.list.and.returnValue(of([
      { id: 'a', name: 'Kindergarten', enabled: true },
      { id: 'b', name: 'Verein', enabled: false },
    ]));

    await TestBed.configureTestingModule({
      imports: [CookingReminderJobsComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [
        { provide: CookingReminderJobService, useValue: jobService },
        { provide: MailAccountService, useValue: accountService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingReminderJobsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt die Jobs', () => {
    expect(component.jobs.length).toBe(1);
  });

  it('zeigt auch nicht aktivierte Mailkonten zur Auswahl an', () => {
    expect(component.accounts.length).toBe(2);
    expect(component.accounts.some((a) => a.id === 'b' && !a.enabled)).toBeTrue();
  });

  it('uebernimmt Job und Vorlage in die Maske', () => {
    component.selectForEdit(JOB);

    expect(component.form.value.subject).toBe('Dein Kochdienst');
    expect(component.templateValue).toEqual({ name: 'Vorlage', bodyHtml: '<p>Am {{duty.date}} kochst du.</p>' });
  });

  it('sendet Job und Vorlage gemeinsam beim Anlegen', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', sendTime: '08:00', active: false,
    });
    component.onTemplateChange({ name: 'V', bodyHtml: '<p>x</p>' });

    component.save();

    expect(jobService.create).toHaveBeenCalledWith({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', sendTime: '08:00', active: false,
      templateName: 'V', templateBodyHtml: '<p>x</p>',
    });
  });

  it('schaltet einen Job ueber die Liste um', () => {
    component.toggleActive(JOB);

    expect(jobService.update).toHaveBeenCalledWith('1', jasmine.objectContaining({ active: false }));
  });
});
