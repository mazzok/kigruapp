# UI-Verbesserungen: Setup, Icon Explorer, Custom Fields

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Erster Setup-Nutzer wird als Elternteil angelegt; visueller Icon-Picker in Organisationseinstellungen; Edit + klarerer Delete in benutzerdefinierten Eigenschaften.

**Architecture:** (1) `ParentsStepComponent` erhält `@Input() keycloakPrefill` und wird in `setup.component` als zweiter MatStepper-Schritt eingebettet; Backend setzt `personType PARENT`. (2) Neuer `IconPickerDialogComponent` (MatDialog, CSS-Grid, Substring-Suche) wird per Button neben dem Icon-Input in `organisation.component` geöffnet. (3) Das inline Add-Formular aus `custom-fields.component` wird in einen neuen `CustomFieldsDialogComponent` (MatDialog) extrahiert, der Create und Edit unterstützt.

**Tech Stack:** Angular 18 standalone, Angular Material (MatStepper, MatDialog, MatTable, MatIcon), RxJS, Quarkus Backend

---

## Datei-Map

**Neue Dateien:**
- `frontend/src/app/shared/components/icon-picker/material-icons.const.ts` — statische Liste aller Material Icon Namen
- `frontend/src/app/shared/components/icon-picker/icon-picker-dialog.component.ts`
- `frontend/src/app/shared/components/icon-picker/icon-picker-dialog.component.html`
- `frontend/src/app/settings/custom-fields/custom-fields-dialog.component.ts`
- `frontend/src/app/settings/custom-fields/custom-fields-dialog.component.html`

**Geänderte Dateien:**
- `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts` — `@Input() keycloakPrefill` hinzufügen
- `frontend/src/app/setup/setup.component.ts` — MatStepper, ParentsStepComponent, erweiterter API-Payload
- `frontend/src/app/setup/setup.component.html` — 2-Step-Stepper
- `frontend/src/app/settings/organisation/organisation.component.ts` — `openIconPicker()`
- `frontend/src/app/settings/organisation/organisation.component.html` — Picker-Button neben Icon-Input
- `frontend/src/app/settings/custom-fields/custom-fields.component.ts` — Dialog für Create + Edit
- `frontend/src/app/settings/custom-fields/custom-fields.component.html` — Edit-Button, Delete-Icon
- Backend `SetupResource` + Setup-DTO — `personType PARENT`, `parentProperties[]`

---

## Task 1: Material Icons Konstante erzeugen

**Files:**
- Create: `frontend/src/app/shared/components/icon-picker/material-icons.const.ts`

- [ ] **Step 1: Verzeichnis anlegen**

```bash
mkdir -p frontend/src/app/shared/components/icon-picker
```

- [ ] **Step 2: Icon-Liste von Google Fonts abrufen und Konstante erzeugen**

Führe im `frontend/`-Verzeichnis aus:

```bash
node -e "
const https = require('https');
https.get('https://fonts.google.com/metadata/icons?key=material_symbols_outlined&incomplete=true', (res) => {
  let data = '';
  res.on('data', d => data += d);
  res.on('end', () => {
    const json = JSON.parse(data.replace(\")]}'\n\", ''));
    const names = json.icons.map(i => i.name).sort();
    const ts = 'export const MATERIAL_ICONS: readonly string[] = [\n' + names.map(n => \"  '\" + n + \"'\").join(',\n') + '\n];\n';
    require('fs').writeFileSync('src/app/shared/components/icon-picker/material-icons.const.ts', ts);
    console.log('Generated ' + names.length + ' icons');
  });
}).on('error', e => { console.error('Fetch failed:', e.message); process.exit(1); });
"
```

Expected output: `Generated 2xxx icons`

- [ ] **Step 3: Falls Step 2 fehlschlägt — Datei manuell erstellen**

Erstelle `frontend/src/app/shared/components/icon-picker/material-icons.const.ts` mit einer Teilmenge; der Build funktioniert mit jeder Größe:

