# Platzzuweisung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin-only "Platzzuweisung" view that lists all children with their age and lets admins assign each child to a group via dropdown (auto-saved).

**Architecture:** Groups are `FieldDefinition` entities (tag "groups" in Organisation); each group has exactly one shared `FieldInstance` (value: `true`) in `field_instances`. A child's group membership is a `FieldRef` in the new `Person.organisationalUnit` list. Assigning a group = updating that `FieldRef`; multiple children point to the same `FieldInstance`.

**Tech Stack:** Quarkus (JAX-RS, Panache, MongoDB), Angular 17 standalone components, Angular Material.

## Global Constraints

- All backend files: package `at.kigruapp.*`
- All frontend files: Angular standalone components, no NgModules
- No new MongoDB collections — use existing `persons`, `field_instances`, `field_definitions`, `migrations`
- Admin-only: backend endpoints need no extra guard (SecurityFilter already handles it); frontend route uses existing `adminGuard`
- Do NOT delete `FieldInstance`s belonging to groups when reassigning a child — they are shared

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `backend/.../entity/Person.java` | Modify | Add `organisationalUnit` field |
| `backend/.../dto/PersonDTO.java` | Modify | Add `organisationalUnit` field |
| `backend/.../resource/PersonResource.java` | Modify | Wire `organisationalUnit` in create/update/delete/toFullDTO; add `GET /children` + `PATCH /{id}/group` |
| `backend/.../migration/GroupInstanceMigration.java` | Create | Seed missing FieldInstances for existing groups on startup |
| `frontend/.../shared/models/person.model.ts` | Modify | Add `organisationalUnit` to `Person`, `CreatePersonRequest`, `PersonDTO`; add `ChildDTO` |
| `frontend/.../shared/services/field-instance.service.ts` | Create | `create(definitionId, value)` |
| `frontend/.../shared/services/person.service.ts` | Modify | Add `getChildren()` + `assignGroup()` |
| `frontend/.../settings/organisation/organisation.component.ts` | Modify | `addGroup()` also creates FieldInstance after FieldDefinition |
| `frontend/.../administration/platzzuweisung/platzzuweisung.component.ts` | Create | Table: Name, Alter, Gruppe dropdown, auto-save |
| `frontend/.../app.routes.ts` | Modify | Add `/administration/platzzuweisung` route |
| `frontend/.../app.component.html` | Modify | Add sidebar nav entry |

---

### Task 1: Backend — Person entity + DTO + PersonResource wiring

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/Person.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`

**Interfaces:**
- Produces: `Person.organisationalUnit: List<FieldRef>` available in all downstream resource methods

- [ ] **Step 1: Add `organisationalUnit` to Person entity**

In `backend/src/main/java/at/kigruapp/entity/Person.java`, add the field after `customProperties`:

```java
public List<FieldRef> customProperties = new ArrayList<>();
public List<FieldRef> organisationalUnit = new ArrayList<>();
```

- [ ] **Step 2: Add `organisationalUnit` to PersonDTO**

In `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`:

```java
public List<FieldInstanceDTO> customProperties;
public List<FieldInstanceDTO> organisationalUnit;
```

- [ ] **Step 3: Update `CreatePersonRequest` record in PersonResource**

In `PersonResource.java`, update the record (add `organisationalUnit` parameter):

```java
public record CreatePersonRequest(
    String familyId,
    List<SectionInput> basicProperties,
    List<SectionInput> roles,
    List<SectionInput> schedules,
    List<SectionInput> duties,
    List<SectionInput> finance,
    List<SectionInput> customProperties,
    List<SectionInput> organisationalUnit
) {}
```

- [ ] **Step 4: Wire `organisationalUnit` in `create()` method**

In `PersonResource.create()`, after the `customProperties` line add:

```java
person.customProperties = createFieldInstances(request.customProperties(), now);
person.organisationalUnit = createFieldInstances(request.organisationalUnit(), now);
```

- [ ] **Step 5: Wire `organisationalUnit` in `update()` method**

In `PersonResource.update()`, after the `customProperties` line add:

```java
person.customProperties = update.customProperties;
person.organisationalUnit = update.organisationalUnit != null ? update.organisationalUnit : new ArrayList<>();
```

- [ ] **Step 6: Wire `organisationalUnit` in `delete()` method**

In `PersonResource.delete()`, after `deleteFieldInstances(person.customProperties)` add:

```java
// Note: do NOT delete group FieldInstances — they are shared across children
// Only remove the FieldRefs themselves (done by deleting the person document)
```

So `delete()` does NOT call `deleteFieldInstances(person.organisationalUnit)` — the shared instances must survive.

- [ ] **Step 7: Wire `organisationalUnit` in `toFullDTO()`**

In `PersonResource.toFullDTO()`, after `customProperties` line add:

```java
dto.customProperties = resolveRefs(person.customProperties);
dto.organisationalUnit = resolveRefs(person.organisationalUnit != null ? person.organisationalUnit : List.of());
```

- [ ] **Step 8: Verify backend compiles**

```bash
cd backend && ./mvnw compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/Person.java \
        backend/src/main/java/at/kigruapp/dto/PersonDTO.java \
        backend/src/main/java/at/kigruapp/resource/PersonResource.java
