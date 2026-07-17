# UI-Verbesserungen: Setup-Wizard, Icon Explorer, Custom Properties

**Datum:** 2026-06-16
**Status:** Approved

---

## Überblick

Drei unabhängige UI-Verbesserungen, als ein kombinierter Spec:

1. **Setup-Wizard**: Erster Nutzer wird als Elternteil angelegt (bisher: Kind)
2. **Icon Explorer**: Visueller Icon-Picker statt Freitext-Eingabe in Organisationseinstellungen
3. **Custom Properties**: Edit-Dialog und klarerer Archive-Button in benutzerdefinierten Eigenschaften

---

## Feature 1: Setup-Wizard — Erster Nutzer als Elternteil

### Problem

Der Setup-Wizard legt den ersten Nutzer ohne expliziten `personType` an. Das Backend weist `CHILD` zu, obwohl der erste Nutzer typischerweise ein Elternteil ist.

### Lösung

Der Setup-Wizard erhält einen zweiten Schritt mit dem bestehenden Elternteil-Formular, vorausgefüllt mit Keycloak-Daten.

### Flow

```
Step 1: Login + Familienname (unverändert)
Step 2: Elternteil-Formular (vorausgefüllt aus Keycloak-Claims)
         → firstName  ← given_name
         → lastName   ← family_name
         → email      ← email claim
         → phone, address, Custom Fields (PARENT): leer, manuell ausfüllbar
POST /api/v1/setup (erweiterte Payload inkl. Parent-Felder + personType: PARENT)
```

### Komponenten

| Datei | Änderung |
|-------|----------|
| `setup/setup.component.ts` | MatStepper mit 2 Steps; Keycloak-Claims in Step-2-Formular injizieren |
| `setup/setup.component.html` | `<mat-stepper>` mit Step 1 (Familienname) und Step 2 (Elternteil-Formular) |
| `administration/families/family-wizard/steps/parents-step.component` | Als eingebettete Komponente in Step 2 verwenden; Input für Vorausfüllung |
| Backend `SetupResource` | `personType` auf `PARENT` setzen; erweiterte Felder empfangen |

### Vorausfüllung

`setup.component.ts` liest nach dem Keycloak-Login die Claims via `AuthService` aus und übergibt sie als `@Input()` an die eingebettete Elternteil-Komponente. Felder sind editierbar — der Nutzer kann Keycloak-Daten korrigieren.

---

## Feature 2: Icon Explorer

### Problem

In den Organisationseinstellungen (Dienst-Einstellungen) muss der Nutzer Material-Icon-Namen als Freitext eingeben (z.B. `restaurant`). Keine Vorschau, kein Discovery.

### Lösung

Button neben dem bestehenden Textfeld öffnet einen `IconPickerDialogComponent` (MatDialog) mit Suchfeld und Virtual-Scroll-Grid aller Material Icons.

### Komponenten

| Datei | Änderung |
|-------|----------|
| `shared/components/icon-picker/icon-picker-dialog.component.ts` | Neu — MatDialog mit Suchfeld + CdkVirtualScrollViewport |
| `shared/components/icon-picker/icon-picker-dialog.component.html` | Grid: `<mat-icon>` + Name je Zelle |
| `shared/components/icon-picker/material-icons.const.ts` | Neu — statische Liste aller ~2000 Material Icon Namen (TS-Konstante, kein HTTP) |
| `settings/organisation/organisation.component.html` | Button `<mat-icon>image_search</mat-icon>` neben dem Icon-Input |
| `settings/organisation/organisation.component.ts` | `openIconPicker()` — öffnet Dialog, setzt FormControl-Wert bei Rückgabe |

### Icon-Picker UX

- **Suchfeld**: Substring-Suche auf Icon-Namen, debounce 200ms, live gefiltert
- **Grid**: Virtual Scroll (CDK), ca. 5–6 Spalten, jede Zelle ~80px: Icon + Name
- **Auswahl**: Klick auf Icon → `dialogRef.close(iconName)` → `formControl.setValue(iconName)`
- **Vorschau**: Das bestehende `<mat-icon>` neben dem Input bleibt und zeigt den gewählten Icon live

### Icon-Liste

Statische TS-Konstante, einmalig beim Build eingebunden. Keine Laufzeit-Abhängigkeit. Quelle: offizielle Material Icons Namensliste.

---

## Feature 3: Benutzerdefinierte Eigenschaften — Edit & Delete

### Problem

Die Tabelle in `custom-fields.component` hat nur einen Archive-Button (soft delete). Es fehlt eine Möglichkeit, bestehende Felder zu bearbeiten. Der Archive-Button ist mit einem `archive`-Icon nicht intuitiv als "Löschen" erkennbar.

### Lösung

- Edit-Button in der Actions-Spalte öffnet den bestehenden Erstell-Dialog vorausgefüllt
- Archive-Button bleibt als einzige Delete-Option (soft delete), erhält klareres `delete`-Icon

### Komponenten

| Datei | Änderung |
|-------|----------|
| `settings/custom-fields/custom-fields.component.ts` | `openEditDialog(field)` — öffnet Dialog mit `MAT_DIALOG_DATA: { field }` |
| `settings/custom-fields/custom-fields.component.html` | Edit-Button (Icon: `edit`) in Actions-Spalte vor Archive-Button |
| `settings/custom-fields/custom-fields-dialog.component.ts` | Empfängt optionale `data.field` via `MAT_DIALOG_DATA`; füllt Formular vor; ruft `update()` statt `create()` auf; wechselt Titel |
| `settings/custom-fields/custom-fields-dialog.component.html` | Dynamischer Titel: "Feld bearbeiten" / "Feld hinzufügen" |
| `settings/custom-fields/services/field-definition.service.ts` | `update(id, dto)` existiert bereits — kein neuer Service-Code |

### Edit-Dialog-Verhalten

- Alle Felder vorausgefüllt: `fieldName`, `labelDe`, `labelEn`, `description`, `schemaType`, `required`, `options`, `keycloakMapping`
- `fieldName` ist im Edit-Modus read-only (disabled) — der technische Bezeichner darf nach Erstellung nicht geändert werden, da externe Referenzen (Keycloak-Mapping, API-Consumers) darauf basieren können
- Submit → `PUT /field-definitions/{id}` → Tabelle neu laden

### Archive (Delete)

- Icon-Änderung: `archive` → `delete` für bessere Erkennbarkeit
- Funktion bleibt `outdateField()` (soft delete via PATCH `/field-definitions/{id}/outdate`)
- Tooltip bleibt "Als veraltet markieren"

---

## Nicht im Scope

- Hard-Delete für Custom Properties
- Icon Explorer in anderen Komponenten (nur Organisationseinstellungen)
- Änderung des Setup-Flows für bereits existierende Instanzen
