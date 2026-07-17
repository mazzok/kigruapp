# Familien bearbeiten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable full editing of families (metadata, persons, add/remove) by extending the existing FamilyWizardComponent with a dual create/edit mode.

**Architecture:** `FamilyWizardComponent` receives `familyId` via `MAT_DIALOG_DATA`; if present it loads existing data, pre-fills all steps, and on submit runs PUT/POST/DELETE instead of only POST. The backend `PUT /persons/{id}` is refactored to accept `CreatePersonRequest` (replace-all field instances) matching the create flow.

**Tech Stack:** Angular 17 (standalone components, `@for` control flow), Angular Material (MatDialog, MatStepper), Quarkus/Panache (MongoDB), Java records.

## Global Constraints

- Angular `@for`/`@if` control flow syntax only — no `*ngFor`/`*ngIf` directives
- All components are standalone — add every dependency to `imports: []`
- `lastValueFrom()` for all RxJS-to-Promise conversions
- PersonType values: `'CHILD'` for Kinder, `'PARENT'` for Eltern (uppercase, matches existing code)
- German UI labels throughout
- No new files except where explicitly listed

---

## File Map

| File | Change |
|------|--------|
| `backend/src/main/java/at/kigruapp/resource/PersonResource.java` | Replace raw-Person PUT with CreatePersonRequest-based PUT |
| `frontend/src/app/administration/families/services/family.service.ts` | Add `get(id)` and `update(id, data)` methods |
| `frontend/src/app/shared/services/person.service.ts` | Change `update()` signature to `CreatePersonRequest` |
| `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts` | Add `editFamily` Input + edit mode logic |
| `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html` | Hide radio toggle in edit mode |
| `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts` | Add `existingChildren` Input, multi-child edit support |
| `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts` | Add `existingParents` Input, ID tracking, `getParentsData()` |
| `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts` | Dual-mode orchestration, `MAT_DIALOG_DATA`, edit submit |
| `frontend/src/app/administration/families/family-wizard/family-wizard.component.html` | Edit-mode title, always-show parents step in edit mode |
| `frontend/src/app/administration/families/family-list/family-list.component.ts` | Add `openEditWizard(familyId)` |
| `frontend/src/app/administration/families/family-list/family-list.component.html` | Add edit button per family |

---