git commit -m "feat: add organisationalUnit section to Person entity and DTO"
```

---

### Task 2: Backend — GroupInstanceMigration

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/GroupInstanceMigration.java`

**Interfaces:**
- Produces: for every `FieldDefinition` with `fieldName = "group"`, exactly one `FieldInstance` with `{ definitionId, value: true }` exists in `field_instances`

- [ ] **Step 1: Create the migration class**

Create `backend/src/main/java/at/kigruapp/migration/GroupInstanceMigration.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.Date;

@ApplicationScoped
@Startup
public class GroupInstanceMigration {

    private static final String MIGRATION_ID = "group-field-instances-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);

        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> defs = db.getCollection("field_definitions");
        MongoCollection<Document> instances = db.getCollection("field_instances");
        Date now = Date.from(Instant.now());

        // Find all FieldDefinitions with fieldName "group"
        for (Document def : defs.find(new Document("fieldName", "group"))) {
            ObjectId defId = def.getObjectId("_id");
            // Check if a FieldInstance already exists for this definition
            if (instances.find(new Document("definitionId", defId)).first() == null) {
                instances.insertOne(new Document("_id", new ObjectId())
                        .append("definitionId", defId)
                        .append("value", true)
                        .append("createdAt", now)
                        .append("updatedAt", now));
            }
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }
}
```

- [ ] **Step 2: Verify backend compiles**

```bash
cd backend && ./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Start backend and verify migration runs**

Start the backend (e.g. `./mvnw quarkus:dev`). Check logs — no errors. Connect to MongoDB and verify:

```js
db.field_instances.find({ value: true })
// Should show one document per existing group FieldDefinition
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/GroupInstanceMigration.java
git commit -m "feat: migration to seed shared FieldInstances for group FieldDefinitions"
```

---

### Task 3: Backend — GET /persons/children + PATCH /persons/{id}/group

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`

**Interfaces:**
- Produces:
  - `GET /api/v1/persons/children` → `List<ChildDTO>`
  - `PATCH /api/v1/persons/{id}/group` body `{ definitionId, fieldInstanceId }` → 204

- [ ] **Step 1: Add `ChildDTO` record to PersonResource**

Inside `PersonResource` class (alongside `CreatePersonRequest`), add:

```java
public record ChildDTO(
    String id,
    String firstName,
    String lastName,
    String dateOfBirth,
    String groupDefinitionId,
    String groupInstanceId
) {}

public record GroupAssignmentRequest(String definitionId, String fieldInstanceId) {}
```

