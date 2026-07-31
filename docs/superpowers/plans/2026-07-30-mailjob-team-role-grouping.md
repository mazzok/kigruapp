# Mailjob-Empfänger: Team-Rollen-Gruppierung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Im Mailjob-Empfänger-Dropdown (`MailJobEditorComponent`) jedes Elternteam und den Vorstand mit ihren jeweiligen Rollen visuell gruppieren — Team-Checkbox oben, darunter eingerückt die Checkboxen seiner Rollen — statt der aktuell fünf flachen, unverbundenen Optgroups.

**Architecture:** Task 1 fügt der Komponente eine abgeleitete Datenstruktur hinzu, die jedes Team mit seinen zugehörigen Rollen zusammenfasst (Parent-Team-Rollen über `value.teamInstanceId`, Board-Rollen ungefiltert unter dem einzigen Board-Team). Task 2 verdrahtet diese Struktur im Template als verschachtelte `*ngFor`-Schleifen innerhalb der bestehenden "Elternteams"- und "Vorstand"-Optgroups und entfernt die bisherigen separaten "Team-Rollen"/"Vorstandsrollen"-Optgroups, plus ein CSS-Einzug für Rollen-Optionen.

**Tech Stack:** Angular (standalone components), Angular Material (`mat-select`, `mat-optgroup`, `mat-option`), Jasmine/Karma.

## Global Constraints

- Team- und Rollen-Checkboxen bleiben technisch unabhängige Auswahlkriterien — keine Kaskadierung (spec: "Auswahl-Logik").
- `RecipientSelection`, Backend-Validierung, `optionValue()`, `toSelections()` bleiben unverändert — nur die visuelle Anordnung im Dropdown ändert sich (spec: "Nicht Teil dieser Änderung").
- "Gruppen"-Optgroup bleibt unverändert (flache Liste).
- Teams ohne zugehörige Rollen zeigen nur ihre eigene Checkbox, keine leere Einrückung (spec: "Design").
- Board-Rollen werden ungefiltert unter dem einzigen Board-Team gruppiert (kein `teamInstanceId`-Vergleich nötig für Board).

---

### Task 1: Team-Rollen-Gruppierung in der Komponente berechnen

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts`
- Test: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

**Interfaces:**
- Consumes: bestehende Felder `parentTeams: FieldInstanceDTO[]`, `teamRoles: FieldInstanceDTO[]`, `boardTeams: FieldInstanceDTO[]`, `boardRoles: FieldInstanceDTO[]` (`mail-job-editor.component.ts:45-49`); `FieldInstanceDTO.value: unknown`, wobei ein `parent-team-role`-Wert zur Laufzeit `{ label: string, teamInstanceId: string, ... }` ist.
- Produces: `export interface TeamWithRoles { team: FieldInstanceDTO; roles: FieldInstanceDTO[] }`, neue Felder `parentTeamGroups: TeamWithRoles[]` und `boardTeamGroups: TeamWithRoles[]` auf `MailJobEditorComponent`, beide befüllt nach jedem Pool-Load (inklusive Zwischenständen, bevor alle 5 Pools geladen sind).

- [x] **Step 1: Fixture um ein zweites Team mit eigener Rolle erweitern und Grouping-Tests schreiben (failing)**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`, in der `FakeFieldInstanceService`-Klasse (aktuell Zeilen 106-128), den Eintrag `'def-team'` um ein zweites Team und `'def-team-role'` um `teamInstanceId` sowie eine zweite, zu Team 2 gehörende Rolle erweitern:

```typescript
class FakeFieldInstanceService {
  byDefinition: Record<string, FieldInstanceDTO[]> = {
    'def-group': [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
      { id: 'g2', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Blaue Gruppe' }, definitionOutdated: false },
    ],
    'def-team': [
      { id: 't1', definitionId: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false, value: { label: 'Gartenteam' }, definitionOutdated: false },
      { id: 't2', definitionId: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false, value: { label: 'Kuechenteam' }, definitionOutdated: false },
    ],
    'def-board': [
      { id: 'b1', definitionId: 'def-board', fieldName: 'board', label: { de: 'Vorstand' }, jsonSchema: {}, required: false, value: { label: 'Vorstand' }, definitionOutdated: false },
    ],
    'def-team-role': [
      { id: 'tr1', definitionId: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false, value: { label: 'Teamleitung', teamInstanceId: 't1' }, definitionOutdated: false },
      { id: 'tr2', definitionId: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false, value: { label: 'Kochleitung', teamInstanceId: 't2' }, definitionOutdated: false },
    ],
    'def-board-role': [
      { id: 'br1', definitionId: 'def-board-role', fieldName: 'board-role', label: { de: 'Vorstandsrollen' }, jsonSchema: {}, required: false, value: { label: 'Obfrau' }, definitionOutdated: false },
    ],
  };
  listByDefinitionId(definitionId: string) {
    return of(this.byDefinition[definitionId] ?? []);
  }
}
```