### Task 1: Backend — PUT /persons/{id} accepts CreatePersonRequest

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`

**Interfaces:**
- Consumes: existing `CreatePersonRequest` record (already defined in same file), existing `createFieldInstances()` and `deleteFieldInstances()` private helpers
- Produces: `PUT /persons/{id}` now accepts `CreatePersonRequest` body, returns updated `Person`

- [ ] **Step 1: Locate the PUT endpoint**

Open `PersonResource.java`. Find the `@PUT @Path("/{id}")` method (around line 169). It currently takes `Person update` as body parameter.

- [ ] **Step 2: Replace the PUT endpoint implementation**

Replace the entire `update` method with:

```java
@PUT
@Path("/{id}")
public Response update(@PathParam("id") String id, CreatePersonRequest request) {
    Person person = Person.findById(new ObjectId(id));
    if (person == null) {
        throw new NotFoundException();
    }
    // Delete all existing field instances for this person
    deleteFieldInstances(person.basicProperties);
    deleteFieldInstances(person.roles);
    deleteFieldInstances(person.schedules);
    deleteFieldInstances(person.duties);
    deleteFieldInstances(person.finance);
    deleteFieldInstances(person.customProperties);
    deleteFieldInstances(person.organisationalUnit);
    // Create fresh field instances from request
    Instant now = Instant.now();
    person.basicProperties = createFieldInstances(request.basicProperties(), now);
    person.roles = createFieldInstances(request.roles(), now);
    person.schedules = createFieldInstances(request.schedules(), now);
    person.duties = createFieldInstances(request.duties(), now);
    person.finance = createFieldInstances(request.finance(), now);
    person.customProperties = createFieldInstances(request.customProperties(), now);
    person.organisationalUnit = createFieldInstances(request.organisationalUnit(), now);
    person.updatedAt = now;
    person.update();
    return Response.ok(person).build();
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd backend && ./mvnw compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 4: Manual smoke test**

Start the backend, then:

```bash
# Replace <ID> with an existing person ID from your dev DB
curl -s -X PUT http://localhost:8080/api/v1/persons/<ID> \
  -H "Content-Type: application/json" \
  -d '{
    "familyId": "<FAMILY_ID>",
    "basicProperties": [],
    "roles": [],
    "schedules": [],
    "duties": [],
    "finance": [],
    "customProperties": [],
    "organisationalUnit": []
  }'
```

Expected: JSON response with the updated person, HTTP 200.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/at/kigruapp/resource/PersonResource.java
git commit -m "refactor: PUT /persons/{id} accepts CreatePersonRequest with field instance replace-all"
```

---

### Task 2: FamilyService — add get() and update()

**Files:**
- Modify: `frontend/src/app/administration/families/services/family.service.ts`

**Interfaces:**
- Produces: `get(id: string): Observable<Family>`, `update(id: string, data: { name: string; address?: FamilyAddress }): Observable<Family>`

- [ ] **Step 1: Read the current file**

Open `frontend/src/app/administration/families/services/family.service.ts`.

- [ ] **Step 2: Add missing import and methods**

Add `FamilyAddress` to the Family model import if not present, then add two methods after `create()`:

```typescript
get(id: string): Observable<Family> {
  return this.api.get<Family>(`/families/${id}`);
}

update(id: string, data: { name: string; address?: FamilyAddress }): Observable<Family> {
  return this.api.put<Family>(`/families/${id}`, data);
}
```

Ensure `FamilyAddress` is imported from `'../../../shared/models/family.model'`. If it doesn't exist in that model file yet, check `family.model.ts` — the type is `{ street: string; zip: string; city: string }`. Add it to the model if missing:

```typescript
export interface FamilyAddress {
  street: string;
  zip: string;
  city: string;
}
```

- [ ] **Step 3: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

Expected: no TypeScript errors.

- [ ] **Step 4: Commit**

```bash
cd frontend
git add src/app/administration/families/services/family.service.ts \
        src/app/shared/models/family.model.ts
git commit -m "feat: add get() and update() to FamilyService"
```

---

### Task 3: PersonService — update() uses CreatePersonRequest

**Files:**
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Produces: `update(id: string, request: CreatePersonRequest): Observable<Person>`

- [ ] **Step 1: Change the update() signature**

In `person.service.ts`, replace:

```typescript
update(id: string, person: Person): Observable<Person> {
  return this.api.put<Person>(`/persons/${id}`, person);
}
```

with:

```typescript
update(id: string, request: CreatePersonRequest): Observable<Person> {
  return this.api.put<Person>(`/persons/${id}`, request);
}
```

`CreatePersonRequest` is already imported in this file (check the import line — it's in `'../models/person.model'`).

- [ ] **Step 2: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors. (Any call-sites passing a raw `Person` would now error — fix them if found.)

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/app/shared/services/person.service.ts
git commit -m "refactor: PersonService.update() accepts CreatePersonRequest"
```

---

### Task 4: FamilyStep — edit mode support

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`
- Modify: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html`

**Interfaces:**
- Consumes: `Family` model (already imported)
- Produces: `@Input() editFamily: Family | undefined` — when set, pre-fills form and hides radio toggle; existing getters (`isValid`, `newFamilyName`, `address`, `isNewFamily`) continue to work

- [ ] **Step 1: Add editFamily Input and editMode flag**

In `family-step.component.ts`, add after the existing `existingFamilies: Family[] = [];` line:

```typescript
editMode = false;

@Input() set editFamily(family: Family | undefined) {
  if (!family) return;
  this.editMode = true;
  this.form.patchValue({
    mode: 'new',
    newFamilyName: family.name,
    street: family.address?.street ?? '',
    zip: family.address?.zip ?? '',
    city: family.address?.city ?? '',
  });
}
```

Add `Input` to the `@angular/core` import if not already present.

- [ ] **Step 2: Update isValid for edit mode**

Replace the `get isValid()` getter with:

```typescript
get isValid(): boolean {
  if (this.editMode) {
    return !!this.form.value.newFamilyName?.trim();
  }
  if (this.form.value.mode === 'existing') {
    return !!this.form.value.existingFamilyId;
  }
  return !!this.form.value.newFamilyName?.trim();
}
```

- [ ] **Step 3: Update HTML to hide radio toggle in edit mode**

In `family-step.component.html`, wrap the `<mat-radio-group>` block with a condition:

```html
@if (!editMode) {
  <mat-radio-group formControlName="mode">
    <mat-radio-button value="new">Neue Familie erstellen</mat-radio-button>
    <mat-radio-button value="existing">Bestehende Familie verwenden</mat-radio-button>
  </mat-radio-group>
}
```

Leave the address fields and the `@if (form.value.mode === 'new')` name field unchanged — in edit mode, `mode` will be `'new'` (set by patchValue), so the name field renders automatically.

- [ ] **Step 4: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/app/administration/families/family-wizard/steps/family-step.component.ts \
        src/app/administration/families/family-wizard/steps/family-step.component.html
git commit -m "feat: FamilyStep supports edit mode with pre-fill and hidden radio toggle"
```

---

### Task 5: ChildStep — multi-child edit mode

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts`

**Interfaces:**
- Consumes: `PersonDTO` from `'../../../../shared/models/person.model'`, `FieldInstanceDTO` from `'../../../../shared/models/field-instance.model'`
- Produces:
  - `@Input() existingChildren: { id: string; dto: PersonDTO }[]`
  - `removedChildIds: string[]` (read by wizard on submit)
  - `getChildrenData(): { id?: string; basicProperties: SectionInput[] }[]` (used in edit submit)
  - `getBasicProperties(): SectionInput[]` (unchanged — used in create submit)
  - `get isEditMode(): boolean`

- [ ] **Step 1: Add imports**

In `child-step.component.ts`, add to the existing imports:

```typescript
import { Input, ViewChildren, QueryList } from '@angular/core';
// Update the existing @angular/core import line to include these
import { PersonDTO, SectionInput } from '../../../../shared/models/person.model';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
```

Add `MatButtonModule` and `MatIconModule` to the `imports: []` array in `@Component`.

- [ ] **Step 2: Add existingChildren state**

Replace the class body, keeping existing `ALLOWED_FIELDS`, `sectionForm`, `definitions`, `personTypeDef`, `constructor`, and `ngOnInit` unchanged. Add after existing fields:

```typescript
@Input() set existingChildren(children: { id: string; dto: PersonDTO }[]) {
  if (!children || children.length === 0) return;
  this._existingChildren = children;
  // If definitions already loaded, build entries immediately
  if (this.definitions.length > 0) {
    this.buildEditEntries();
  }
}
private _existingChildren: { id: string; dto: PersonDTO }[] = [];

get isEditMode(): boolean {
  return this._existingChildren.length > 0;
}

childEntries: { id?: string; existingFields: FieldInstanceDTO[] }[] = [];
removedChildIds: string[] = [];

@ViewChildren('childForm') childForms!: QueryList<SectionFormComponent>;
```

- [ ] **Step 3: Build edit entries after definitions load**

In `ngOnInit`, after setting `this.definitions`, call `buildEditEntries()` if in edit mode:

```typescript
ngOnInit(): void {
  this.fieldDefService.listActive().subscribe((defs) => {
    this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
    this.definitions = defs.filter((d) => ChildStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
    if (this._existingChildren.length > 0) {
      this.buildEditEntries();
    }
  });
}

private buildEditEntries(): void {
  this.childEntries = this._existingChildren.map((c) => ({
    id: c.id,
    existingFields: c.dto.basicProperties ?? [],
  }));
}
```

- [ ] **Step 4: Add addChild / removeChild methods**

```typescript
addChild(): void {
  this.childEntries.push({ existingFields: [] });
}

removeChild(index: number): void {
  const entry = this.childEntries[index];
  if (entry.id) {
    this.removedChildIds.push(entry.id);
  }
  this.childEntries.splice(index, 1);
}
```

- [ ] **Step 5: Add getChildrenData method**

```typescript
getChildrenData(): { id?: string; basicProperties: SectionInput[] }[] {
  return this.childForms.toArray().map((form, i) => {
    const values = form.getValues();
    if (this.personTypeDef?.id) {
      values.push({ definitionId: this.personTypeDef.id, value: 'CHILD' });
    }
    return { id: this.childEntries[i]?.id, basicProperties: values };
  });
}
```

- [ ] **Step 6: Update the component template**

The component uses an inline `template`. Replace the entire `template` string with:

```typescript
template: `
  @if (isEditMode) {
    @for (entry of childEntries; track $index; let i = $index) {
      <div class="child-block">
        <div style="display:flex; align-items:center; justify-content:space-between;">
          <h4 style="margin:0">Kind {{ i + 1 }}</h4>
          <button mat-icon-button color="warn" (click)="removeChild(i)">
            <mat-icon>delete</mat-icon>
          </button>
        </div>
        @if (definitions.length > 0) {
          <app-section-form #childForm
            [definitions]="definitions"
            [existingFields]="entry.existingFields">
          </app-section-form>
        }
      </div>
    }
    <button mat-stroked-button (click)="addChild()" style="margin-top:8px">
      <mat-icon>child_care</mat-icon> Kind hinzufügen
    </button>
  } @else {
    <h3>Kind</h3>
    @if (definitions.length > 0) {
      <app-section-form #sectionForm [definitions]="definitions"></app-section-form>
    }
  }
`,
```

- [ ] **Step 7: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/app/administration/families/family-wizard/steps/child-step.component.ts
git commit -m "feat: ChildStep supports multi-child edit mode with pre-fill, add, and remove"
```

---

### Task 6: ParentsStep — pre-fill existing parents and ID tracking

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts`

**Interfaces:**
- Consumes: `PersonDTO`, `FieldInstanceDTO`
- Produces:
  - `@Input() existingParents: { id: string; dto: PersonDTO }[]`
  - `removedParentIds: string[]` (read by wizard on submit)
  - `getParentsData(): { id?: string; basicProperties: SectionInput[] }[]` (edit submit)
  - Existing `getParentsBasicProperties(): SectionInput[][]` unchanged (create submit)

- [ ] **Step 1: Add imports**

In `parents-step.component.ts`, add to imports:

```typescript
import { PersonDTO } from '../../../../shared/models/person.model';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
```

- [ ] **Step 2: Add existingParents state and ID tracking**

Add these fields to the class (after `parentIndices`):

```typescript
@Input() set existingParents(parents: { id: string; dto: PersonDTO }[]) {
  if (!parents || parents.length === 0) return;
  this._existingParents = parents;
  if (this.definitions.length > 0) {
    this.buildEditEntries();
  }
}
private _existingParents: { id: string; dto: PersonDTO }[] = [];

parentEntries: { id?: string; existingFields: FieldInstanceDTO[] }[] = [{ existingFields: [] }];
removedParentIds: string[] = [];
```

- [ ] **Step 3: Build edit entries after definitions load**

In `ngOnInit`, after setting `this.definitions`, call `buildEditEntries()` if in edit mode:

```typescript
ngOnInit(): void {
  this.fieldDefService.listActive().subscribe((defs) => {
    this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
    this.definitions = defs.filter((d) => ParentsStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
    if (this._existingParents.length > 0) {
      this.buildEditEntries();
    } else if (this.keycloakPrefill) {
      setTimeout(() => this.applyKeycloakPrefill(), 0);
    }
  });
}

private buildEditEntries(): void {
  this.parentEntries = this._existingParents.map((p) => ({
    id: p.id,
    existingFields: p.dto.basicProperties ?? [],
  }));
  // Sync parentIndices with entry count
  this.parentIndices = this.parentEntries.map((_, i) => i);
}
```

- [ ] **Step 4: Update addParent / removeParent to use parentEntries**

Replace:

```typescript
addParent(): void {
  this.parentIndices.push(this.parentIndices.length);
}

removeParent(index: number): void {
  this.parentIndices.splice(index, 1);
}
```

With:

```typescript
addParent(): void {
  this.parentEntries.push({ existingFields: [] });
  this.parentIndices = this.parentEntries.map((_, i) => i);
}

removeParent(index: number): void {
  const entry = this.parentEntries[index];
  if (entry.id) {
    this.removedParentIds.push(entry.id);
  }
  this.parentEntries.splice(index, 1);
  this.parentIndices = this.parentEntries.map((_, i) => i);
}
```

- [ ] **Step 5: Add getParentsData method**

```typescript
getParentsData(): { id?: string; basicProperties: SectionInput[] }[] {
  return this.parentForms.toArray().map((form, i) => {
    const values = form.getValues();
    if (this.personTypeDef?.id) {
      values.push({ definitionId: this.personTypeDef.id, value: 'PARENT' });
    }
    return { id: this.parentEntries[i]?.id, basicProperties: values };
  });
}
```

- [ ] **Step 6: Pass existingFields to SectionFormComponent in template**

In the component's inline template, find the `<app-section-form #parentForm>` tag and add the `existingFields` binding:

```html
<app-section-form #parentForm
  [definitions]="definitions"
  [existingFields]="parentEntries[$index]?.existingFields ?? []">
</app-section-form>
```

(Use `$index` from the `@for` loop variable.)

- [ ] **Step 7: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/app/administration/families/family-wizard/steps/parents-step.component.ts
git commit -m "feat: ParentsStep supports pre-fill from existing parents with ID tracking"
```

---

### Task 7: FamilyWizardComponent — dual-mode orchestration

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.html`

**Interfaces:**
- Consumes: `MAT_DIALOG_DATA: { familyId?: string } | null`, all step components from Tasks 4–6, `FamilyService.get()` and `FamilyService.update()` from Task 2, `PersonService.update()` from Task 3
- Produces: `isEditMode: boolean`, `editFamily: Family | undefined`, `existingChildren`, `existingParents` (passed as `@Input` to steps)

- [ ] **Step 1: Add MAT_DIALOG_DATA injection and OnInit**

In `family-wizard.component.ts`, update imports:

```typescript
import { Component, Inject, OnInit, Optional, ViewChild } from '@angular/core';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { Family } from '../../../shared/models/family.model';
import { PersonDTO, CreatePersonRequest } from '../../../shared/models/person.model';
```

Update the constructor to inject `MAT_DIALOG_DATA` and implement `OnInit`:

```typescript
export class FamilyWizardComponent implements OnInit {
  // ... existing @ViewChild declarations ...

  editFamily?: Family;
  existingChildren: { id: string; dto: PersonDTO }[] = [];
  existingParents: { id: string; dto: PersonDTO }[] = [];
  loading = false;

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: { familyId?: string } | null,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {}

  get isEditMode(): boolean {
    return !!this.data?.familyId;
  }

  ngOnInit(): void {
    if (this.isEditMode) {
      this.loadEditData();
    }
  }
```

- [ ] **Step 2: Add loadEditData method**

```typescript
private async loadEditData(): Promise<void> {
  this.loading = true;
  try {
    const familyId = this.data!.familyId!;
    const family = await lastValueFrom(this.familyService.get(familyId));
    this.editFamily = family;

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
```

- [ ] **Step 3: Add submitEdit method and update submit()**

```typescript
async submit(): Promise<void> {
  this.submitting = true;
  try {
    if (this.isEditMode) {
      await this.submitEdit();
    } else {
      await this.submitCreate();
    }
    this.dialogRef.close(true);
  } catch (err) {
    console.error('Wizard failed:', err);
    this.submitting = false;
  }
}

private async submitCreate(): Promise<void> {
  // Move existing submit() body here unchanged
  let familyId: string;
  if (this.familyStep.isNewFamily) {
    const family = await lastValueFrom(
      this.familyService.create({
        name: this.familyStep.newFamilyName,
        address: this.familyStep.address ?? undefined,
      })
    );
    familyId = family.id!;
  } else {
    familyId = this.familyStep.selectedFamilyId!;
  }

  const childRequest: CreatePersonRequest = {
    familyId,
    basicProperties: this.childStep.getBasicProperties(),
    roles: [], schedules: [], duties: [], finance: [], customProperties: [], organisationalUnit: [],
  };
  await lastValueFrom(this.personService.create(childRequest));

  const parentsProps = this.parentsStep?.getParentsBasicProperties() ?? [];
  for (const parentProps of parentsProps) {
    const parentRequest: CreatePersonRequest = {
      familyId,
      basicProperties: parentProps,
      roles: [], schedules: [], duties: [], finance: [], customProperties: [], organisationalUnit: [],
    };
    await lastValueFrom(this.personService.create(parentRequest));
  }
}

private async submitEdit(): Promise<void> {
  const familyId = this.data!.familyId!;

  // 1. Update family metadata
  await lastValueFrom(this.familyService.update(familyId, {
    name: this.familyStep.newFamilyName,
    address: this.familyStep.address ?? undefined,
  }));

  // 2. Save children (create new, update existing)
  const childrenData = this.childStep.getChildrenData();
  for (const child of childrenData) {
    const req: CreatePersonRequest = {
      familyId,
      basicProperties: child.basicProperties,
      roles: [], schedules: [], duties: [], finance: [], customProperties: [], organisationalUnit: [],
    };
    if (child.id) {
      await lastValueFrom(this.personService.update(child.id, req));
    } else {
      await lastValueFrom(this.personService.create(req));
    }
  }

  // 3. Save parents (create new, update existing)
  const parentsData = this.parentsStep.getParentsData();
  for (const parent of parentsData) {
    const req: CreatePersonRequest = {
      familyId,
      basicProperties: parent.basicProperties,
      roles: [], schedules: [], duties: [], finance: [], customProperties: [], organisationalUnit: [],
    };
    if (parent.id) {
      await lastValueFrom(this.personService.update(parent.id, req));
    } else {
      await lastValueFrom(this.personService.create(req));
    }
  }

  // 4. Delete removed persons
  const removedIds = [
    ...(this.childStep.removedChildIds ?? []),
    ...(this.parentsStep.removedParentIds ?? []),
  ];
  for (const id of removedIds) {
    await lastValueFrom(this.personService.delete(id));
  }
}
```

- [ ] **Step 4: Pass inputs to steps in template**

In `family-wizard.component.html`, update the step components to pass the edit-mode inputs:

```html
<h2 mat-dialog-title>{{ isEditMode ? 'Familie bearbeiten' : 'Neues Kind' }}</h2>

<mat-dialog-content>
  @if (loading) {
    <p>Laden…</p>
  } @else {
    <mat-stepper #stepper (selectionChange)="onStepChange($event)">
      <mat-step>
        <ng-template matStepLabel>Familie</ng-template>
        <app-family-step [editFamily]="editFamily"></app-family-step>
        <div class="step-actions">
          <button mat-raised-button color="primary" matStepperNext
            [disabled]="familyStep ? !familyStep.isValid : true">Weiter</button>
        </div>
      </mat-step>

      <mat-step>
        <ng-template matStepLabel>Kind(er)</ng-template>
        <app-child-step [existingChildren]="existingChildren"></app-child-step>
        <div class="step-actions">
          <button mat-button matStepperPrevious>Zurück</button>
          @if (familyStep?.isNewFamily || isEditMode) {
            <button mat-raised-button color="primary" matStepperNext
              [disabled]="childStep ? !childStep.isValid : true">Weiter</button>
          } @else {
            <button mat-raised-button color="primary"
              [disabled]="(childStep ? !childStep.isValid : true) || submitting"
              (click)="submit()">Kind anlegen</button>
          }
        </div>
      </mat-step>

      @if (familyStep?.isNewFamily || isEditMode) {
        <mat-step>
          <ng-template matStepLabel>Eltern</ng-template>
          <app-parents-step [existingParents]="existingParents"></app-parents-step>
          <div class="step-actions">
            <button mat-button matStepperPrevious>Zurück</button>
            <button mat-raised-button color="primary"
              [disabled]="(parentsStep ? !parentsStep.isValid : true) || submitting"
              (click)="submit()">{{ isEditMode ? 'Speichern' : 'Familie erstellen' }}</button>
          </div>
        </mat-step>
      }
    </mat-stepper>
  }
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Abbrechen</button>
</mat-dialog-actions>
```

- [ ] **Step 5: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
cd frontend
git add src/app/administration/families/family-wizard/family-wizard.component.ts \
        src/app/administration/families/family-wizard/family-wizard.component.html
git commit -m "feat: FamilyWizardComponent dual create/edit mode with full submit logic"
```

---

### Task 8: FamilyListComponent — edit button

**Files:**
- Modify: `frontend/src/app/administration/families/family-list/family-list.component.ts`
- Modify: `frontend/src/app/administration/families/family-list/family-list.component.html`

**Interfaces:**
- Consumes: `FamilyWizardComponent` (already imported), `Family.id`
- Produces: `openEditWizard(familyId: string): void`

- [ ] **Step 1: Add openEditWizard method and fix openWizard data**

In `family-list.component.ts`, add after `openWizard()`:

```typescript
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
```

Also update `openWizard()` to explicitly pass `data: null` so `MAT_DIALOG_DATA` injection works:

```typescript
openWizard(): void {
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

- [ ] **Step 2: Add edit button to family list HTML**

In `family-list.component.html`, find the accordion panel header for each family. It likely looks like:

```html
<mat-expansion-panel-header>
  <mat-panel-title>{{ node.family.name }}</mat-panel-title>
  ...
</mat-expansion-panel-header>
```

Add an edit button inside the header (or next to the title):

```html
<mat-expansion-panel-header>
  <mat-panel-title>
    {{ node.family.name }}
  </mat-panel-title>
  <mat-panel-description>
    {{ node.childCount }} Kind(er) · {{ node.parentCount }} Elternteil(e)
  </mat-panel-description>
</mat-expansion-panel-header>
<!-- Add this after the header, inside the panel but before the table: -->
```

Actually, add the button as a header action. Place it in `mat-panel-description` or right after the `mat-expansion-panel-header` closing tag — in the panel body, at the top:

```html
<div style="display:flex; justify-content:flex-end; margin-bottom:8px;">
  <button mat-stroked-button (click)="openEditWizard(node.family.id!); $event.stopPropagation()">
    <mat-icon>edit</mat-icon> Bearbeiten
  </button>
</div>
```

Place this div as the first element inside `<mat-expansion-panel>` (after the header).

- [ ] **Step 3: Verify build**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | grep -i error
```

Expected: no errors.

- [ ] **Step 4: End-to-end manual test**

1. Start backend: `cd backend && ./mvnw quarkus:dev`
2. Start frontend: `cd frontend && npx ng serve`
3. Navigate to Familien list
4. Expand a family — verify "Bearbeiten" button appears
5. Click "Bearbeiten" — wizard opens with "Familie bearbeiten" title, Schritt 1 pre-filled with family name/address (no radio toggle)
6. Advance to Schritt 2 — existing children shown with their data pre-filled
7. Add a child — new empty form appears
8. Remove a child — child disappears from list
9. Advance to Schritt 3 — existing parents shown with their data pre-filled
10. Add a parent — new empty form appears
11. Click "Speichern" — dialog closes, family list reloads with updated data

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/app/administration/families/family-list/family-list.component.ts \
        src/app/administration/families/family-list/family-list.component.html
git commit -m "feat: add edit button per family in FamilyListComponent opening wizard in edit mode"
```