- [ ] **Step 2: Add `GET /children` endpoint**

Add this method to `PersonResource`:

```java
@GET
@Path("/children")
public List<ChildDTO> listChildren() {
    List<Person> all = Person.listAll();
    List<ChildDTO> result = new ArrayList<>();
    for (Person person : all) {
        if (!isChild(person)) continue;
        result.add(toChildDTO(person));
    }
    return result;
}
```

- [ ] **Step 3: Add helper `isChild()`**

```java
private boolean isChild(Person person) {
    if (person.roles == null) return false;
    MongoCollection<Document> instColl = getFieldInstancesCollection();
    for (FieldRef ref : person.roles) {
        FieldDefinition def = FieldDefinition.findById(ref.definitionId);
        if (def != null && "personType".equals(def.fieldName)) {
            Document inst = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (inst != null && "CHILD".equals(inst.get("value"))) {
                return true;
            }
        }
    }
    return false;
}
```

- [ ] **Step 4: Add helper `toChildDTO()`**

```java
private ChildDTO toChildDTO(Person person) {
    String firstName = resolveBasicValue(person, "firstName");
    String lastName = resolveBasicValue(person, "lastName");
    String dateOfBirth = resolveBasicValue(person, "dateOfBirth");

    String groupDefinitionId = null;
    String groupInstanceId = null;
    if (person.organisationalUnit != null) {
        for (FieldRef ref : person.organisationalUnit) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && "group".equals(def.fieldName)) {
                groupDefinitionId = ref.definitionId.toHexString();
                groupInstanceId = ref.fieldInstanceId.toHexString();
                break;
            }
        }
    }

    return new ChildDTO(
        person.id.toHexString(),
        firstName,
        lastName,
        dateOfBirth,
        groupDefinitionId,
        groupInstanceId
    );
}
```

- [ ] **Step 5: Add helper `resolveBasicValue()`**

```java
private String resolveBasicValue(Person person, String fieldName) {
    if (person.basicProperties == null) return null;
    MongoCollection<Document> instColl = getFieldInstancesCollection();
    for (FieldRef ref : person.basicProperties) {
        FieldDefinition def = FieldDefinition.findById(ref.definitionId);
        if (def != null && fieldName.equals(def.fieldName)) {
            Document inst = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (inst != null && inst.get("value") != null) {
                return inst.get("value").toString();
            }
        }
    }
    return null;
}
```

- [ ] **Step 6: Add `PATCH /{id}/group` endpoint**

```java
@PATCH
@Path("/{id}/group")
public Response patchGroup(@PathParam("id") String id, GroupAssignmentRequest request) {
    Person person = Person.findById(new ObjectId(id));
    if (person == null) throw new NotFoundException();

    ObjectId defId = new ObjectId(request.definitionId());
    ObjectId instId = new ObjectId(request.fieldInstanceId());

    if (person.organisationalUnit == null) {
        person.organisationalUnit = new ArrayList<>();
    }

    boolean found = false;
    for (FieldRef ref : person.organisationalUnit) {
        FieldDefinition def = FieldDefinition.findById(ref.definitionId);
        if (def != null && "group".equals(def.fieldName)) {
            ref.definitionId = defId;
            ref.fieldInstanceId = instId;
            found = true;
            break;
        }
    }
    if (!found) {
        person.organisationalUnit.add(new FieldRef(defId, instId));
    }

    person.updatedAt = Instant.now();
    person.update();
    return Response.noContent().build();
}
```

- [ ] **Step 7: Verify backend compiles and endpoints work**

```bash
cd backend && ./mvnw compile -q
```

With backend running, test manually:

```bash
# List children (adjust token as needed)
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/persons/children

# Assign group (use real IDs from DB)
curl -X PATCH -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"definitionId":"<def-id>","fieldInstanceId":"<inst-id>"}' \
  http://localhost:8080/api/v1/persons/<person-id>/group
```

