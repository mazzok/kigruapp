# Familie bearbeiten: Übersicht statt Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the sequential `mat-stepper` in `FamilyWizardComponent` with an overview screen that links to three independent sections (Familie / Kinder / Eltern), each with its own Speichern/Abbrechen, so editing one section no longer requires stepping through the others.

**Architecture:** `FamilyWizardComponent` gets a `view: 'overview' | 'family' | 'children' | 'parents'` state instead of a stepper. Each section has its own save method (`saveFamily`/`saveChildren`/`saveParents`) that persists only that section and returns to `'overview'`. `FamilyStepComponent` is simplified to a plain name+address form (the new/existing-family radio choice is removed — adding a child to an existing family now goes through Bearbeiten → Kinder instead of the create flow). `family-list` gets a new "Familie erstellen" button that opens the same dialog with `data: null`.

**Tech Stack:** Angular 17+ standalone components, Angular Material (dialog, button, icon), Angular reactive forms, Jasmine/Karma (`ng test`).

## Global Constraints

- No `mat-stepper`/`MatStepperModule` usage remains in `family-wizard.component.ts` or its template.
- No "Weiter" button anywhere in the dialog — only Abbrechen/Speichern per section, and Abbrechen on the overview.
- `FamilyStepComponent`'s new/existing-family radio selection, `existingFamilyId`, `existingFamilies`, `isNewFamily`, `selectedFamilyId` are removed entirely.
- Kinder/Eltern-Kacheln are disabled until `resolvedFamilyId` is set.
- Dialog title switches live between "Familie erstellen" and "Familie bearbeiten" based on whether `resolvedFamilyId` is set.
- `child-step.component.ts` and `parents-step.component.ts` are NOT modified.
- `FamilyWizardComponent`'s class name and selector (`app-family-wizard`) stay unchanged.

---

## Task 1: Simplify FamilyStepComponent

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`
- Modify: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html`
- Create: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.spec.ts`

**Interfaces:**
- Consumes: `Family`, `FamilyAddress` from `../../../../shared/models/family.model`.
- Produces (used by Task 2): `@Input() editFamily: Family | undefined`, `get isValid(): boolean`, `get newFamilyName(): string`, `get address(): FamilyAddress | null`, public `form: FormGroup` with controls `newFamilyName`, `street`, `zip`, `city`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/administration/families/family-wizard/steps/family-step.component.spec.ts`:

```ts
import { FamilyStepComponent } from './family-step.component';

describe('FamilyStepComponent', () => {
  let component: FamilyStepComponent;

  beforeEach(() => {
    component = new FamilyStepComponent();
  });

  it('is invalid when the name is empty', () => {
    expect(component.isValid).toBe(false);
  });

  it('is valid once a name is entered', () => {
    component.form.patchValue({ newFamilyName: 'Familie Müller' });
    expect(component.isValid).toBe(true);
  });

  it('trims the family name', () => {
    component.form.patchValue({ newFamilyName: '  Familie Müller  ' });
    expect(component.newFamilyName).toBe('Familie Müller');
  });

  it('returns null address when all address fields are empty', () => {
    expect(component.address).toBeNull();
  });

  it('returns a trimmed address when any address field is set', () => {
    component.form.patchValue({ street: ' Hauptstr. 1 ', zip: '1010', city: ' Wien ' });
    expect(component.address).toEqual({ street: 'Hauptstr. 1', zip: '1010', city: 'Wien' });
  });

  it('prefills name and address from an existing family via editFamily', () => {
    component.editFamily = {
      id: 'f1',
      name: 'Familie Bestand',
      address: { street: 'Ring 3', zip: '4020', city: 'Linz' },
    };

    expect(component.newFamilyName).toBe('Familie Bestand');
    expect(component.address).toEqual({ street: 'Ring 3', zip: '4020', city: 'Linz' });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include=**/family-step.component.spec.ts`
Expected: FAIL — compile error, because `FamilyStepComponent` still requires a `FamilyService` constructor argument and its `form` has no `newFamilyName`-only shape compatible with a no-arg construction (current constructor is `constructor(private familyService: FamilyService)`).

- [ ] **Step 3: Simplify the component**