```typescript
export const MATERIAL_ICONS: readonly string[] = [
  'access_alarm', 'account_circle', 'add', 'add_circle', 'agriculture',
  'archive', 'arrow_back', 'arrow_forward', 'article', 'assignment',
  'baby_changing_station', 'backpack', 'bakery_dining', 'breakfast_dining',
  'brunch_dining', 'cake', 'calendar_today', 'cancel', 'check', 'check_circle',
  'child_care', 'child_friendly', 'close', 'coffee', 'cookie',
  'dashboard', 'delete', 'dinner_dining', 'edit', 'egg',
  'emoji_food_beverage', 'emoji_people', 'error', 'event', 'face',
  'family_restroom', 'fastfood', 'favorite', 'filter_list', 'flag',
  'food_bank', 'group', 'help', 'home', 'icecream',
  'image', 'image_search', 'info', 'kitchen', 'label',
  'list', 'local_bar', 'local_cafe', 'local_dining', 'local_pizza',
  'lunch_dining', 'manage_accounts', 'menu', 'menu_book', 'more_vert',
  'notifications', 'outdoor_grill', 'people', 'person', 'person_add',
  'phone', 'pie_chart', 'place', 'public', 'ramen_dining',
  'receipt', 'refresh', 'restaurant', 'restaurant_menu', 'rice_bowl',
  'save', 'schedule', 'school', 'search', 'security',
  'send', 'set_meal', 'settings', 'share', 'shopping_cart',
  'spa', 'sports', 'star', 'store', 'table_restaurant',
  'takeout_dining', 'task', 'thumb_up', 'timer', 'today',
  'translate', 'tune', 'verified', 'visibility', 'volunteer_activism',
  'warning', 'water', 'wb_sunny', 'work',
];
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/shared/components/icon-picker/material-icons.const.ts
git commit -m "feat: add Material Icons constant for icon picker"
```

---

## Task 2: IconPickerDialogComponent erstellen

**Files:**
- Create: `frontend/src/app/shared/components/icon-picker/icon-picker-dialog.component.ts`
- Create: `frontend/src/app/shared/components/icon-picker/icon-picker-dialog.component.html`

- [ ] **Step 1: icon-picker-dialog.component.ts erstellen**

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MATERIAL_ICONS } from './material-icons.const';