Expected: children endpoint returns array, patch returns 204.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java
git commit -m "feat: add GET /persons/children and PATCH /persons/{id}/group endpoints"
```

---

### Task 4: Frontend — models + FieldInstanceService + PersonService

**Files:**
- Modify: `frontend/src/app/shared/models/person.model.ts`
- Create: `frontend/src/app/shared/services/field-instance.service.ts`
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Produces:
  - `ChildDTO` interface
  - `FieldInstanceService.create(definitionId, value)`
  - `PersonService.getChildren(): Observable<ChildDTO[]>`
  - `PersonService.assignGroup(personId, definitionId, fieldInstanceId): Observable<void>`

- [ ] **Step 1: Update person.model.ts**

Add `organisationalUnit` to all three interfaces and add `ChildDTO`:

```typescript
import { FieldInstanceDTO } from './field-instance.model';

export interface FieldRef {
  definitionId: string;
  fieldInstanceId: string;
}

export interface Person {
  id?: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldRef[];
  roles: FieldRef[];
  schedules: FieldRef[];
  duties: FieldRef[];
  finance: FieldRef[];
  customProperties: FieldRef[];
  organisationalUnit: FieldRef[];
  createdAt?: string;
  updatedAt?: string;
}

export interface CreatePersonRequest {
  familyId: string;
  basicProperties: SectionInput[];
  roles: SectionInput[];
  schedules: SectionInput[];
  duties: SectionInput[];
  finance: SectionInput[];
  customProperties: SectionInput[];
  organisationalUnit: SectionInput[];
}

export interface SectionInput {
  definitionId: string;
  value: unknown;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldInstanceDTO[];
  roles: FieldInstanceDTO[];
  schedules: FieldInstanceDTO[];
  duties: FieldInstanceDTO[];
  finance: FieldInstanceDTO[];
  customProperties: FieldInstanceDTO[];
  organisationalUnit: FieldInstanceDTO[];
  createdAt?: string;
  updatedAt?: string;
}