Replace the full contents of `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { Family, FamilyAddress } from '../../../../shared/models/family.model';

@Component({
  selector: 'app-family-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatInputModule, MatFormFieldModule,
  ],
  templateUrl: './family-step.component.html',
})
export class FamilyStepComponent {
  form = new FormGroup({
    newFamilyName: new FormControl<string>('', Validators.required),
    street: new FormControl<string>(''),
    zip: new FormControl<string>(''),
    city: new FormControl<string>(''),
  });

  @Input() set editFamily(family: Family | undefined) {
    if (!family) return;
    this.form.patchValue({
      newFamilyName: family.name,
      street: family.address?.street ?? '',
      zip: family.address?.zip ?? '',
      city: family.address?.city ?? '',
    });
  }

  get isValid(): boolean {
    return !!this.form.value.newFamilyName?.trim();
  }

  get newFamilyName(): string {
    return this.form.value.newFamilyName?.trim() ?? '';
  }

  get address(): FamilyAddress | null {
    const { street, zip, city } = this.form.value;
    if (street?.trim() || zip?.trim() || city?.trim()) {
      return { street: street?.trim() ?? '', zip: zip?.trim() ?? '', city: city?.trim() ?? '' };
    }
    return null;
  }
}
```

Replace the full contents of `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html`:

```html
<div [formGroup]="form">
  <mat-form-field appearance="outline" class="full-width">
    <mat-label>Familienname</mat-label>
    <input matInput formControlName="newFamilyName" placeholder="z.B. Familie Müller">
  </mat-form-field>

  <h4>Adresse</h4>
  <mat-form-field appearance="outline" class="full-width">
    <mat-label>Straße</mat-label>
    <input matInput formControlName="street">
  </mat-form-field>
  <div style="display: flex; gap: 16px;">
    <mat-form-field appearance="outline">
      <mat-label>PLZ</mat-label>
      <input matInput formControlName="zip">
    </mat-form-field>
    <mat-form-field appearance="outline" style="flex: 1;">
      <mat-label>Ort</mat-label>
      <input matInput formControlName="city">
    </mat-form-field>
  </div>
</div>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include=**/family-step.component.spec.ts`
Expected: PASS — all 6 specs green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts frontend/src/app/administration/families/family-wizard/steps/family-step.component.html frontend/src/app/administration/families/family-wizard/steps/family-step.component.spec.ts
git commit -m "refactor: simplify FamilyStepComponent to name+address only"
```

---

## Task 2: View-state and per-section save logic in FamilyWizardComponent

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`
- Create: `frontend/src/app/administration/families/family-wizard/family-wizard.component.spec.ts`

**Interfaces:**
- Consumes: `FamilyStepComponent.{newFamilyName, address, isValid}` (Task 1), `ChildStepComponent.{getChildrenData(), removedChildIds, prefill(lastName, address), isValid}`, `ParentsStepComponent.{getParentsData(), removedParentIds, prefill(lastName, address), isValid}` (both unchanged), `FamilyService.{get, create, update}`, `PersonService.{list, getFull, create, update, delete}`.
- Produces (used by Task 3 template): `view: 'overview' | 'family' | 'children' | 'parents'`, `resolvedFamilyId?: string`, `familyName: string`, `familyAddress: FamilyAddress | null`, `existingChildren`, `existingParents`, `editFamily`, `loading`, `submitting`, methods `openSection(target)`, `backToOverview()`, `cancel()`, `saveFamily()`, `saveChildren()`, `saveParents()`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/administration/families/family-wizard/family-wizard.component.spec.ts`:

```ts
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include=**/family-wizard.component.spec.ts`
Expected: FAIL — `component.view`, `resolvedFamilyId`, `saveFamily`, `saveChildren`, `saveParents`, `backToOverview`, `openSection`, `familyName`, `familyAddress` don't exist yet on `FamilyWizardComponent` (current class only has `isEditMode`, `submit`, `submitCreate`, `submitEdit`, `onStepChange`).

- [ ] **Step 3: Replace FamilyWizardComponent's logic**

Replace the full contents of `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`:

```ts
import { Component, Inject, OnInit, Optional, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { CreatePersonRequest, PersonDTO } from '../../../shared/models/person.model';
import { Family, FamilyAddress } from '../../../shared/models/family.model';
import { lastValueFrom } from 'rxjs';

type WizardView = 'overview' | 'family' | 'children' | 'parents';

@Component({
  selector: 'app-family-wizard',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule, MatIconModule, MatDialogModule,
    FamilyStepComponent, ChildStepComponent, ParentsStepComponent,
  ],
  templateUrl: './family-wizard.component.html',
  styleUrl: './family-wizard.component.scss',
})
export class FamilyWizardComponent implements OnInit {
  @ViewChild(FamilyStepComponent) familyStep!: FamilyStepComponent;
  @ViewChild(ChildStepComponent) childStep!: ChildStepComponent;
  @ViewChild(ParentsStepComponent) parentsStep!: ParentsStepComponent;

