# Datepicker-Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Datepicker in `JsonSchemaFieldComponent` öffnet sich korrekt und serialisiert Datum-Werte als `"YYYY-MM-DD"` String ans Backend.

**Architecture:** `provideNativeDateAdapter()` im Root-Injector registrieren, damit das CDK-Overlay den Angular Material DateAdapter findet. `MatNativeDateModule` aus Einzelkomponenten entfernen. `SectionFormComponent.getValues()` serialisiert `Date`-Objekte zu lokalen `"YYYY-MM-DD"` Strings.

**Tech Stack:** Angular 17+, Angular Material (`MatDatepickerModule`, `provideNativeDateAdapter`), Jasmine/Karma

## Global Constraints

- Keine neuen Dependencies hinzufügen — `provideNativeDateAdapter` ist bereits in `@angular/material/core` enthalten
- Serialisierung immer mit lokalen Datums-Komponenten (`getFullYear()`, `getMonth()`, `getDate()`) — NICHT `toISOString()` (UTC-Zeitzonenproblem)
- Kein Backend-Code ändern

---

## Dateiübersicht

| Datei | Aktion |
|-------|--------|
| `frontend/src/app/app.config.ts` | Modify — `provideNativeDateAdapter()` hinzufügen |
| `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts` | Modify — `MatNativeDateModule` entfernen |
| `frontend/src/app/cooking/cooking-duty-dialog.component.ts` | Modify — `MatNativeDateModule` entfernen |
| `frontend/src/app/shared/components/section-form/section-form.component.ts` | Modify — `getValues()` mit Datum-Serialisierung |
| `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.spec.ts` | Create — Datepicker-Rendering-Test |
| `frontend/src/app/shared/components/section-form/section-form.component.spec.ts` | Create — Serialisierungs-Test |

---

### Task 1: Root DateAdapter Provider + Cleanup

**Files:**
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts`
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.ts`
- Create: `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.spec.ts`

**Interfaces:**
- Produces: `provideNativeDateAdapter()` steht global bereit; `JsonSchemaFieldComponent` braucht kein eigenes `MatNativeDateModule` mehr

- [ ] **Step 1: Failing test schreiben**

Datei erstellen: `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.spec.ts`