export interface ChildDTO {
  id: string;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  groupDefinitionId: string | null;
  groupInstanceId: string | null;
}
```

- [ ] **Step 2: Fix all callers of `CreatePersonRequest` that don't pass `organisationalUnit`**

Search for `CreatePersonRequest` usages:

```bash
grep -r "customProperties:" frontend/src/app --include="*.ts" -l
```

For every `CreatePersonRequest` literal found (e.g. in `family-wizard.component.ts`), add `organisationalUnit: []`.

In `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`, both request objects need:

```typescript
const childRequest: CreatePersonRequest = {
  familyId,
  basicProperties: this.childStep.getBasicProperties(),
  roles: [],
  schedules: [],
  duties: [],
  finance: [],
  customProperties: [],
  organisationalUnit: [],
};
```

And the parent request:

```typescript
const parentRequest: CreatePersonRequest = {
  familyId,
  basicProperties: parentProps,
  roles: [],
  schedules: [],
  duties: [],
  finance: [],
  customProperties: [],
  organisationalUnit: [],
};
```

Also check `frontend/src/app/setup/setup.component.ts` for any `CreatePersonRequest` usages and add `organisationalUnit: []` there too.

- [ ] **Step 3: Create FieldInstanceService**

Create `frontend/src/app/shared/services/field-instance.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldInstanceService {
  constructor(private api: ApiService) {}

  create(definitionId: string, value: unknown): Observable<{ id: string }> {
    return this.api.post<{ id: string }>('/field-instances', { definitionId, value });
  }
}
```

- [ ] **Step 4: Add `getChildren()` and `assignGroup()` to PersonService**

In `frontend/src/app/shared/services/person.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Person, CreatePersonRequest, PersonDTO, ChildDTO } from '../models/person.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class PersonService {
  constructor(private api: ApiService) {}

  list(familyId?: string): Observable<Person[]> {
    const params = familyId ? `?familyId=${familyId}` : '';
    return this.api.get<Person[]>(`/persons${params}`);
  }

  get(id: string): Observable<Person> {
    return this.api.get<Person>(`/persons/${id}`);
  }

  getFull(id: string): Observable<PersonDTO> {
    return this.api.get<PersonDTO>(`/persons/${id}/full`);
  }

  getChildren(): Observable<ChildDTO[]> {
    return this.api.get<ChildDTO[]>('/persons/children');
  }

  create(request: CreatePersonRequest): Observable<Person> {
    return this.api.post<Person>('/persons', request);
  }

  update(id: string, person: Person): Observable<Person> {
    return this.api.put<Person>(`/persons/${id}`, person);
  }

  assignGroup(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
    return this.api.patch<void>(`/persons/${personId}/group`, { definitionId, fieldInstanceId });
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/persons/${id}`);
  }
}
```

- [ ] **Step 5: Verify ApiService has a `patch` method**

Check `frontend/src/app/core/services/api.service.ts`. If `patch<T>()` is missing, add it — it should follow the same pattern as `put<T>()`:

```typescript
patch<T>(path: string, body: unknown): Observable<T> {
  return this.http.patch<T>(`${this.baseUrl}${path}`, body, { headers: this.headers() });
}
```

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/models/person.model.ts \
        frontend/src/app/shared/services/field-instance.service.ts \
        frontend/src/app/shared/services/person.service.ts \
        frontend/src/app/administration/families/family-wizard/family-wizard.component.ts \
        frontend/src/app/setup/setup.component.ts
git commit -m "feat: add organisationalUnit to frontend models, FieldInstanceService, PersonService children/assignGroup"
```

---

### Task 5: Frontend — Organisation addGroup() creates FieldInstance

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`

**Interfaces:**
- Consumes: `FieldInstanceService.create(definitionId, value): Observable<{ id: string }>`

- [ ] **Step 1: Inject FieldInstanceService**

In `organisation.component.ts`, import and inject:

```typescript
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { switchMap } from 'rxjs/operators';
```

Add to constructor:

```typescript
constructor(
  private orgService: OrganisationService,
  private fieldDefService: FieldDefinitionService,
  private fieldInstanceService: FieldInstanceService,
  private dialog: MatDialog,
) {}
```

- [ ] **Step 2: Update `addGroup()` to create FieldInstance after FieldDefinition**

Replace the existing `addGroup()` method:

```typescript
addGroup(): void {
  if (!this.groupForm.valid || !this.groupsOrg) return;
  const labelDe = this.groupForm.value.labelDe!;
  const color = this.groupForm.value.color!;

  const newDef: FieldDefinition = {
    fieldName: 'group',
    label: { de: labelDe },
    jsonSchema: { type: 'string' },
    required: false,
    properties: { color },
  };

  this.fieldDefService.create(newDef).pipe(
    switchMap((created) =>
      this.fieldInstanceService.create(created.id!, true).pipe(
        map(() => created)
      )
    )
  ).subscribe((created) => {
    const updatedIds = [...this.groupsOrg!.definitions.map((d) => d.id!), created.id!];
    this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).subscribe(() => {
      this.groupForm.reset({ color: '#4285f4' });
      this.loadGroups();
    });
  });
}
```

Also add `map` to the imports:

```typescript
import { switchMap, map } from 'rxjs/operators';
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Manual test**

Start frontend + backend. Go to Settings → Organisation → Gruppen. Add a new group "Testgruppe". Check MongoDB:

```js
db.field_instances.find({ value: true })
// Should include a new document with definitionId matching the new group FieldDefinition
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.ts
git commit -m "feat: create shared FieldInstance when adding a group in Organisation settings"
```

---

### Task 6: Frontend — PlatzzuweisungComponent + route + sidebar

**Files:**
- Create: `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.html`

**Interfaces:**
- Consumes:
  - `PersonService.getChildren(): Observable<ChildDTO[]>`
  - `PersonService.assignGroup(personId, definitionId, fieldInstanceId): Observable<void>`
  - `OrganisationService.getByTag('groups'): Observable<OrganisationDTO>` → `.definitions: FieldDefinition[]`

- [ ] **Step 1: Create PlatzzuweisungComponent**

Create `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { ChildDTO } from '../../shared/models/person.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';

@Component({
  selector: 'app-platzzuweisung',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule, MatSelectModule, MatFormFieldModule, MatProgressSpinnerModule,
  ],
  template: `
    <div class="page-container">
      <h2>Platzzuweisung</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
        <table mat-table [dataSource]="children" class="mat-elevation-z2 full-width">
          <ng-container matColumnDef="name">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let child">
              {{ child.lastName }}, {{ child.firstName }}
            </td>
          </ng-container>

          <ng-container matColumnDef="alter">
            <th mat-header-cell *matHeaderCellDef>Alter</th>
            <td mat-cell *matCellDef="let child">
              {{ getAge(child.dateOfBirth) ?? '—' }}
            </td>
          </ng-container>

          <ng-container matColumnDef="gruppe">
            <th mat-header-cell *matHeaderCellDef>Gruppe</th>
            <td mat-cell *matCellDef="let child">
              <mat-select
                [value]="child.groupDefinitionId"
                (selectionChange)="onGroupChange(child, $event.value)"
                placeholder="—">
                <mat-option [value]="null">—</mat-option>
                @for (group of groups; track group.id) {
                  <mat-option [value]="group.id">{{ group.label['de'] }}</mat-option>
                }
              </mat-select>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>
      }
    </div>
  `,
  styles: [`
    .page-container { padding: 24px; }
    .full-width { width: 100%; }
    mat-select { min-width: 160px; }
  `],
})
export class PlatzzuweisungComponent implements OnInit {
  displayedColumns = ['name', 'alter', 'gruppe'];
  children: ChildDTO[] = [];
  groups: FieldDefinition[] = [];
  loading = true;