@Component({
  selector: 'app-icon-picker-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatDialogModule, MatFormFieldModule,
    MatInputModule, MatIconModule, MatButtonModule,
  ],
  templateUrl: './icon-picker-dialog.component.html',
})
export class IconPickerDialogComponent implements OnInit {
  searchTerm = '';
  filteredIcons: readonly string[] = MATERIAL_ICONS;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private dialogRef: MatDialogRef<IconPickerDialogComponent>) {}

  ngOnInit(): void {
    this.filteredIcons = MATERIAL_ICONS;
  }

  onSearch(term: string): void {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => {
      const lower = term.toLowerCase().trim();
      this.filteredIcons = lower
        ? MATERIAL_ICONS.filter(name => name.includes(lower))
        : MATERIAL_ICONS;
    }, 200);
  }

  select(iconName: string): void {
    this.dialogRef.close(iconName);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 2: icon-picker-dialog.component.html erstellen**

```html
<h2 mat-dialog-title>Icon auswählen</h2>

<mat-dialog-content style="width: 560px; height: 500px; display: flex; flex-direction: column; overflow: hidden;">

  <mat-form-field appearance="outline" style="width: 100%; flex-shrink: 0;">
    <mat-label>Icons durchsuchen</mat-label>
    <input matInput [(ngModel)]="searchTerm" (ngModelChange)="onSearch($event)"
           placeholder="z.B. restaurant, person, star" autofocus>
    <mat-icon matSuffix>search</mat-icon>
  </mat-form-field>

  <p style="font-size: 12px; color: #888; margin: 0 0 8px; flex-shrink: 0;">
    {{ filteredIcons.length }} Icons
  </p>

  <div style="overflow-y: auto; flex: 1;">
    <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(80px, 1fr)); gap: 4px;">
      @for (icon of filteredIcons; track icon) {
        <button mat-button (click)="select(icon)" [title]="icon"
                style="height: 80px; display: flex; flex-direction: column; align-items: center;
                       justify-content: center; gap: 4px; padding: 4px; min-width: unset;">
          <mat-icon>{{ icon }}</mat-icon>
          <span style="font-size: 9px; overflow: hidden; text-overflow: ellipsis;
                       white-space: nowrap; width: 72px; text-align: center;">{{ icon }}</span>
        </button>
      }
    </div>
  </div>

</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Abbrechen</button>
</mat-dialog-actions>
```

- [ ] **Step 3: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

Expected: keine Kompilierungsfehler.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/shared/components/icon-picker/
git commit -m "feat: add IconPickerDialogComponent with search and CSS grid"
```

---

## Task 3: Icon Picker in Organisationseinstellungen verdrahten

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`

- [ ] **Step 1: MatDialog + IconPickerDialog in organisation.component.ts einbinden**

Aktuelle Imports und Konstruktor aus `organisation.component.ts` (Zeilen 1–48) sind bekannt. Ergänze:

```typescript
// Neue Imports (zu bestehenden hinzufügen):
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { IconPickerDialogComponent } from '../../shared/components/icon-picker/icon-picker-dialog.component';
```

Im `@Component` `imports`-Array ergänzen: `MatDialogModule, IconPickerDialogComponent`

Im Konstruktor `private dialog: MatDialog` hinzufügen.

Neue Methode in der Klasse (nach `deleteFoodProperty`):

```typescript
openIconPicker(): void {
  this.dialog.open(IconPickerDialogComponent, { width: '620px' })
    .afterClosed()
    .subscribe(iconName => {
      if (iconName) {
        this.dutyForm.get('icon')!.setValue(iconName);
      }
    });
}
```

- [ ] **Step 2: organisation.component.html lesen**

```bash
cat frontend/src/app/settings/organisation/organisation.component.html
```

- [ ] **Step 3: Icon-Input in HTML um Picker-Button ergänzen**

Suche den Block mit `formControlName="icon"`. Wrape das `<mat-form-field>` in eine Flex-Row und füge den Button dahinter ein:

```html
<!-- Vorher: -->
<mat-form-field>
  <mat-label>Icon (Material Icon)</mat-label>
  <input matInput formControlName="icon">
</mat-form-field>

<!-- Nachher: -->
<div style="display: flex; align-items: center; gap: 8px;">
  <mat-form-field style="flex: 1;">
    <mat-label>Icon (Material Icon)</mat-label>
    <input matInput formControlName="icon">
  </mat-form-field>
  <button mat-icon-button type="button" (click)="openIconPicker()" title="Icon auswählen">
    <mat-icon>image_search</mat-icon>
  </button>
</div>
```

- [ ] **Step 4: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

- [ ] **Step 5: Manueller Test**
  1. App starten, Einstellungen → Organisationseinstellungen → Dienst-Einstellungen
  2. `image_search`-Button klicken → Dialog öffnet sich
  3. "rest" tippen → Liste filtert live
  4. Icon anklicken → Dialog schließt, Textfeld enthält Icon-Namen

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/organisation/
git commit -m "feat: add icon picker button to Organisationseinstellungen"
```

---

## Task 4: CustomFieldsDialogComponent erstellen (Create + Edit)

**Files:**
- Create: `frontend/src/app/settings/custom-fields/custom-fields-dialog.component.ts`
- Create: `frontend/src/app/settings/custom-fields/custom-fields-dialog.component.html`

Die `buildJsonSchema`-Logik aus `custom-fields.component.ts` (Zeilen 107–122) wird dupliziert — der Dialog ist standalone und braucht sie intern.

- [ ] **Step 1: custom-fields-dialog.component.ts erstellen**

```typescript
import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { FieldDefinition } from '../../shared/models/field-definition.model';

export interface CustomFieldDialogData {
  field?: FieldDefinition;
}

export interface CustomFieldDialogResult {
  fieldName: string;
  labelDe: string;
  labelEn: string;
  description: string;
  schemaType: SchemaType;
  options: string;
  required: boolean;
  keycloakMapping: string;
}

type SchemaType = 'text' | 'number' | 'date' | 'boolean' | 'select';

@Component({
  selector: 'app-custom-fields-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatButtonModule,
  ],
  templateUrl: './custom-fields-dialog.component.html',
})
export class CustomFieldsDialogComponent implements OnInit {
  form!: FormGroup;
  isEditMode: boolean;

  constructor(
    private dialogRef: MatDialogRef<CustomFieldsDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CustomFieldDialogData,
  ) {
    this.isEditMode = !!data?.field;
  }

  ngOnInit(): void {
    const f = this.data?.field;
    this.form = new FormGroup({
      fieldName: new FormControl(
        { value: f?.fieldName ?? '', disabled: this.isEditMode },
        Validators.required,
      ),
      labelDe: new FormControl(f?.label?.['de'] ?? '', Validators.required),
      labelEn: new FormControl(f?.label?.['en'] ?? '', Validators.required),
      description: new FormControl(f?.description ?? ''),
      schemaType: new FormControl<SchemaType>(this.detectSchemaType(f), Validators.required),
      options: new FormControl(this.detectOptions(f)),
      required: new FormControl(f?.required ?? false),
      keycloakMapping: new FormControl(f?.keycloakMapping ?? ''),
    });
  }

