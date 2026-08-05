import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideNativeDateAdapter } from '@angular/material/core';
import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { CurrencyService } from '../../shared/services/currency.service';
import { KostenDefinitionService } from '../../shared/services/kosten-definition.service';
import { RequiredHoursService } from '../../shared/services/required-hours.service';
import { AliquotConfigService } from '../../shared/services/aliquot-config.service';
import { CookingReminderJobService } from '../../shared/services/cooking-reminder-job.service';
import { MailAccountService } from '../../shared/services/mail-account.service';

describe('OrganisationComponent — Erinnerungs-Einstellungen', () => {
  let fixture: ComponentFixture<OrganisationComponent>;
  let component: OrganisationComponent;

  beforeEach(async () => {
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

    const cookingReminderJobService = jasmine.createSpyObj('CookingReminderJobService', ['list', 'create', 'update', 'delete']);
    cookingReminderJobService.list.and.returnValue(of([]));

    const mailAccountService = jasmine.createSpyObj('MailAccountService', ['list']);
    mailAccountService.list.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [OrganisationComponent, NoopAnimationsModule],
      providers: [
        provideNativeDateAdapter(),
        { provide: OrganisationService, useValue: orgService },
        { provide: FieldDefinitionService, useValue: fieldDefService },
        { provide: FieldInstanceService, useValue: fieldInstanceService },
        { provide: SemesterService, useValue: semesterService },
        { provide: CurrencyService, useValue: currencyService },
        { provide: KostenDefinitionService, useValue: kostenDefinitionService },
        { provide: RequiredHoursService, useValue: requiredHoursService },
        { provide: AliquotConfigService, useValue: aliquotConfigService },
        { provide: CookingReminderJobService, useValue: cookingReminderJobService },
        { provide: MailAccountService, useValue: mailAccountService },
      ],
    })
      .compileComponents();

    fixture = TestBed.createComponent(OrganisationComponent);
    component = fixture.componentInstance;
  });

  it('rendert die Kochdienst-Erinnerungen als eigene Komponente', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('app-cooking-reminder-jobs')).not.toBeNull();
  });

  it('haelt kein eigenes Erinnerungs-Formular mehr', () => {
    expect((component as unknown as Record<string, unknown>)['reminderForm']).toBeUndefined();
  });
});
