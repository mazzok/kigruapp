import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { CookingOverviewJobsComponent } from './cooking-overview-jobs.component';
import { CookingOverviewJobService } from '../../../shared/services/cooking-overview-job.service';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { CookingOverviewJob } from '../../../shared/models/cooking-overview-job.model';

const JOB: CookingOverviewJob = {
  id: '1', name: 'Wochenuebersicht', senderAccountId: 'a', subject: 'Kochdienste diese Woche',
  cron: '0 0 7 ? * MON', allParents: true, recipientSelections: [], active: true,
  templateId: 't', templateName: 'Vorlage', templateBodyHtml: '<p>Diese Woche kochen ...</p>',
};

describe('CookingOverviewJobsComponent', () => {
  let fixture: ComponentFixture<CookingOverviewJobsComponent>;
  let component: CookingOverviewJobsComponent;
  let jobService: jasmine.SpyObj<CookingOverviewJobService>;

  beforeEach(async () => {
    jobService = jasmine.createSpyObj('CookingOverviewJobService', ['list', 'create', 'update', 'delete']);
    jobService.list.and.returnValue(of([JOB]));
    jobService.create.and.returnValue(of(JOB));
    jobService.update.and.returnValue(of(JOB));
    jobService.delete.and.returnValue(of(void 0));

    const accountService = jasmine.createSpyObj('MailAccountService', ['list']);
    accountService.list.and.returnValue(of([{ id: 'a', name: 'Kindergarten', enabled: true }]));

    const organisationService = jasmine.createSpyObj('OrganisationService', ['getByTag']);
    organisationService.getByTag.and.returnValue(of({ id: 'o1', tag: 'groups', definitions: [], entries: [] }));

    const fieldInstanceService = jasmine.createSpyObj('FieldInstanceService', ['listByDefinitionId']);
    fieldInstanceService.listByDefinitionId.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [CookingOverviewJobsComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [
        { provide: CookingOverviewJobService, useValue: jobService },
        { provide: MailAccountService, useValue: accountService },
        { provide: OrganisationService, useValue: organisationService },
        { provide: FieldInstanceService, useValue: fieldInstanceService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingOverviewJobsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt die Jobs', () => {
    expect(component.jobs.length).toBe(1);
  });

  it('zeigt auch nicht aktivierte Mailkonten zur Auswahl an', () => {
    expect(component.accounts.length).toBe(1);
  });

  it('uebernimmt Job und Vorlage in die Maske', () => {
    component.selectForEdit(JOB);

    expect(component.form.value.subject).toBe('Kochdienste diese Woche');
    expect(component.form.value.cron).toBe('0 0 7 ? * MON');
    expect(component.templateValue).toEqual({ name: 'Vorlage', bodyHtml: '<p>Diese Woche kochen ...</p>' });
  });

  it('sendet Job und Vorlage gemeinsam beim Anlegen', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', cron: '0 0 8 ? * MON', allParents: true, active: false,
    });
    component.onTemplateChange({ name: 'V', bodyHtml: '<p>x</p>' });

    component.save();

    expect(jobService.create).toHaveBeenCalledWith(jasmine.objectContaining({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', cron: '0 0 8 ? * MON',
      allParents: true, active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    }));
  });

  it('schaltet einen Job aktiv/inaktiv', () => {
    component.toggleActive(JOB);

    expect(jobService.update).toHaveBeenCalledWith('1', jasmine.objectContaining({ active: false }));
  });

  it('loescht einen Job', () => {
    component.delete(JOB);

    expect(jobService.delete).toHaveBeenCalledWith('1');
  });
});