Danach im Test `'loads all five recipient pools on init'` (aktuell Zeile 224-230) die `parentTeams`-Assertion an die zwei Teams anpassen:

```typescript
  it('loads all five recipient pools on init', () => {
    expect(component.groups.map((g) => g.id)).toEqual(['g1', 'g2']);
    expect(component.parentTeams.map((t) => t.id)).toEqual(['t1', 't2']);
    expect(component.boardTeams.map((t) => t.id)).toEqual(['b1']);
    expect(component.teamRoles.map((r) => r.id)).toEqual(['tr1', 'tr2']);
    expect(component.boardRoles.map((r) => r.id)).toEqual(['br1']);
  });
```

Direkt danach drei neue Tests einfügen:

```typescript
  it('groups each parent team with its own roles (matched via teamInstanceId)', () => {
    expect(component.parentTeamGroups).toEqual([
      { team: component.parentTeams[0], roles: [component.teamRoles[0]] },
      { team: component.parentTeams[1], roles: [component.teamRoles[1]] },
    ]);
  });

  it('groups the board team with all board roles (no teamInstanceId needed)', () => {
    expect(component.boardTeamGroups).toEqual([
      { team: component.boardTeams[0], roles: component.boardRoles },
    ]);
  });

  it("does not spill a role into a different team's group", () => {
    expect(component.parentTeamGroups[0].roles.map((r) => r.id)).toEqual(['tr1']);
    expect(component.parentTeamGroups[1].roles.map((r) => r.id)).toEqual(['tr2']);
  });
```

- [x] **Step 2: Tests laufen lassen und Fehlschlag verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/mail-job-editor.component.spec.ts'`
Expected: FAIL — `component.parentTeamGroups` und `component.boardTeamGroups` existieren nicht (TypeScript-Compile-Fehler `Property 'parentTeamGroups' does not exist`), sowie die angepasste `parentTeams`-Assertion schlägt fehl, solange die Fixture noch nicht der alten Erwartung entspricht (sie ist zu diesem Zeitpunkt aber schon angepasst, also schlägt nur die Compile-Fehlermeldung wegen der fehlenden Felder zu Buche).

- [x] **Step 3: `TeamWithRoles`, die neuen Felder und `buildTeamGroups()` implementieren**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts`, nach den bestehenden Imports (vor `const DEFAULT_CRON = ...`, aktuell Zeile 25), das neue Interface einfügen:

```typescript
export interface TeamWithRoles {
  team: FieldInstanceDTO;
  roles: FieldInstanceDTO[];
}
```

In der Klasse `MailJobEditorComponent`, direkt nach den bestehenden Pool-Feldern (aktuell Zeilen 45-49, nach `boardRoles: FieldInstanceDTO[] = [];`), die zwei neuen Felder ergänzen:

```typescript
  /** Jedes Team, zusammen mit den Rollen, die zu ihm gehören (siehe {@link buildTeamGroups}). */
  parentTeamGroups: TeamWithRoles[] = [];
  boardTeamGroups: TeamWithRoles[] = [];
