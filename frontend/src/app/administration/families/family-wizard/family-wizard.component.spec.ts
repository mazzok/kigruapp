import { of } from 'rxjs';
import { FamilyWizardComponent } from './family-wizard.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { Family } from '../../../shared/models/family.model';
import { Person, PersonDTO } from '../../../shared/models/person.model';

class FakeDialogRef {
  closedWith: unknown;
  close(value?: unknown): void {
    this.closedWith = value;
  }
}

class FakeFamilyService {
  createCalls: Family[] = [];
  updateCalls: { id: string; data: Partial<Family> }[] = [];
  createdFamily: Family = { id: 'f-new', name: 'Neue Familie' };

  create(family: Family) {
    this.createCalls.push(family);
    return of(this.createdFamily);
  }

  update(id: string, data: { name: string; address?: Family['address'] }) {
    this.updateCalls.push({ id, data });
    return of({ id, ...data } as Family);
  }

  get(_id: string) {
    return of({ id: 'f1', name: 'Familie Bestand' } as Family);
  }

  list() {
    return of([]);
  }
}

class FakePersonService {
  createCalls: unknown[] = [];
  updateCalls: { id: string; req: unknown }[] = [];
  deleteCalls: string[] = [];
  personsByFamily: Person[] = [];
  fullById = new Map<string, PersonDTO>();

  list(_familyId?: string) {
    return of(this.personsByFamily);
  }

  getFull(id: string) {
    return of(this.fullById.get(id)!);
  }

  create(req: unknown) {
    this.createCalls.push(req);
    return of({ id: 'new-person' } as Person);
  }

  update(id: string, req: unknown) {
    this.updateCalls.push({ id, req });
    return of({ id } as Person);
  }

  delete(id: string) {
    this.deleteCalls.push(id);
    return of(undefined);
  }
}

