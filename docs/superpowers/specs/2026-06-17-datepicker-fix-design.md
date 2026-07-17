# Design: Datepicker-Fix für Datumsfelder

**Datum:** 2026-06-17
**Status:** Approved
**Bereich:** Frontend — `JsonSchemaFieldComponent`, `SectionFormComponent`, `app.config.ts`

---

## Problem

Der Angular Material Datepicker öffnet sich nicht, wenn er in `JsonSchemaFieldComponent` (innerhalb von `SectionFormComponent`) gerendert wird — z.B. beim Geburtsdatum im Kind-Erstellen-Dialog.

**Root Cause:** `angular-calendar` registriert in `app.config.ts` seinen eigenen `DateAdapter` (date-fns-basiert) im Root-Injector:

```typescript
CalendarModule.forRoot({ provide: DateAdapter, useFactory: adapterFactory })
```

Angular Material's `MatDatepicker` sucht den Material-`DateAdapter` ebenfalls im Root-Injector. Der `NativeDateAdapter` (aus `MatNativeDateModule`) ist nur im Komponenten-Injector von `JsonSchemaFieldComponent` registriert. Das CDK-Overlay rendert jedoch auf Root-Ebene und kann auf den Komponenten-Injector nicht zugreifen — daher öffnet sich der Picker nie.

**Zweites Problem:** Selbst wenn der Picker öffnen würde, schreibt `MatDatepicker` mit `MatNativeDateModule` ein JavaScript `Date`-Objekt in den `FormControl`. `SectionFormComponent.getValues()` gibt dieses rohe `Date`-Objekt ans Backend weiter — das Backend erwartet aber einen `"YYYY-MM-DD"` String (JSON Schema `format: date`).

---

## Design

### Fix 1 — `provideNativeDateAdapter()` im Root-Injector

**Datei:** `frontend/src/app/app.config.ts`

`provideNativeDateAdapter()` zu den `providers` hinzufügen. Damit steht der Angular Material `NativeDateAdapter` global zur Verfügung — das CDK-Overlay findet ihn beim Öffnen des Datepickers.

```typescript
import { provideNativeDateAdapter } from '@angular/material/core';

providers: [
  // ... bestehende Provider
  provideNativeDateAdapter(),
]
```

**Cleanup:**
`MatNativeDateModule` aus den `imports`-Arrays folgender Komponenten entfernen, da es jetzt global bereitgestellt wird:
- `JsonSchemaFieldComponent`
- `CookingDutyDialogComponent`

### Fix 2 — Datum-Serialisierung in `SectionFormComponent.getValues()`

**Datei:** `frontend/src/app/shared/components/section-form/section-form.component.ts`

`getValues()` muss beim Mapping prüfen, ob der Wert ein `Date`-Objekt ist und das Feld `format: 'date'` hat. Falls ja: zu `"YYYY-MM-DD"` serialisieren.

```typescript
getValues(): SectionInput[] {
  return this.fieldDTOs
    .filter((dto) => !dto.definitionOutdated)
    .map((dto) => {
      let value = this.controls[dto.definitionId]?.value ?? null;
      if (value instanceof Date && dto.jsonSchema?.['format'] === 'date') {
        value = value.toISOString().substring(0, 10);
      }
      return { definitionId: dto.definitionId, value };
    });
}
```

---

## Betroffene Dateien

| Datei | Änderung |
|-------|----------|
| `app/app.config.ts` | `provideNativeDateAdapter()` hinzufügen |
| `shared/components/json-schema-field/json-schema-field.component.ts` | `MatNativeDateModule` aus imports entfernen |
| `app/cooking/cooking-duty-dialog.component.ts` | `MatNativeDateModule` aus imports entfernen |
| `shared/components/section-form/section-form.component.ts` | `getValues()` mit Datum-Serialisierung |

---

## Nicht im Scope

- Kein neuer `'timestamp'` Custom-Field-Typ (separates Feature)
- Keine Änderung am Backend
- Keine Änderung an den JSON Schema Definitionen