  private detectSchemaType(f?: FieldDefinition): SchemaType {
    if (!f?.jsonSchema) return 'text';
    const s = f.jsonSchema;
    if (s['type'] === 'boolean') return 'boolean';
    if (s['type'] === 'number' || s['type'] === 'integer') return 'number';
    if (s['enum']) return 'select';
    if (s['format'] === 'date') return 'date';
    return 'text';
  }

  private detectOptions(f?: FieldDefinition): string {
    const enumVals = f?.jsonSchema?.['enum'] as string[] | undefined;
    return enumVals ? enumVals.join(', ') : '';
  }

  get isSelectType(): boolean {
    return this.form.get('schemaType')?.value === 'select';
  }

  submit(): void {
    if (this.form.invalid) return;
    this.dialogRef.close(this.form.getRawValue() as CustomFieldDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 2: custom-fields-dialog.component.html erstellen**

```html
<h2 mat-dialog-title>{{ isEditMode ? 'Feld bearbeiten' : 'Neues Feld hinzufügen' }}</h2>

<mat-dialog-content style="min-width: 480px;">
  <form [formGroup]="form" style="display: flex; flex-direction: column; gap: 8px; padding-top: 8px;">

    <mat-form-field appearance="outline">
      <mat-label>Feldname (technisch)</mat-label>
      <input matInput formControlName="fieldName" placeholder="z.B. allergies">
      @if (isEditMode) {
        <mat-hint>Technischer Name kann nicht geändert werden</mat-hint>
      }
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Bezeichnung (Deutsch)</mat-label>
      <input matInput formControlName="labelDe" placeholder="z.B. Allergien">
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Bezeichnung (Englisch)</mat-label>
      <input matInput formControlName="labelEn" placeholder="z.B. Allergies">
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Beschreibung</mat-label>
      <textarea matInput formControlName="description" rows="2"></textarea>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Feldtyp</mat-label>
      <mat-select formControlName="schemaType">
        <mat-option value="text">Text</mat-option>
        <mat-option value="number">Zahl</mat-option>
        <mat-option value="date">Datum</mat-option>
        <mat-option value="boolean">Ja/Nein</mat-option>
        <mat-option value="select">Auswahlliste</mat-option>
      </mat-select>
    </mat-form-field>

    @if (isSelectType) {
      <mat-form-field appearance="outline">
        <mat-label>Optionen (kommagetrennt)</mat-label>
        <input matInput formControlName="options" placeholder="Option A, Option B, Option C">
      </mat-form-field>
    }

    <mat-form-field appearance="outline">
      <mat-label>Keycloak-Attribut (optional)</mat-label>
      <input matInput formControlName="keycloakMapping" placeholder="z.B. given_name">
    </mat-form-field>

    <mat-checkbox formControlName="required">Pflichtfeld</mat-checkbox>

  </form>
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Abbrechen</button>
  <button mat-raised-button color="primary" (click)="submit()" [disabled]="form.invalid">
    {{ isEditMode ? 'Speichern' : 'Hinzufügen' }}
  </button>
</mat-dialog-actions>
```

- [ ] **Step 3: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/settings/custom-fields/custom-fields-dialog.component.*
git commit -m "feat: add CustomFieldsDialogComponent for create and edit"
```

---

## Task 5: Custom Fields Tabelle — Dialog verdrahten, Edit + Delete-Icon

**Files:**
- Modify: `frontend/src/app/settings/custom-fields/custom-fields.component.ts`
- Modify: `frontend/src/app/settings/custom-fields/custom-fields.component.html`

- [ ] **Step 1: custom-fields.component.ts ersetzen**

Der bestehende Code ist bekannt (Zeilen 1–123). Ersetze die gesamte Datei:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FieldDefinitionService } from './services/field-definition.service';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import {
  CustomFieldsDialogComponent,
  CustomFieldDialogData,
  CustomFieldDialogResult,
} from './custom-fields-dialog.component';

type SchemaType = 'text' | 'number' | 'date' | 'boolean' | 'select';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule, MatButtonModule, MatIconModule, MatDialogModule,
    CustomFieldsDialogComponent,
  ],
  templateUrl: './custom-fields.component.html',
  styleUrl: './custom-fields.component.scss',
})
export class CustomFieldsComponent implements OnInit {
  displayedColumns = ['fieldName', 'labelDe', 'description', 'schemaType', 'required', 'status', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  constructor(
    private fieldDefService: FieldDefinitionService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.fieldDefService.list().subscribe(defs => {
      this.dataSource.data = defs;
    });
  }

  openAddDialog(): void {
    this.dialog.open(CustomFieldsDialogComponent, {
      data: {} as CustomFieldDialogData,
    }).afterClosed().subscribe((result: CustomFieldDialogResult | undefined) => {
      if (result) {
        this.fieldDefService.create(this.buildFieldDef(result)).subscribe(() => this.loadData());
      }
    });
  }

  openEditDialog(field: FieldDefinition): void {
    this.dialog.open(CustomFieldsDialogComponent, {
      data: { field } as CustomFieldDialogData,
    }).afterClosed().subscribe((result: CustomFieldDialogResult | undefined) => {
      if (result) {
        this.fieldDefService.update(field.id!, this.buildFieldDef(result)).subscribe(() => this.loadData());
      }
    });
  }

  outdateField(id: string): void {
    this.fieldDefService.outdate(id).subscribe(() => this.loadData());
  }

  getSchemaTypeLabel(def: FieldDefinition): string {
    const schema = def.jsonSchema;
    if (!schema) return '?';
    const type = schema['type'] as string;
    if (type === 'boolean') return 'Ja/Nein';
    if (type === 'number' || type === 'integer') return 'Zahl';
    if (type === 'string') {
      if (schema['enum']) return 'Auswahl';
      if (schema['format'] === 'date') return 'Datum';
      return 'Text';
    }
    return type || '?';
  }

  isOutdated(def: FieldDefinition): boolean {
    return !!def.outdatedAt;
  }

  private buildFieldDef(result: CustomFieldDialogResult): FieldDefinition {
    return {
      fieldName: result.fieldName,
      label: { de: result.labelDe, en: result.labelEn },
      description: result.description || undefined,
      jsonSchema: this.buildJsonSchema(result.schemaType, result.options),
      required: result.required,
      keycloakMapping: result.keycloakMapping || null,
    };
  }

  private buildJsonSchema(schemaType: SchemaType, optionsStr: string): Record<string, unknown> {
    switch (schemaType) {
      case 'text':    return { type: 'string' };
      case 'number':  return { type: 'number' };
      case 'date':    return { type: 'string', format: 'date' };
      case 'boolean': return { type: 'boolean' };
      case 'select': {
        const options = optionsStr.split(',').map(o => o.trim()).filter(o => o);
        return { type: 'string', enum: options };
      }
    }
  }
}
```

- [ ] **Step 2: custom-fields.component.html lesen**

```bash
cat frontend/src/app/settings/custom-fields/custom-fields.component.html
```

- [ ] **Step 3: custom-fields.component.html aktualisieren**

a) Das inline Add-Formular (`<form [formGroup]="form">` Block) durch einen Button ersetzen:

```html
<div style="margin-bottom: 16px;">
  <button mat-raised-button color="primary" (click)="openAddDialog()">
    <mat-icon>add</mat-icon> Feld hinzufügen
  </button>
</div>
```

b) In der `matColumnDef="actions"`-Spalte: Edit-Button hinzufügen, `archive`-Icon zu `delete` ändern:

```html
<ng-container matColumnDef="actions">
  <th mat-header-cell *matHeaderCellDef></th>
  <td mat-cell *matCellDef="let row">
    @if (!isOutdated(row)) {
      <button mat-icon-button (click)="openEditDialog(row)" title="Bearbeiten">
        <mat-icon>edit</mat-icon>
      </button>
      <button mat-icon-button color="warn" (click)="outdateField(row.id)" title="Als veraltet markieren">
        <mat-icon>delete</mat-icon>
      </button>
    }
  </td>
</ng-container>
```

- [ ] **Step 4: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

- [ ] **Step 5: Manueller Test**
  1. Einstellungen → Benutzerdefinierte Eigenschaften
  2. "Feld hinzufügen" → Dialog öffnet sich mit leerem Formular
  3. Felder ausfüllen → "Hinzufügen" → Feld erscheint in Tabelle
  4. Stift-Button klicken → Dialog öffnet sich vorausgefüllt; `fieldName` ist grau/deaktiviert
  5. Felder ändern → "Speichern" → Tabelle aktualisiert sich
  6. Delete-Button → Feld wechselt auf "Veraltet"

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/custom-fields/
git commit -m "feat: replace inline form with dialog, add edit button, update delete icon"
```

---

## Task 6: Backend — Setup API: personType PARENT + optionale Parent-Felder

**Files:**
- Backend SetupResource + zugehöriges DTO/Request-Klasse

- [ ] **Step 1: Backend Setup-Dateien lokalisieren**

```bash
find backend/src -type f -name "*.java" | xargs grep -l "setup\|Setup" | head -10
```

Expected: Zeigt SetupResource.java und ggf. SetupRequest.java / SetupDto.java

- [ ] **Step 2: SetupResource und DTO lesen**

```bash
cat <Pfad>/SetupResource.java
cat <Pfad>/SetupRequest.java   # oder wie die DTO-Klasse heißt
```

- [ ] **Step 3: DTO um optionale Felder erweitern**

Füge in der Setup-Request-Klasse optionale Felder hinzu (falls noch nicht vorhanden):

```java
// In SetupRequest.java (Quarkus/Jackson — public fields oder Getter/Setter je nach Pattern):
public String phone;
public String street;
public String zip;
public String city;
```

- [ ] **Step 4: SetupResource — personType auf PARENT setzen**

Finde die Stelle, an der die erste Person angelegt wird. Setze `personType` auf `"PARENT"`:

```java
// Vorher (Beispiel — je nach Code-Struktur):
person.personType = "CHILD";   // oder: setPersonType("CHILD")
// oder: es fehlt ganz (default = CHILD)

// Nachher:
person.personType = "PARENT";  // oder: setPersonType("PARENT")
```

- [ ] **Step 5: SetupResource — optionale Kontaktfelder übernehmen**

Nach der Stelle wo firstName/lastName/email gesetzt werden, ergänze:

```java
if (request.phone != null && !request.phone.isBlank()) {
    person.phone = request.phone;   // Passe Feldname an Person-Entität an
}
if (request.street != null && !request.street.isBlank()) {
    person.street = request.street;
}
if (request.zip != null && !request.zip.isBlank()) {
    person.zip = request.zip;
}
if (request.city != null && !request.city.isBlank()) {
    person.city = request.city;
}
```

Falls die Person-Entität keine direkten Adressfelder hat (sondern properties/field-values), folge dem Muster der Family-Wizard-Implementierung im Backend.

- [ ] **Step 6: Backend Build-Check**

```bash
cd backend && ./mvnw compile -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add backend/src/
git commit -m "feat: setup creates first user as PARENT with optional contact fields"
```

---

## Task 7: ParentsStepComponent — keycloakPrefill Input hinzufügen

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts`

Der aktuelle Code ist bekannt (Zeilen 1–95). `prefill()` (Zeile 72) setzt nur `lastName` und `address` via `SectionFormComponent.setValueByFieldName()`. Wir fügen einen `@Input()` hinzu der auch `firstName` und `email` setzt.

Das Problem: `parentForms` (ViewChildren) ist leer bis `definitions` geladen sind und die `@for`-Schleife `<app-section-form>` gerendert hat. Die Prefill-Logik muss deshalb *nach* dem definitions-Subscribe laufen (mit `setTimeout(0)` um einen Render-Tick abzuwarten).

- [ ] **Step 1: parents-step.component.ts aktualisieren**

Ersetze die gesamte Datei (alle bestehenden Methoden bleiben erhalten, `@Input` und `applyKeycloakPrefill` werden ergänzt):

```typescript
import { Component, Input, OnInit, QueryList, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { SectionInput } from '../../../../shared/models/person.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-parents-step',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    SectionFormComponent,
  ],
  template: `
    <h3>Elternteile</h3>
    @for (idx of parentIndices; track idx) {
      <div class="parent-block">
        <h4>
          Elternteil {{ idx + 1 }}
          @if (idx > 0) {
            <button mat-icon-button (click)="removeParent(idx)">
              <mat-icon>delete</mat-icon>
            </button>
          }
        </h4>
        @if (definitions.length > 0) {
          <app-section-form
            #parentForm
            [definitions]="definitions"
          ></app-section-form>
        }
      </div>
    }
    <button mat-stroked-button (click)="addParent()">
      <mat-icon>add</mat-icon> Elternteil hinzufuegen
    </button>
  `,
  styles: [`.parent-block { margin-bottom: 24px; border-bottom: 1px solid #ccc; padding-bottom: 16px; }`],
})
export class ParentsStepComponent implements OnInit {
  private static readonly ALLOWED_FIELDS = ['firstName', 'lastName', 'email', 'phone', 'dateOfBirth', 'address'];