describe('FamilyWizardComponent', () => {
  let dialogRef: FakeDialogRef;
  let familyService: FakeFamilyService;
  let personService: FakePersonService;
  let component: FamilyWizardComponent;

  beforeEach(() => {
    dialogRef = new FakeDialogRef();
    familyService = new FakeFamilyService();
    personService = new FakePersonService();
  });

  function create(data: { familyId?: string } | null): void {
    component = new FamilyWizardComponent(
      dialogRef as any,
      data,
      familyService as unknown as FamilyService,
      personService as unknown as PersonService,
    );
  }

  it('starts on the overview for a new family without a resolved id', () => {
    create(null);
    expect(component.view).toBe('overview');
    expect(component.resolvedFamilyId).toBeUndefined();
  });

  it('resolves the family id immediately in edit mode', () => {
    create({ familyId: 'f1' });
    expect(component.resolvedFamilyId).toBe('f1');
  });

  it('creates a new family on saveFamily when no id is resolved yet', async () => {
    create(null);
    component.familyStep = { newFamilyName: 'Familie Müller', address: null } as any;

    await component.saveFamily();

    expect(familyService.createCalls).toEqual([{ name: 'Familie Müller', address: undefined }]);
    expect(component.resolvedFamilyId).toBe('f-new');
    expect(component.view).toBe('overview');
  });

  it('updates the existing family on saveFamily once an id is resolved', async () => {
    create({ familyId: 'f1' });
    component.resolvedFamilyId = 'f1';
    component.familyStep = { newFamilyName: 'Familie Neu', address: null } as any;

    await component.saveFamily();

    expect(familyService.updateCalls).toEqual([{ id: 'f1', data: { name: 'Familie Neu', address: undefined } }]);
  });

  it('refreshes editFamily after saveFamily so reopening the Familie section shows the new address', async () => {
    create({ familyId: 'f1' });
    component.resolvedFamilyId = 'f1';
    component.editFamily = { id: 'f1', name: 'Familie Alt', address: { street: 'Alte Str. 1', zip: '1010', city: 'Wien' } };
    familyService.update = (id: string, data: { name: string; address?: Family['address'] }) =>
      of({ id, name: data.name, address: data.address } as Family);
    component.familyStep = {
      newFamilyName: 'Familie Neu',
      address: { street: 'Neue Str. 99', zip: '4020', city: 'Linz' },
    } as any;

    await component.saveFamily();

    expect(component.editFamily).toEqual({
      id: 'f1',
      name: 'Familie Neu',
      address: { street: 'Neue Str. 99', zip: '4020', city: 'Linz' },
    });
  });

  it('marks anyChanges after a successful save so cancel reports it to the caller', async () => {
    create(null);
    component.familyStep = { newFamilyName: 'Familie Müller', address: null } as any;

    await component.saveFamily();
    component.cancel();

    expect(dialogRef.closedWith).toBe(true);
  });

  it('reports no changes when cancelling the overview without saving anything', () => {
    create(null);
    component.cancel();
    expect(dialogRef.closedWith).toBe(false);
  });

  it('returns to the overview without closing the dialog when backToOverview is called', () => {
    create(null);
    component.view = 'family';
    component.backToOverview();
    expect(component.view).toBe('overview');
    expect(dialogRef.closedWith).toBeUndefined();
  });

  it('creates and updates persons then reloads existingChildren on saveChildren', async () => {
    create({ familyId: 'f1' });
    component.resolvedFamilyId = 'f1';
    component.childStep = {
      getChildrenData: () => [
        { id: 'c1', basicProperties: [{ definitionId: 'd1', value: 'Kind Eins' }] },
        { basicProperties: [{ definitionId: 'd1', value: 'Kind Zwei' }] },
      ],
      removedChildIds: ['c-old'],
    } as any;

    personService.personsByFamily = [{ id: 'c1' } as Person, { id: 'new-person' } as Person];
    personService.fullById.set('c1', { id: 'c1', basicProperties: [{ fieldName: 'personType', value: 'CHILD' }] } as PersonDTO);
    personService.fullById.set('new-person', { id: 'new-person', basicProperties: [{ fieldName: 'personType', value: 'CHILD' }] } as PersonDTO);

    await component.saveChildren();

    expect(personService.updateCalls.length).toBe(1);
    expect(personService.createCalls.length).toBe(1);
    expect(personService.deleteCalls).toEqual(['c-old']);
    expect(component.existingChildren.length).toBe(2);
    expect(component.view).toBe('overview');
  });

  it('creates and updates persons then reloads existingParents on saveParents', async () => {
    create({ familyId: 'f1' });
    component.resolvedFamilyId = 'f1';
    component.parentsStep = {
      getParentsData: () => [
        { id: 'p1', basicProperties: [{ definitionId: 'd1', value: 'Elternteil Eins' }] },
      ],
      removedParentIds: ['p-old'],
    } as any;

    personService.personsByFamily = [{ id: 'p1' } as Person];
    personService.fullById.set('p1', { id: 'p1', basicProperties: [{ fieldName: 'personType', value: 'PARENT' }] } as PersonDTO);

    await component.saveParents();

    expect(personService.updateCalls.length).toBe(1);
    expect(personService.deleteCalls).toEqual(['p-old']);
    expect(component.existingParents.length).toBe(1);
    expect(component.view).toBe('overview');
  });

  it('prefills the children step with the family name/address when opening the children section', () => {
    jasmine.clock().install();
    create({ familyId: 'f1' });
    component.familyName = 'Müller';
    component.familyAddress = { street: 'Ring 3', zip: '4020', city: 'Linz' };
    const prefillSpy = jasmine.createSpy('prefill');
    component.childStep = { prefill: prefillSpy } as any;

    component.openSection('children');
    jasmine.clock().tick(1);

    expect(prefillSpy).toHaveBeenCalledWith('Müller', { street: 'Ring 3', zip: '4020', city: 'Linz' });
    jasmine.clock().uninstall();
  });
});