  view: WizardView = 'overview';
  submitting = false;
  loading = false;
  anyChanges = false;

  resolvedFamilyId?: string;
  familyName = '';
  familyAddress: FamilyAddress | null = null;

  editFamily?: Family;
  existingChildren: { id: string; dto: PersonDTO }[] = [];
  existingParents: { id: string; dto: PersonDTO }[] = [];

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: { familyId?: string } | null,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {}

  ngOnInit(): void {
    if (this.data?.familyId) {
      this.resolvedFamilyId = this.data.familyId;
      this.loadEditData();
    }
  }

  private async loadEditData(): Promise<void> {
    this.loading = true;
    try {
      const familyId = this.data!.familyId!;
      const family = await lastValueFrom(this.familyService.get(familyId));
      this.editFamily = family;
      this.familyName = family.name;
      this.familyAddress = family.address ?? null;

      const persons = await lastValueFrom(this.personService.list(familyId));
      const dtos = await Promise.all(
        persons.filter((p) => !!p.id).map((p) => lastValueFrom(this.personService.getFull(p.id!)))
      );

      for (const dto of dtos) {
        const personType = dto.basicProperties?.find((f) => f.fieldName === 'personType')?.value;
        if (personType === 'CHILD') {
          this.existingChildren.push({ id: dto.id!, dto });
        } else {
          this.existingParents.push({ id: dto.id!, dto });
        }
      }
    } finally {
      this.loading = false;
    }
  }

  openSection(target: 'family' | 'children' | 'parents'): void {
    this.view = target;
    if (target === 'children' || target === 'parents') {
      const name = this.familyName;
      const address = this.familyAddress;
      if (name || address) {
        setTimeout(() => {
          if (target === 'children') this.childStep?.prefill(name, address);
          else this.parentsStep?.prefill(name, address);
        }, 0);
      }
    }
  }

  backToOverview(): void {
    this.view = 'overview';
  }

  cancel(): void {
    this.dialogRef.close(this.anyChanges);
  }