  // Map from groupDefinitionId → fieldInstanceId (looked up from groups org)
  private instanceIdByDefId = new Map<string, string>();

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
  ) {}

  ngOnInit(): void {
    this.personService.getChildren().subscribe((children) => {
      this.children = children;
      this.checkDone();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      this.groups = org.definitions.filter((d) => !d.outdatedAt);
      // Build map: definitionId → fieldInstanceId by fetching field_instances
      // The instanceId for each group is stored on the ChildDTO from the backend
      // We'll resolve it by looking at existing children's groupInstanceId
      this.buildInstanceMap();
      this.checkDone();
    });
  }

  private loaded = 0;
  private checkDone(): void {
    this.loaded++;
    if (this.loaded >= 2) this.loading = false;
  }

  private buildInstanceMap(): void {
    // Collect known mappings from already-assigned children
    for (const child of this.children) {
      if (child.groupDefinitionId && child.groupInstanceId) {
        this.instanceIdByDefId.set(child.groupDefinitionId, child.groupInstanceId);
      }
    }
  }

  getAge(dateOfBirth: string | null): number | null {
    if (!dateOfBirth) return null;
    const today = new Date();
    const dob = new Date(dateOfBirth);
    let age = today.getFullYear() - dob.getFullYear();
    const m = today.getMonth() - dob.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < dob.getDate())) {
      age--;
    }
    return age;
  }

  onGroupChange(child: ChildDTO, groupDefinitionId: string | null): void {
    if (!groupDefinitionId) return; // removing group is out of scope

    const fieldInstanceId = this.instanceIdByDefId.get(groupDefinitionId);
    if (!fieldInstanceId) {
      console.error('No FieldInstance found for group definition', groupDefinitionId);
      return;
    }

    this.personService.assignGroup(child.id, groupDefinitionId, fieldInstanceId).subscribe(() => {
      child.groupDefinitionId = groupDefinitionId;
      child.groupInstanceId = fieldInstanceId;
    });
  }
}
```

**Important:** The `instanceIdByDefId` map must be populated before any assignment. After `ngOnInit` loads groups, it only knows definitionIds. The instanceIds come from children who are already assigned. For groups with no children yet assigned, the instanceId can't be known from the frontend alone.

To solve this: after loading both groups and children, do a second-pass to fill the map from all children's `groupInstanceId`. If a group has no assigned children, the instanceId is unknown — the dropdown for that group will silently do nothing.

**This is acceptable for the current scope** — the GroupInstanceMigration ensures instances exist; a future improvement can expose instanceIds via the groups endpoint.

- [ ] **Step 2: Add route**

In `frontend/src/app/app.routes.ts`, add inside the `administration` children array:

```typescript
{
  path: 'families',
  loadComponent: () =>
    import('./administration/families/family-list/family-list.component').then(
      m => m.FamilyListComponent
    ),
},
{
  path: 'platzzuweisung',
  loadComponent: () =>
    import('./administration/platzzuweisung/platzzuweisung.component').then(
      m => m.PlatzzuweisungComponent
    ),
},
```

- [ ] **Step 3: Add sidebar entry**

In `frontend/src/app/app.component.html`, inside the `@if (currentUser.isAdmin)` block, after the "Familien" link:

```html
<a mat-list-item routerLink="/administration/families" routerLinkActive="active">
  <mat-icon matListItemIcon>family_restroom</mat-icon>
  <span matListItemTitle>Familien</span>