```typescript
import { TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { JsonSchemaFieldComponent } from './json-schema-field.component';
import { FieldInstanceDTO } from '../../models/field-instance.model';

const DATE_DTO: FieldInstanceDTO = {
  definitionId: 'def-date',
  fieldName: 'dateOfBirth',
  label: { de: 'Geburtsdatum' },
  jsonSchema: { type: 'string', format: 'date' },
  required: false,
  value: null,
  definitionOutdated: false,
};

describe('JsonSchemaFieldComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JsonSchemaFieldComponent],
      providers: [provideNativeDateAdapter(), provideAnimations()],
    }).compileComponents();
  });

  it('rendert mat-datepicker für Feld mit format:date', () => {
    const fixture = TestBed.createComponent(JsonSchemaFieldComponent);
    fixture.componentInstance.dto = DATE_DTO;
    fixture.componentInstance.control = new FormControl(null);
    fixture.detectChanges();

    const picker = fixture.nativeElement.querySelector('mat-datepicker');
    expect(picker).withContext('mat-datepicker soll im DOM vorhanden sein').toBeTruthy();

    const toggle = fixture.nativeElement.querySelector('mat-datepicker-toggle');
    expect(toggle).withContext('mat-datepicker-toggle soll im DOM vorhanden sein').toBeTruthy();
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss FAIL sein**

```bash
cd frontend && npx ng test --include="**/json-schema-field.component.spec.ts" --watch=false
```

Erwartet: FAIL — `NullInjectorError: No provider for DateAdapter` (oder ähnlich, da Provider noch fehlt)

- [ ] **Step 3: `provideNativeDateAdapter()` in `app.config.ts` hinzufügen**

Datei: `frontend/src/app/app.config.ts`

Import hinzufügen (Zeile 5, nach bestehendem `MAT_DATE_LOCALE` Import):
```typescript
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
```

Provider hinzufügen (nach `provideAnimationsAsync()`):
```typescript
provideNativeDateAdapter(),
```

Vollständige `providers`-Liste danach:
```typescript
providers: [
  provideZoneChangeDetection({ eventCoalescing: true }),
  provideRouter(routes),
  provideHttpClient(withInterceptors([authInterceptor])),
  provideAnimationsAsync(),
  provideNativeDateAdapter(),
  { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  { provide: LOCALE_ID, useValue: 'de-AT' },
  provideOAuthClient(),
  importProvidersFrom(
    CalendarModule.forRoot({ provide: DateAdapter, useFactory: adapterFactory })
  ),
  {
    provide: APP_INITIALIZER,
    useFactory: (auth: AuthService) => () => auth.configure(),
    deps: [AuthService],
    multi: true,
  },
],
```

- [ ] **Step 4: `MatNativeDateModule` aus `JsonSchemaFieldComponent` entfernen**

Datei: `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts`

Import-Zeile entfernen:
```typescript
import { MatNativeDateModule } from '@angular/material/core';
```

Aus dem `imports`-Array im `@Component`-Decorator entfernen:
```typescript
// Vorher:
imports: [
  ReactiveFormsModule,
  MatFormFieldModule,
  MatInputModule,
  MatSelectModule,
  MatDatepickerModule,
  MatNativeDateModule,   // <-- diese Zeile entfernen
  MatSlideToggleModule,
  MatCardModule,
],

// Nachher:
imports: [
  ReactiveFormsModule,
  MatFormFieldModule,
  MatInputModule,
  MatSelectModule,
  MatDatepickerModule,
  MatSlideToggleModule,
  MatCardModule,
],
```

- [ ] **Step 5: `MatNativeDateModule` aus `CookingDutyDialogComponent` entfernen**

Datei: `frontend/src/app/cooking/cooking-duty-dialog.component.ts`

Import-Zeile entfernen:
```typescript
import { MatNativeDateModule } from '@angular/material/core';
```

`MatNativeDateModule` aus dem `imports`-Array im `@Component`-Decorator entfernen.

- [ ] **Step 6: Test nochmals laufen lassen — muss PASS sein**

```bash
cd frontend && npx ng test --include="**/json-schema-field.component.spec.ts" --watch=false
```

Erwartet: PASS — beide Expectations grün

- [ ] **Step 7: Manuell verifizieren**

App starten (`ng serve`), zu Kind-Erstellen navigieren, Geburtsdatum-Feld anklicken → Kalender-Popup muss sich öffnen.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/app.config.ts \
        frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts \
        frontend/src/app/cooking/cooking-duty-dialog.component.ts \
        frontend/src/app/shared/components/json-schema-field/json-schema-field.component.spec.ts
git commit -m "fix: provide NativeDateAdapter at root level so datepicker overlay works"
```

---

### Task 2: Datum-Serialisierung in `SectionFormComponent.getValues()`

**Files:**
- Modify: `frontend/src/app/shared/components/section-form/section-form.component.ts`
- Create: `frontend/src/app/shared/components/section-form/section-form.component.spec.ts`

**Interfaces:**
- Consumes: `SectionFormComponent.controls` (Record<string, FormControl>), `SectionFormComponent.fieldDTOs` (FieldInstanceDTO[])
- Produces: `getValues()` gibt `SectionInput[]` zurück wobei `Date`-Werte für `format: 'date'`-Felder als `"YYYY-MM-DD"` String serialisiert sind

- [ ] **Step 1: Failing test schreiben**

Datei erstellen: `frontend/src/app/shared/components/section-form/section-form.component.spec.ts`

```typescript
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { SectionFormComponent } from './section-form.component';
import { FieldDefinition } from '../../models/field-definition.model';

const DATE_DEFINITION: FieldDefinition = {
  id: 'def-dob',
  fieldName: 'dateOfBirth',
  label: { de: 'Geburtsdatum', en: 'Date of Birth' },
  jsonSchema: { type: 'string', format: 'date' },
  required: false,
  outdatedAt: undefined,
  keycloakMapping: null,
} as unknown as FieldDefinition;

describe('SectionFormComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SectionFormComponent],
      providers: [provideNativeDateAdapter(), provideAnimations()],
    }).compileComponents();
  });

  describe('getValues()', () => {
    it('serialisiert Date-Objekte zu YYYY-MM-DD String (Ortszeit)', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      // Datum in Ortszeit: 13. November 2016
      fixture.componentInstance.controls['def-dob'].setValue(new Date(2016, 10, 13)); // Monat 0-basiert

      const values = fixture.componentInstance.getValues();

      expect(values.length).toBe(1);
      expect(values[0].definitionId).toBe('def-dob');
      expect(values[0].value).toBe('2016-11-13');
    });

    it('lässt nicht-Date Werte unverändert', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      fixture.componentInstance.controls['def-dob'].setValue('2016-11-13');

      const values = fixture.componentInstance.getValues();
      expect(values[0].value).toBe('2016-11-13');
    });

    it('lässt null Werte unverändert', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      const values = fixture.componentInstance.getValues();
      expect(values[0].value).toBeNull();
    });
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss FAIL sein**

```bash
cd frontend && npx ng test --include="**/section-form.component.spec.ts" --watch=false
```

Erwartet: FAIL — `expected Date { ... } to be '2016-11-13'`

- [ ] **Step 3: `getValues()` in `SectionFormComponent` anpassen**

Datei: `frontend/src/app/shared/components/section-form/section-form.component.ts`

`getValues()` ersetzen:

```typescript
getValues(): SectionInput[] {
  return this.fieldDTOs
    .filter((dto) => !dto.definitionOutdated)
    .map((dto) => {
      let value: unknown = this.controls[dto.definitionId]?.value ?? null;
      if (value instanceof Date && dto.jsonSchema?.['format'] === 'date') {
        const y = value.getFullYear();
        const m = String(value.getMonth() + 1).padStart(2, '0');
        const d = String(value.getDate()).padStart(2, '0');
        value = `${y}-${m}-${d}`;
      }
      return { definitionId: dto.definitionId, value };
    });
}
```

> **Hinweis Zeitzone:** `getFullYear()` / `getMonth()` / `getDate()` verwenden die lokale Zeitzone des Browsers — das ist korrekt für Datumsfelder. `toISOString()` würde UTC verwenden und bei Usern östlich von UTC könnte das Datum um einen Tag abweichen.

- [ ] **Step 4: Test nochmals laufen lassen — muss PASS sein**

```bash
cd frontend && npx ng test --include="**/section-form.component.spec.ts" --watch=false
```

Erwartet: PASS — alle 3 Tests grün

- [ ] **Step 5: Alle Tests laufen lassen**

```bash
cd frontend && npx ng test --watch=false
```

Erwartet: Kein neuer Fehler eingeführt

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/components/section-form/section-form.component.ts \
        frontend/src/app/shared/components/section-form/section-form.component.spec.ts
git commit -m "fix: serialize Date objects to YYYY-MM-DD string in SectionFormComponent.getValues()"
```
