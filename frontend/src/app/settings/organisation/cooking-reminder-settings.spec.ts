import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideNativeDateAdapter } from '@angular/material/core';
import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { CookingReminderSettingsService } from '../../shared/services/cooking-reminder-settings.service';
import { MailAccountService } from '../../shared/services/mail-account.service';
import { MailTemplateService } from '../../shared/services/mail-template.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { CurrencyService } from '../../shared/services/currency.service';
import { KostenDefinitionService } from '../../shared/services/kosten-definition.service';
import { RequiredHoursService } from '../../shared/services/required-hours.service';
import { AliquotConfigService } from '../../shared/services/aliquot-config.service';

describe('OrganisationComponent — Erinnerungs-Einstellungen', () => {
  let fixture: ComponentFixture<OrganisationComponent>;
  let component: OrganisationComponent;
  let reminderService: jasmine.SpyObj<CookingReminderSettingsService>;

  beforeEach(async () => {
    reminderService = jasmine.createSpyObj('CookingReminderSettingsService', ['get', 'save']);
    reminderService.get.and.returnValue(of({
      senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true,
    }));
    reminderService.save.and.returnValue(of({
      senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true,
    }));

    const mailAccountService = jasmine.createSpyObj('MailAccountService', ['list']);
    mailAccountService.list.and.returnValue(of([{ id: 'a1', name: 'Kiga', enabled: true }]));

    const mailTemplateService = jasmine.createSpyObj('MailTemplateService', ['list']);
    mailTemplateService.list.and.returnValue(of([{ id: 't1', name: 'Erinnerung' }]));

    const orgService = jasmine.createSpyObj('OrganisationService', ['getByTag', 'update']);
    orgService.getByTag.and.returnValue(of({ id: 'org', tag: 'x', definitions: [], entries: [] }));
    orgService.update.and.returnValue(of({ id: 'org', tag: 'x', definitions: [], entries: [] }));

    const fieldDefService = jasmine.createSpyObj('FieldDefinitionService', ['create', 'outdate']);
    fieldDefService.create.and.returnValue(of({}));
    fieldDefService.outdate.and.returnValue(of({}));

    const fieldInstanceService = jasmine.createSpyObj('FieldInstanceService', ['listByDefinitionId', 'create', 'delete']);
    fieldInstanceService.listByDefinitionId.and.returnValue(of([]));
    fieldInstanceService.create.and.returnValue(of({}));
    fieldInstanceService.delete.and.returnValue(of(undefined));

    const semesterService = jasmine.createSpyObj('SemesterService', ['getAll', 'create']);
    semesterService.getAll.and.returnValue(of([]));
    semesterService.create.and.returnValue(of({}));

    const currencyService = jasmine.createSpyObj('CurrencyService', ['getAll', 'create']);
    currencyService.getAll.and.returnValue(of([]));
    currencyService.create.and.returnValue(of({}));

    const kostenDefinitionService = jasmine.createSpyObj('KostenDefinitionService', ['getAll', 'create', 'setActive']);
    kostenDefinitionService.getAll.and.returnValue(of([]));
    kostenDefinitionService.create.and.returnValue(of({}));
    kostenDefinitionService.setActive.and.returnValue(of({}));

    const requiredHoursService = jasmine.createSpyObj('RequiredHoursService', ['get', 'save']);
    requiredHoursService.get.and.returnValue(of({ defaultMinutesPerMonth: 0, tiers: [] }));
    requiredHoursService.save.and.returnValue(of({}));

    const aliquotConfigService = jasmine.createSpyObj('AliquotConfigService', ['get', 'save']);
    aliquotConfigService.get.and.returnValue(of({ stundenMode: 'NONE', kostenMode: 'NONE' }));
    aliquotConfigService.save.and.returnValue(of({}));

    await TestBed.configureTestingModule({
      imports: [OrganisationComponent, NoopAnimationsModule],
      providers: [
        provideNativeDateAdapter(),
        { provide: CookingReminderSettingsService, useValue: reminderService },
        { provide: MailAccountService, useValue: mailAccountService },
        { provide: MailTemplateService, useValue: mailTemplateService },
        { provide: OrganisationService, useValue: orgService },
        { provide: FieldDefinitionService, useValue: fieldDefService },
        { provide: FieldInstanceService, useValue: fieldInstanceService },
        { provide: SemesterService, useValue: semesterService },
        { provide: CurrencyService, useValue: currencyService },
        { provide: KostenDefinitionService, useValue: kostenDefinitionService },
        { provide: RequiredHoursService, useValue: requiredHoursService },
        { provide: AliquotConfigService, useValue: aliquotConfigService },
      ],
    })
      .compileComponents();

    fixture = TestBed.createComponent(OrganisationComponent);
    component = fixture.componentInstance;
  });

  it('laedt die Einstellungen in das Formular', () => {
    component.loadReminderSettings();

    expect(component.reminderForm.value.senderAccountId).toBe('a1');
    expect(component.reminderForm.value.sendTime).toBe('18:30');
    expect(component.reminderSettingsActive).toBeTrue();
  });

  it('laedt Mailkonten und Vorlagen fuer die Auswahl', () => {
    component.loadReminderSettings();

    expect(component.mailAccounts.length).toBe(1);
    expect(component.mailTemplates.length).toBe(1);
  });

  it('speichert die Einstellungen', () => {
    component.loadReminderSettings();
    component.reminderForm.patchValue({ subject: 'Neu' });

    component.saveReminderSettings();

    expect(reminderService.save).toHaveBeenCalled();
    const payload = reminderService.save.calls.mostRecent().args[0];
    expect(payload.subject).toBe('Neu');
  });
});