  @Input() keycloakPrefill: { firstName: string; lastName: string; email: string } | null = null;

  @ViewChildren('parentForm') parentForms!: QueryList<SectionFormComponent>;

  definitions: FieldDefinition[] = [];
  private personTypeDef?: FieldDefinition;
  parentIndices: number[] = [0];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
      this.definitions = defs.filter((d) => ParentsStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
      if (this.keycloakPrefill) {
        // Wait one tick for @for to render SectionFormComponents
        setTimeout(() => this.applyKeycloakPrefill(), 0);
      }
    });
  }

  private applyKeycloakPrefill(): void {
    const first = this.parentForms?.first;
    if (!first || !this.keycloakPrefill) return;
    first.setValueByFieldName('firstName', this.keycloakPrefill.firstName);
    first.setValueByFieldName('lastName', this.keycloakPrefill.lastName);
    first.setValueByFieldName('email', this.keycloakPrefill.email);
  }

  addParent(): void {
    this.parentIndices.push(this.parentIndices.length);
  }

  removeParent(index: number): void {
    this.parentIndices.splice(index, 1);
    this.parentIndices = this.parentIndices.map((_, i) => i);
  }

  prefill(lastName: string, address?: { street: string; zip: string; city: string } | null): void {
    this.parentForms?.forEach((f) => {
      f.setValueByFieldName('lastName', lastName);
      if (address) {
        f.setValueByFieldName('address', address);
      }
    });
  }

  get isValid(): boolean {
    return this.parentForms?.length > 0 &&
      this.parentForms.toArray().every((f) => f.isValid);
  }

  getParentsBasicProperties(): SectionInput[][] {
    return this.parentForms.toArray().map((f) => {
      const values = f.getValues();
      if (this.personTypeDef?.id) {
        values.push({ definitionId: this.personTypeDef.id, value: 'PARENT' });
      }
      return values;
    });
  }
}
```

- [ ] **Step 2: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts
git commit -m "feat: add keycloakPrefill input to ParentsStepComponent"
```