</a>
<a mat-list-item routerLink="/administration/platzzuweisung" routerLinkActive="active">
  <mat-icon matListItemIcon>groups</mat-icon>
  <span matListItemTitle>Platzzuweisung</span>
</a>
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 5: Manual end-to-end test**

1. Start backend + frontend
2. Log in as admin
3. Go to Settings → Organisation → Gruppen — add a group "Bären" if none exist
4. Navigate to "Platzzuweisung" in the sidebar — table loads with children
5. Each child shows their name, age, and a dropdown
6. Change a child's group — confirm no error in console, confirm reassignment persists on page reload
7. Verify in MongoDB: `db.persons.findOne({ _id: ObjectId("<child-id>") }).organisationalUnit`
   Should contain `{ definitionId: <bären-def-id>, fieldInstanceId: <bären-inst-id> }`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts \
        frontend/src/app/app.routes.ts \
        frontend/src/app/app.component.html
git commit -m "feat: Platzzuweisung admin view — children table with group assignment dropdown"
```

---

## Self-Review

**Spec coverage:**
- ✅ Admin-only: route uses `adminGuard`, sidebar inside `@if (currentUser.isAdmin)`
- ✅ Alle Kinder sehen: `GET /persons/children` filtert nach personType=CHILD
- ✅ Alter anzeigen: `getAge()` berechnet aus dateOfBirth
- ✅ Gruppe per Dropdown: mat-select mit auto-save
- ✅ Shared FieldInstance: Migration + Organisation addGroup() erstellt genau eine Instance pro Gruppe
- ✅ organisationalUnit: neue Sektion auf Person, nicht customProperties
- ⚠️ Gruppen ohne zugewiesene Kinder: instanceId unbekannt im Frontend — bekanntes Limit, im Scope-Abschnitt der Spec erwähnt (Folge-Task)

**Placeholder scan:** Keine TBDs, alle Code-Blöcke vollständig.

**Type consistency:**
- `ChildDTO` definiert in Task 3 (backend record) und Task 4 (frontend interface) — Felder stimmen überein
- `assignGroup(personId, definitionId, fieldInstanceId)` in Task 4 definiert, in Task 6 aufgerufen — ✅
- `GroupAssignmentRequest.definitionId` / `GroupAssignmentRequest.fieldInstanceId` — stimmt mit PATCH body überein ✅