```

Die bestehende `onPoolLoaded()`-Methode (aktuell Zeilen 120-125) so ändern, dass sie bei jedem Aufruf die Gruppen neu berechnet:

```typescript
  /** Once every pool has loaded, drop any selection whose instance no longer exists in its pool. */
  private onPoolLoaded(): void {
    this.buildTeamGroups();
    this.poolsLoadedCount++;
    if (this.poolsLoadedCount === MailJobEditorComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
  }

  /**
   * Fasst jedes Team mit den Rollen zusammen, die zu ihm gehören. Elternteam-Rollen
   * tragen dazu in value.teamInstanceId die ID ihres Teams; Board-Rollen haben kein
   * teamInstanceId, weil es nur ein einziges Board-Team gibt, also landen sie
   * ungefiltert unter diesem einen Team.
   */
  private buildTeamGroups(): void {
    this.parentTeamGroups = this.parentTeams.map((team) => ({
      team,
      roles: this.teamRoles.filter((r) => this.roleTeamInstanceId(r) === team.id),
    }));
    this.boardTeamGroups = this.boardTeams.map((team) => ({
      team,
      roles: this.boardRoles,
    }));
  }

  private roleTeamInstanceId(role: FieldInstanceDTO): string | undefined {
    return (role.value as { teamInstanceId?: string } | null)?.teamInstanceId;
  }
```

- [x] **Step 4: Tests laufen lassen und Erfolg verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/mail-job-editor.component.spec.ts'`
Expected: PASS — alle Tests grün, inklusive der drei neuen Grouping-Tests und der angepassten `parentTeams`-Assertion.

- [x] **Step 5: Commit**

```bash
git add frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts
git commit -m "feat(fe): Team-Rollen-Gruppierung fuer Mailjob-Empfaenger berechnen"
```

---

### Task 2: Dropdown-Template auf verschachtelte Team/Rollen-Gruppen umstellen

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html:99-113`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.scss`
- Test: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

**Interfaces:**
- Consumes: `parentTeamGroups: TeamWithRoles[]`, `boardTeamGroups: TeamWithRoles[]` (aus Task 1), bestehende `optionValue(kind: RecipientKind, instanceId: string): string` und `instanceLabel(i: FieldInstanceDTO): string`.
- Produces: keine neuen Interfaces — reine Template-/Styling-Änderung.

- [x] **Step 1: Bestehende Optgroup-Tests an die neue 3-Optgroup-Struktur anpassen (failing) und einen Indentations-Test ergänzen**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`, im `describe('MailJobEditorComponent (Template)', ...)`-Block, den Test `'renders one optgroup per non-empty pool'` (aktuell Zeilen 415-422) ersetzen durch:

```typescript
  it('renders one optgroup per non-empty pool', () => {
    component.newJob();
    component.form.patchValue({ allParents: false });
    fixture.detectChanges();

    expect(openAndReadOptgroupLabels()).toEqual(['Gruppen', 'Elternteams', 'Vorstand']);
  });
```

Den Test `'omits the optgroup of an empty pool'` (aktuell Zeilen 424-431) ersetzen durch:

```typescript
  it('omits the Elternteams optgroup when there are no parent teams', () => {
    component.newJob();
    component.form.patchValue({ allParents: false });
    component.parentTeams = [];
    component.parentTeamGroups = [];
    fixture.detectChanges();

    expect(openAndReadOptgroupLabels()).not.toContain('Elternteams');
  });
```

Direkt danach einen neuen Test ergänzen, der die verschachtelte Team/Rollen-Reihenfolge inklusive Einzug prüft:

```typescript
  it('renders each team option followed by its own indented role options', () => {
    component.newJob();
    component.form.patchValue({ allParents: false });
    fixture.detectChanges();

    const trigger: HTMLElement = fixture.nativeElement.querySelector('.recipient-field .mat-mdc-select-trigger');
    trigger.click();
    fixture.detectChanges();

    const optgroups = Array.from(document.querySelectorAll('.mat-mdc-optgroup'));
    const elternteamsGroup = optgroups.find((g) =>
      g.querySelector('.mat-mdc-optgroup-label')?.textContent?.trim() === 'Elternteams');
    const options = Array.from(elternteamsGroup!.querySelectorAll('mat-option')).map((el) => ({
      text: el.textContent?.trim(),
      indented: el.classList.contains('recipient-role-option'),
    }));

    expect(options).toEqual([
      { text: 'Gartenteam', indented: false },
      { text: 'Teamleitung', indented: true },
      { text: 'Kuechenteam', indented: false },
      { text: 'Kochleitung', indented: true },
    ]);
  });
```

- [x] **Step 2: Tests laufen lassen und Fehlschlag verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/mail-job-editor.component.spec.ts'`
Expected: FAIL — `'renders one optgroup per non-empty pool'` erwartet `['Gruppen', 'Elternteams', 'Vorstand']`, bekommt aber weiterhin `['Gruppen', 'Elternteams', 'Vorstand', 'Team-Rollen', 'Vorstandsrollen']`; der neue Indentations-Test findet keine Optionen mit Klasse `recipient-role-option`.

- [x] **Step 3: Template umbauen**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html`, die Zeilen 99-113 (die fünf Optgroups "Gruppen" bis "Vorstandsrollen") ersetzen durch:

```html
            <mat-optgroup label="Gruppen" *ngIf="groups.length">
              <mat-option *ngFor="let g of groups" [value]="optionValue('GROUP', g.id!)">{{ instanceLabel(g) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Elternteams" *ngIf="parentTeams.length">
              <ng-container *ngFor="let group of parentTeamGroups">
                <mat-option [value]="optionValue('TEAM', group.team.id!)">{{ instanceLabel(group.team) }}</mat-option>
                <mat-option *ngFor="let r of group.roles" class="recipient-role-option" [value]="optionValue('ROLE', r.id!)">{{ instanceLabel(r) }}</mat-option>
              </ng-container>
            </mat-optgroup>
            <mat-optgroup label="Vorstand" *ngIf="boardTeams.length">
              <ng-container *ngFor="let group of boardTeamGroups">
                <mat-option [value]="optionValue('TEAM', group.team.id!)">{{ instanceLabel(group.team) }}</mat-option>
                <mat-option *ngFor="let r of group.roles" class="recipient-role-option" [value]="optionValue('ROLE', r.id!)">{{ instanceLabel(r) }}</mat-option>
              </ng-container>
            </mat-optgroup>
```

- [x] **Step 4: Einzugs-Styling für Rollen-Optionen ergänzen**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.scss`, am Ende der Datei ergänzen. `mat-option`-Elemente eines `mat-select` werden von Angular Material per CDK Overlay in einem eigenen, außerhalb dieser Komponente liegenden Panel gerendert (nicht im DOM-Teilbaum der Komponente) — Angulars komponentengekapseltes CSS erreicht sie deshalb nicht; `::ng-deep` ganz ohne einschließenden Vorfahren-Selektor macht die Regel absichtlich global, wie es dieses Projekt bereits in `cooking.component.scss` für ähnliche Overlay-Inhalte nutzt:

```scss
/* mat-option-Panel wird per CDK Overlay außerhalb dieser Komponente gerendert,
   daher hier absichtlich global (kein einschließender Vorfahren-Selektor). */
::ng-deep .recipient-role-option {
  padding-left: 40px;
}
```

- [x] **Step 5: Tests laufen lassen und Erfolg verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/mail-job-editor.component.spec.ts'`
Expected: PASS — alle Tests grün, inklusive der beiden angepassten Optgroup-Tests und des neuen Indentations-Tests.

- [x] **Step 6: Production-Build laufen lassen**

Run: `cd frontend && npx ng build`
Expected: Build erfolgreich, keine Template- oder Compile-Fehler.

- [x] **Step 7: Commit**

```bash
git add frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.scss frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts
git commit -m "feat(fe): Dropdown zeigt Teams mit eingerueckten Rollen statt separater Optgroups"
```

---

## Manueller Smoke-Test (nach Task 2, vor Abschluss des Branches)

1. Frontend lokal starten (`cd frontend && npx ng serve`), zu Einstellungen → Mail → Mailjobs navigieren, einen Job anlegen oder bearbeiten.
2. "Alle Eltern" deaktivieren, Empfänger-Dropdown öffnen.
3. Prüfen: drei Optgroups "Gruppen", "Elternteams", "Vorstand". Unter "Elternteams" erscheint pro Team dessen Checkbox, direkt darunter eingerückt die Checkboxen seiner Rollen (falls vorhanden). Unter "Vorstand" die Vorstand-Checkbox, darunter eingerückt alle Vorstandsrollen.
4. Team-Checkbox und eine Rollen-Checkbox unabhängig voneinander an-/abwählen, prüfen dass beide unabhängig bestehen bleiben (keine Kaskadierung).
5. Speichern, Job erneut öffnen, prüfen dass die getroffene Auswahl korrekt wiederhergestellt wird.