---

## Task 8: Setup Component — 2-Step-Stepper mit Elternteil-Formular

**Files:**
- Modify: `frontend/src/app/setup/setup.component.ts`
- Modify: `frontend/src/app/setup/setup.component.html`

Der aktuelle Code ist bekannt (Zeilen 1–84). Die Keycloak-Claims werden via `auth.oauthService.getIdentityClaims()` abgerufen.

- [ ] **Step 1: setup.component.ts ersetzen**

```typescript
import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatStepperModule } from '@angular/material/stepper';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../core/services/auth.service';
import { ParentsStepComponent } from '../administration/families/family-wizard/steps/parents-step.component';

@Component({
  selector: 'app-setup',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule,
    MatFormFieldModule, MatInputModule,
    MatProgressSpinnerModule, MatStepperModule, MatIconModule,
    ParentsStepComponent,
  ],
  templateUrl: './setup.component.html',
  styleUrl: './setup.component.scss',
})
export class SetupComponent implements OnInit {
  @ViewChild(ParentsStepComponent) parentsStep?: ParentsStepComponent;

  familyName = '';
  loading = false;
  error = '';
  setupComplete = false;

  keycloakPrefill: { firstName: string; lastName: string; email: string } | null = null;

  constructor(
    public auth: AuthService,
    private http: HttpClient,
    private router: Router,
  ) {}

  ngOnInit(): void {
    if (this.auth.isAuthenticated) {
      this.checkAndAutoSetup();
      this.loadKeycloakData();
    }
  }

  private checkAndAutoSetup(): void {
    this.http.get<{ required: boolean }>('/api/v1/setup/status').subscribe(status => {
      if (!status.required) {
        this.router.navigate(['/cooking']);
      }
    });
  }

  private loadKeycloakData(): void {
    const oauthSvc = (this.auth as unknown as {
      oauthService: { getIdentityClaims: () => Record<string, string> | null }
    }).oauthService;
    const claims = oauthSvc?.getIdentityClaims?.() ?? null;
    if (claims) {
      this.keycloakPrefill = {
        firstName: claims['given_name'] ?? '',
        lastName: claims['family_name'] ?? '',
        email: this.auth.userEmail ?? claims['email'] ?? '',
      };
    }
  }

  loginWithKeycloak(): void {
    this.auth.login();
  }

  submitSetup(): void {
    if (!this.familyName.trim()) {
      this.error = 'Bitte gib einen Familiennamen ein.';
      return;
    }
    this.loading = true;
    this.error = '';

    const oauthSvc = (this.auth as unknown as {
      oauthService: { getIdentityClaims: () => Record<string, string> | null }
    }).oauthService;
    const claims = oauthSvc?.getIdentityClaims?.() ?? null;

    const parentProperties = this.parentsStep?.getParentsBasicProperties?.()?.[0] ?? [];

    const body = {
      familyName: this.familyName.trim(),
      keycloakUserId: claims?.['sub'] ?? '',
      email: this.auth.userEmail,
      firstName: claims?.['given_name'] ?? '',
      lastName: claims?.['family_name'] ?? '',
      parentProperties,
    };

    this.http.post('/api/v1/setup', body).subscribe({
      next: () => {
        this.setupComplete = true;
        this.router.navigate(['/cooking']);
      },
      error: err => {
        this.loading = false;
        this.error = (err.error as { error?: string })?.error ?? 'Einrichtung fehlgeschlagen. Bitte neu laden.';
      },
    });
  }
}
```

