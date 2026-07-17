---
name: section
description: Use when creating a new section (Sektion) in the kigruapp GUI. A section is a navigation entry for parents displaying data from existing Person fields, optionally with an admin area for managing FieldDefinitions. Triggers on "create a section", "neue Sektion", "Sektion erstellen", "add a section".
---

# Section

A **Section** is a navigation entry in the sidebar that displays data from **existing** Person fields. The Person data model stays unchanged.

## Workflow

When the user asks to create a section, follow this interactive flow:

### Step 1: Clarify the Section

Ask the user:
1. **Name und Zweck** — Wie soll die Sektion heissen und was zeigt sie an?
2. **Welches Person-Feld** — Welches bestehende Feld wird dargestellt? (`basicProperties`, `roles`, `schedules`, `duties`, `finance`, `customProperties`)
3. **Zielgruppe** — Nur Eltern, oder auch Admins sichtbar?

### Step 2: Ask About Admin Area

Ask: **"Braucht diese Sektion einen Verwaltungsbereich für Admins?"**

If **yes**, gather details:
- **Welche FieldDefinitions** sollen verwaltbar sein? Bring eigene Vorschlaege basierend auf dem Sektionskontext ein (z.B. Sektion "Gesundheit" → Allergien, Medikamente, Notfallkontakt, Blutgruppe)
- Die Admin-Seite bekommt **immer** CRUD-Funktionalitaet: Erstellen, Bearbeiten, Loeschen von FieldDefinitions
- Klaere ob bestehende FieldDefinitions aus dem Seed wiederverwendet werden oder neue noetig sind

### Step 3: Build

#### A. Navigation Entry (always)

1. **`app.routes.ts`** — neue Route unter passendem Pfad hinzufuegen
2. **`app.component.html`** — neuen `mat-list-item` Link in der Sidebar einfuegen (mit passendem `mat-icon`)
3. **Section Component** — Standalone Angular Component erstellen:
   - Laedt Personen-Daten via `PersonService`
   - Zeigt relevante Felder via `SectionFormComponent` oder eigenem Template
   - Filtert/zeigt nur die zum Sektionszweck passenden FieldInstanceDTOs

Pattern fuer die Sidebar (reference: `app.component.html`):
```html
<a mat-list-item routerLink="/sectionpath" routerLinkActive="active">
  <mat-icon matListItemIcon>icon_name</mat-icon>
  <span matListItemTitle>Sektionsname</span>
</a>
```

Pattern fuer die Route (reference: `app.routes.ts`):
```typescript
{
  path: 'sectionpath',
  loadComponent: () =>
    import('./path/to/section.component').then(m => m.SectionComponent),
}
```

#### B. Admin Area (optional)

Wenn ein Verwaltungsbereich benoetigt wird:

4. **Admin Component** — Standalone Component mit:
   - `MatTableDataSource<FieldDefinition>` — Tabelle aller relevanten Definitions
   - **Erstellen** — Inline-Formular oder Dialog fuer neue FieldDefinition
   - **Bearbeiten** — Edit-Dialog oder Inline-Edit fuer bestehende FieldDefinition
   - **Loeschen** — Soft-Delete via `outdate()` mit Bestaetigung
   - Nutzt `FieldDefinitionService` fuer alle API-Aufrufe

5. **Route + Navigation** — Admin-Bereich unter `/settings/` einfuegen mit eigenem Sidebar-Link (nach dem Divider bei den anderen Settings-Links)

6. **Seed Migration** (optional) — Standard-FieldDefinitions in `FieldDefinitionSeedMigration.java` hinzufuegen falls sinnvoll

Pattern fuer Admin CRUD (reference: `custom-fields.component.ts`):
```typescript
@Component({
  standalone: true,
  imports: [CommonModule, MatTableModule, MatButtonModule, MatIconModule,
            MatFormFieldModule, MatInputModule, ReactiveFormsModule, ...],
  template: `<!-- table + form -->`
})
export class SectionAdminComponent implements OnInit {
  displayedColumns = ['fieldName', 'label', 'type', 'required', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit() { this.load(); }

  load() {
    this.fieldDefService.list().subscribe(defs => {
      this.dataSource.data = defs.filter(d => /* section-relevant filter */);
    });
  }

  create(def: Partial<FieldDefinition>) { /* POST + reload */ }
  update(def: FieldDefinition) { /* PUT + reload */ }
  delete(def: FieldDefinition) { /* outdate + reload */ }
}
```

### Key Files

| Zweck | Datei |
|-------|-------|
| Navigation | `frontend/src/app/app.component.html` |
| Routing | `frontend/src/app/app.routes.ts` |
| Person-Daten | `frontend/src/app/shared/services/person.service.ts` |
| Feld-Admin | `frontend/src/app/settings/custom-fields/custom-fields.component.ts` |
| FieldDef Service | `frontend/src/app/settings/custom-fields/services/field-definition.service.ts` |
| Formular | `frontend/src/app/shared/components/section-form/section-form.component.ts` |
| Seed | `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java` |

### Common Mistakes

- Person-Entity aendern — Sektionen erweitern nur die Darstellung, nicht das Datenmodell
- Admin-Bereich ohne Edit/Delete bauen — jede Admin-Seite braucht volles CRUD
- FieldDefinitions nicht filtern — Admin-Seite muss nur sektionsrelevante Definitions zeigen
- Sidebar-Link ohne `routerLinkActive` — aktiver Zustand fehlt dann in der Navigation