  async saveFamily(): Promise<void> {
    this.submitting = true;
    try {
      const request = {
        name: this.familyStep.newFamilyName,
        address: this.familyStep.address ?? undefined,
      };
      const family = this.resolvedFamilyId
        ? await lastValueFrom(this.familyService.update(this.resolvedFamilyId, request))
        : await lastValueFrom(this.familyService.create(request));

      this.resolvedFamilyId = family.id;
      this.familyName = family.name;
      this.familyAddress = family.address ?? null;
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Familie fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  async saveChildren(): Promise<void> {
    this.submitting = true;
    try {
      const familyId = this.resolvedFamilyId!;
      for (const child of this.childStep.getChildrenData()) {
        const req: CreatePersonRequest = { familyId, basicProperties: child.basicProperties };
        if (child.id) {
          await lastValueFrom(this.personService.update(child.id, req));
        } else {
          await lastValueFrom(this.personService.create(req));
        }
      }
      for (const id of this.childStep.removedChildIds ?? []) {
        await lastValueFrom(this.personService.delete(id));
      }
      this.existingChildren = await this.loadPersonsByType(familyId, 'CHILD');
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Kinder fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  async saveParents(): Promise<void> {
    this.submitting = true;
    try {
      const familyId = this.resolvedFamilyId!;
      for (const parent of this.parentsStep.getParentsData()) {
        const req: CreatePersonRequest = { familyId, basicProperties: parent.basicProperties };
        if (parent.id) {
          await lastValueFrom(this.personService.update(parent.id, req));
        } else {
          await lastValueFrom(this.personService.create(req));
        }
      }
      for (const id of this.parentsStep.removedParentIds ?? []) {
        await lastValueFrom(this.personService.delete(id));
      }
      this.existingParents = await this.loadPersonsByType(familyId, 'PARENT');
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Eltern fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  private async loadPersonsByType(
    familyId: string,
    type: 'CHILD' | 'PARENT',
  ): Promise<{ id: string; dto: PersonDTO }[]> {
    const persons = await lastValueFrom(this.personService.list(familyId));
    const dtos = await Promise.all(
      persons.filter((p) => !!p.id).map((p) => lastValueFrom(this.personService.getFull(p.id!)))
    );
    return dtos
      .filter((dto) => dto.basicProperties?.find((f) => f.fieldName === 'personType')?.value === type)
      .map((dto) => ({ id: dto.id!, dto }));
  }
}
```

Note: `isEditMode` and `onStepChange` are removed — the template no longer needs them (Task 3 checks `resolvedFamilyId` directly for the disabled-tile state, and dialog title, and prefilling is now done in `openSection`).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include=**/family-wizard.component.spec.ts`
Expected: PASS — all 10 specs green. (The template file `family-wizard.component.html` still references removed members like `onStepChange`/`stepper` at this point; that's fine for this test run since Jasmine instantiates the class directly without compiling the template. Task 3 fixes the template next.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/family-wizard.component.ts frontend/src/app/administration/families/family-wizard/family-wizard.component.spec.ts
git commit -m "refactor: replace family wizard stepper logic with view-state and per-section save"
```

---

## Task 3: Overview + section templates and styles

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.html`
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.scss`

**Interfaces:**
- Consumes: everything produced by Task 2 (`view`, `resolvedFamilyId`, `existingChildren`, `existingParents`, `editFamily`, `loading`, `submitting`, `familyStep`/`childStep`/`parentsStep` template refs via `@ViewChild`, `openSection`, `backToOverview`, `cancel`, `saveFamily`, `saveChildren`, `saveParents`), and Task 1's `<app-family-step [editFamily]>`.
- Produces: nothing consumed by later tasks — this is the last piece of the dialog itself.

- [ ] **Step 1: Replace the template**

Replace the full contents of `frontend/src/app/administration/families/family-wizard/family-wizard.component.html`:

```html
<h2 mat-dialog-title>{{ resolvedFamilyId ? 'Familie bearbeiten' : 'Familie erstellen' }}</h2>

<mat-dialog-content>
  @if (loading) {
    <p>Laden…</p>
  } @else {
    @if (view === 'overview') {
      <div class="section-actions">
        <button mat-button (click)="cancel()">Abbrechen</button>
      </div>

      <div class="overview-tiles">
        <button mat-stroked-button class="overview-tile" (click)="openSection('family')">
          <mat-icon>home</mat-icon>
          <span>Familie</span>
          <mat-icon class="chevron">chevron_right</mat-icon>
        </button>

        <button mat-stroked-button class="overview-tile" [disabled]="!resolvedFamilyId"
          (click)="openSection('children')">
          <mat-icon>child_care</mat-icon>
          <span>Kinder ({{ existingChildren.length }})</span>
          @if (!resolvedFamilyId) {
            <span class="hint">Erst Familie speichern</span>
          } @else {
            <mat-icon class="chevron">chevron_right</mat-icon>
          }
        </button>

        <button mat-stroked-button class="overview-tile" [disabled]="!resolvedFamilyId"
          (click)="openSection('parents')">
          <mat-icon>person</mat-icon>
          <span>Eltern ({{ existingParents.length }})</span>
          @if (!resolvedFamilyId) {
            <span class="hint">Erst Familie speichern</span>
          } @else {
            <mat-icon class="chevron">chevron_right</mat-icon>
          }
        </button>
      </div>
    }

    @if (view === 'family') {
      <div class="section-actions">
        <button mat-button (click)="backToOverview()">Abbrechen</button>
        <button mat-raised-button color="primary"
          [disabled]="(familyStep ? !familyStep.isValid : true) || submitting"
          (click)="saveFamily()">Speichern</button>
      </div>
      <app-family-step [editFamily]="editFamily"></app-family-step>
    }

    @if (view === 'children') {
      <div class="section-actions">
        <button mat-button (click)="backToOverview()">Abbrechen</button>
        <button mat-raised-button color="primary"
          [disabled]="(childStep ? !childStep.isValid : true) || submitting"
          (click)="saveChildren()">Speichern</button>
      </div>
      <app-child-step [existingChildren]="existingChildren"></app-child-step>
    }

    @if (view === 'parents') {
      <div class="section-actions">
        <button mat-button (click)="backToOverview()">Abbrechen</button>
        <button mat-raised-button color="primary"
          [disabled]="(parentsStep ? !parentsStep.isValid : true) || submitting"
          (click)="saveParents()">Speichern</button>
      </div>
      <app-parents-step [existingParents]="existingParents"></app-parents-step>
    }
  }
</mat-dialog-content>
```

- [ ] **Step 2: Update styles — rename `.step-actions` to `.section-actions` and add tile styles**

Replace the full contents of `frontend/src/app/administration/families/family-wizard/family-wizard.component.scss`:

```scss
:host {
  display: block;
}

.row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.row mat-form-field {
  flex: 1;
  min-width: 200px;
}

.full-width {
  width: 100%;
}

.parent-section {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.parent-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.street-field {
  flex: 2;
}

.zip-field {
  flex: 0.5;
  min-width: 100px;
}

.section-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
  background: white;
  padding-bottom: 16px;
  margin-bottom: 8px;
}

.overview-tiles {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.overview-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 16px;
  justify-content: flex-start;
  text-align: left;
}

.overview-tile span {
  flex: 1;
}

.overview-tile .hint {
  flex: 0 0 auto;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.5);
}

.overview-tile .chevron {
  flex: 0 0 auto;
}
```

- [ ] **Step 3: Run the full family-wizard test suite to verify nothing broke**

Run: `cd frontend && npx ng test --watch=false --include=**/family-wizard/**/*.spec.ts`
Expected: PASS — all specs from Task 1 and Task 2 still green (this step doesn't add new specs; it validates that the template/style changes didn't break component compilation).

- [ ] **Step 4: Typecheck**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/family-wizard.component.html frontend/src/app/administration/families/family-wizard/family-wizard.component.scss
git commit -m "feat: overview UI with section tiles for family wizard dialog"
```

---

## Task 4: "Familie erstellen" entry point in family-list

**Files:**
- Modify: `frontend/src/app/administration/families/family-list/family-list.component.ts`
- Modify: `frontend/src/app/administration/families/family-list/family-list.component.html`

**Interfaces:**
- Consumes: `FamilyWizardComponent` (already imported in this file), `MatDialog` (already injected as `this.dialog`), `loadData()` (already exists, private).
- Produces: `openCreateWizard(): void` — no other file depends on it.

This task has no separate unit test: `family-list.component.ts` has no existing spec file, and its sibling `openEditWizard()` method (same `MatDialog.open` + `afterClosed` pattern) is likewise untested in this codebase — `MatDialog.open` isn't trivially fakeable without `MatDialogModule`/`TestBed`, and adding that scaffolding for one new method exceeds this task's scope. Verification is a manual UI check (Step 3).

- [ ] **Step 1: Add `openCreateWizard()` to the component**

In `frontend/src/app/administration/families/family-list/family-list.component.ts`, add a new public method right after `openEditWizard`:

```ts
  openEditWizard(familyId: string): void {
    const dialogRef = this.dialog.open(FamilyWizardComponent, {
      width: '700px',
      maxWidth: '95vw',
      disableClose: true,
      data: { familyId },
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.loadData();
      }
    });
  }

  openCreateWizard(): void {
    const dialogRef = this.dialog.open(FamilyWizardComponent, {
      width: '700px',
      maxWidth: '95vw',
      disableClose: true,
      data: null,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.loadData();
      }
    });
  }
```

- [ ] **Step 2: Add the button to the header**

In `frontend/src/app/administration/families/family-list/family-list.component.html`, replace the `.actions` block:

```html
    <div class="actions">
      <mat-form-field appearance="outline" class="filter-field">
        <mat-label>Filter</mat-label>
        <input matInput (keyup)="applyFilter($event)" placeholder="Suchen...">
      </mat-form-field>
      <button mat-raised-button color="primary" (click)="openCreateWizard()">
        <mat-icon>add</mat-icon> Familie erstellen
      </button>
    </div>
```

- [ ] **Step 3: Manually verify in the browser**

Run: `cd frontend && npx ng serve`
Navigate to the Familien administration page. Confirm:
- "Familie erstellen" button opens the dialog on the overview with only a "Familie"-Kachel enabled ("Kinder"/"Eltern" show "Erst Familie speichern" and are disabled).
- Saving the family in that dialog switches the title to "Familie bearbeiten" and enables the Kinder/Eltern tiles without closing the dialog.
- Adding a child then clicking Speichern returns to the overview with the Kinder count updated, and the dialog is still open.
- Closing the dialog (Abbrechen on the overview) reloads the family list and shows the new family.
- "Bearbeiten" on an existing family still opens directly on the overview with all three tiles enabled and pre-populated counts.

- [ ] **Step 4: Typecheck**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/families/family-list/family-list.component.ts frontend/src/app/administration/families/family-list/family-list.component.html
git commit -m "feat: add Familie erstellen entry point to family list"
```