- [ ] **Step 2: setup.component.html lesen**

```bash
cat frontend/src/app/setup/setup.component.html
```

- [ ] **Step 3: setup.component.html ersetzen**

```html
<mat-card style="max-width: 640px; margin: 48px auto;">
  <mat-card-header>
    <mat-card-title>Willkommen — Ersteinrichtung</mat-card-title>
  </mat-card-header>

  <mat-card-content>

    @if (!auth.isAuthenticated) {
      <p style="margin-top: 16px;">Bitte melden Sie sich an, um die Einrichtung zu starten.</p>
      <button mat-raised-button color="primary" (click)="loginWithKeycloak()">
        Mit Keycloak anmelden
      </button>
    }

    @if (auth.isAuthenticated && !setupComplete) {
      <mat-stepper orientation="vertical" style="margin-top: 8px;">

        <!-- Step 1: Familienname -->
        <mat-step label="Familie">
          <mat-form-field appearance="outline" style="width: 100%; margin-top: 16px;">
            <mat-label>Familienname</mat-label>
            <input matInput [(ngModel)]="familyName" placeholder="z.B. Müller">
          </mat-form-field>
          @if (error) {
            <p style="color: red;">{{ error }}</p>
          }
          <div style="margin-top: 8px;">
            <button mat-raised-button color="primary" [disabled]="!familyName.trim()" matStepperNext>
              Weiter
            </button>
          </div>
        </mat-step>

        <!-- Step 2: Elternteil-Daten -->
        <mat-step label="Ihre Daten">
          <div style="margin-top: 16px;">
            <app-parents-step [keycloakPrefill]="keycloakPrefill"></app-parents-step>
          </div>
          <div style="margin-top: 16px; display: flex; gap: 8px; align-items: center;">
            <button mat-button matStepperPrevious>Zurück</button>
            <button mat-raised-button color="primary" (click)="submitSetup()" [disabled]="loading">
              @if (loading) {
                <mat-progress-spinner diameter="20" mode="indeterminate"></mat-progress-spinner>
              } @else {
                Einrichtung abschließen
              }
            </button>
          </div>
          @if (error) {
            <p style="color: red; margin-top: 8px;">{{ error }}</p>
          }
        </mat-step>

      </mat-stepper>
    }

    @if (setupComplete) {
      <div style="text-align: center; padding: 32px;">
        <mat-icon style="font-size: 48px; width: 48px; height: 48px; color: green;">check_circle</mat-icon>
        <p>Einrichtung abgeschlossen! Sie werden weitergeleitet…</p>
      </div>
    }

  </mat-card-content>
</mat-card>
```

- [ ] **Step 4: Build-Check**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

- [ ] **Step 5: Manueller End-to-End-Test**
  1. `/setup` aufrufen (neue Testinstanz oder DB-Reset)
  2. "Mit Keycloak anmelden" → Step 1 erscheint
  3. Familienname eingeben → "Weiter" → Step 2 erscheint
  4. Elternteil-Formular erscheint; firstName/lastName/email sind vorausgefüllt
  5. "Einrichtung abschließen" → Weiterleitung zu `/cooking`
  6. In der Datenbank / Admin-UI prüfen: erste Person hat `personType = PARENT`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/setup/
git commit -m "feat: setup wizard 2-step stepper creates first user as parent"
```
