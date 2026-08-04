import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { SchliesstageComponent } from './schliesstage.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ClosureDefinition } from '../../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const umbau: ClosureDefinition = {
  id: 'def-umbau', label: 'Umbau', color: '#888888', active: false, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageComponent — Definitionen', () => {
  let fixture: ComponentFixture<SchliesstageComponent>;
  let component: SchliesstageComponent;
  let definitionService: jasmine.SpyObj<ClosureDefinitionService>;
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.callFake(() => of([ferien, umbau]));
    definitionService.create.and.returnValue(of(ferien));
    definitionService.update.and.returnValue(of(ferien));
    definitionService.revise.and.returnValue(of(ferien));
    definitionService.deactivate.and.returnValue(of(undefined));

    const periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.returnValue(of([]));
    periodService.apply.and.returnValue(of([]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([]));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-1', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ]));

    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      imports: [SchliesstageComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SchliesstageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt auch deaktivierte Definitionen fuer die Tabelle', () => {
    expect(definitionService.getAll).toHaveBeenCalledWith(true);
  });

  it('bietet nur aktive Definitionen zur Zuweisung an', () => {
    expect(component.assignableDefinitions.map(d => d.id)).toEqual(['def-ferien']);
  });

  it('legt eine neue Definition an', () => {
    component.definitionForm.setValue({ label: 'Fortbildung', color: '#e0a020' });
    component.addDefinition();

    expect(definitionService.create).toHaveBeenCalledWith({ label: 'Fortbildung', color: '#e0a020' });
  });

  it('legt ohne Label nichts an', () => {
    component.definitionForm.setValue({ label: '  ', color: '#e0a020' });
    component.addDefinition();

    expect(definitionService.create).not.toHaveBeenCalled();
  });

  it('speichert eine unveraenderte Bearbeitung gar nicht erst', () => {
    component.startEdit(ferien);
    component.commitEdit();

    expect(definitionService.update).not.toHaveBeenCalled();
  });

  it('speichert eine Aenderung ohne Verknuepfung direkt', () => {
    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(definitionService.update).toHaveBeenCalledWith('def-ferien', {
      label: 'Ferien neu', color: '#d94f4f',
    });
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('zeigt bei 409 den Warndialog und legt nach OK eine Kopie an', () => {
    definitionService.update.and.returnValue(throwError(() => ({ status: 409 })));
    dialog.open.and.returnValue({ afterClosed: () => of('revise') } as never);

    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(dialog.open).toHaveBeenCalled();
    expect(definitionService.revise).toHaveBeenCalledWith('def-ferien', {
      label: 'Ferien neu', color: '#d94f4f',
    });
  });

  it('verwirft die Aenderung bei Abbruch', () => {
    definitionService.update.and.returnValue(throwError(() => ({ status: 409 })));
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as never);

    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(definitionService.revise).not.toHaveBeenCalled();
    expect(component.editingId).toBeNull();
  });

  it('deaktiviert ueber DELETE', () => {
    component.setActive(ferien, false);
    expect(definitionService.deactivate).toHaveBeenCalledWith('def-ferien');
  });

  it('reaktiviert ueber PUT mit unveraendertem Inhalt', () => {
    component.setActive(umbau, true);
    expect(definitionService.update).toHaveBeenCalledWith('def-umbau', {
      label: 'Umbau', color: '#888888', active: true,
    });
  });
});
