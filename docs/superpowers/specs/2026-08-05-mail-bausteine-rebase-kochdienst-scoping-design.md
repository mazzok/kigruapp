# Mail-Bausteine: Rebase auf main + Kochdienst-Baustein auf Übersichtsjobs beschränken

## Kontext

Der Worktree `feature/mail-template-bausteine` hat parallel zu main die "Mail-Template
Bausteine"-Funktion entwickelt: eine Block-Palette im Mail-Template-Editor (Drag & Drop),
einen Quill-Embed-Blot, einen generischen Block-Konfigurations-Dialog, ein
`MailBlockRenderer`-Registry (Backend) mit einem Kochdienst-Block-Renderer, Marker-Auflösung
(`{{block.type:config}}`) sowie einen Preview-Endpoint/-Tab. Bislang existiert genau ein
Baustein: `cookingDuty` (Kochdienst).

Zwischenzeitlich hat main (`feature/kochdienst-erinnerungen`, gemerged) den bisherigen
`MailTemplateKind.COOKING` in zwei Arten aufgeteilt: `COOKING_REMINDER` (Erinnerungen pro
einzelnem Kochdienst) und `COOKING_OVERVIEW` (Übersichtsjobs, verwaltet über
`CookingOverviewJobResource`/`CookingOverviewJobsComponent`). Außerdem wurde der bisher
monolithische `mail-template-editor.component.ts` aufgeteilt in eine dünne Listen/CRUD-Hülle
(nur für `GENERAL`-Vorlagen) und eine wiederverwendbare `mail-template-form.component.ts`
(Name, Quill-Body, Platzhalter-Chips, Vorschau), die bereits `@Input() kind: MailTemplateKind`
kennt und von `CookingOverviewJobsComponent` eingebettet wird.

Der Worktree ist noch nicht auf main rebased. Die Block-Palette hängt aktuell an der alten,
mittlerweile aufgeteilten Editor-Komponente und kennt kein `kind` — sie würde nach einem
naiven Rebase in **jedem** Mail-Template auftauchen, nicht nur in Kochdienst-Übersichtsjobs.

## Ziel

1. Worktree sauber auf main rebasen.
2. Den Kochdienst-Baustein danach ausschließlich im Editor für Kochdienst-Übersichtsjobs
   (`kind === 'COOKING_OVERVIEW'`) anbieten — in normalen Mail-Vorlagen und bei
   Kochdienst-Erinnerungen (`COOKING_REMINDER`) darf er nicht mehr erscheinen.

## Vorgehen

### 1. Rebase

`git rebase main` im Worktree, Commit für Commit, mit manueller Konfliktlösung. Erwartete
Konfliktstellen:

- `mail-template-editor.component.ts/.html/.spec.ts` — main hat diese Datei aufgeteilt
  (Shell + `mail-template-form.component.ts`). Der Bausteine-Commit, der die Palette in den
  Editor verdrahtet (`0ea4eec feat(fe): block palette with drag/drop insert and marker
  round-trip in mail template editor`), muss beim Rebase gezielt gegen die neue
  `mail-template-form.component.ts` statt gegen die alte Editor-Datei aufgesetzt werden.
- `mail-template.model.ts` — main hat inzwischen ein `kind`-Feld auf `MailTemplate` sowie den
  Typ `MailTemplateKind` ergänzt.

Commits, die reine Bausteine-Logik ohne Bezug zur Editor-Struktur einführen (Blot, Renderer,
Registry, Config-Dialog, Preview-Endpoint), werden voraussichtlich konfliktfrei durchlaufen.

### 2. Palette an `mail-template-form.component.ts` binden

- `MAIL_BLOCK_DEFINITIONS` bleibt die Quelle aller bekannten Bausteine, aber
  `mail-template-form.component.ts` exponiert eine gefilterte Sicht (Getter/computed), die
  nur Bausteine liefert, deren Sichtbarkeit zum aktuellen `this.kind` passt. Für den einzig
  existierenden Baustein `cookingDuty` heißt das: sichtbar nur bei
  `kind === 'COOKING_OVERVIEW'`.
- Die Sichtbarkeitsregel wird direkt an der Baustein-Definition hinterlegt (z. B. ein Feld
  `visibleForKinds: MailTemplateKind[]` auf `MailBlockDefinition`), nicht hart im Formular
  verdrahtet — damit künftige Bausteine mit anderer Sichtbarkeit sich einreihen, ohne die
  Filterlogik anzufassen.
- Palette-Template (Drag-Source-Chips), Quill-Blot-Registrierung und
  Block-Konfigurations-Dialog-Trigger wandern aus der alten Editor-Komponente in
  `mail-template-form.component.ts/.html`.
- `MailTemplateEditorComponent` (die GENERAL-Listen-Hülle) erhält dadurch automatisch keine
  Bausteine-Palette — `kind` bleibt dort `'GENERAL'`, kein zusätzlicher Ausschluss nötig.
- `CookingOverviewJobsComponent` bindet bereits `<app-mail-template-form>`; sofern dort
  `kind="COOKING_OVERVIEW"` gesetzt ist (im Rahmen der Umsetzung zu verifizieren/ergänzen),
  erscheint die Palette dort automatisch.

### 3. Kein Backend-Scoping

`MailBlockRenderer` / `MailTemplateRenderer` bleiben unverändert und lösen
`{{cookingDuty:...}}`-Marker unabhängig vom `kind` des Templates auf. Das ist bewusst
UI-only, konsistent mit dem bestehenden Muster, dass auch die Kochdienst-Jobs sonst nur
UI-seitig gated sind (kein Datenintegritätsrisiko, da Templates nicht direkt per API von
Endnutzern frei editierbar sind).

### 4. Tests

- Bausteine-bezogene Specs, die aktuell an der alten Editor-Komponente hängen
  (`mail-template-editor.component.spec.ts`, `mail-block.util.spec.ts`,
  `mail-block-config-dialog.component.spec.ts`), ziehen auf
  `mail-template-form.component.spec.ts` bzw. bleiben als eigenständige Util-/Dialog-Specs
  bestehen, wo sie komponentenunabhängig sind.
- Neuer Test: Palette zeigt den Kochdienst-Baustein bei `kind='COOKING_OVERVIEW'`, nicht bei
  `kind='GENERAL'` oder `kind='COOKING_REMINDER'`.
- Bestehende Preview-/Marker-Rendering-Tests (Backend) bleiben unverändert gültig, da das
  Rendering kind-unabhängig bleibt.

### 5. Docs

Die bestehenden Design-Dokumente (`2026-08-04-mail-template-bausteine-design.md`,
`2026-08-04-mail-template-bausteine-vorschau-design.md`) werden nicht neu geschrieben,
sondern erhalten einen kurzen Nachtrag, der auf dieses Dokument verweist und die
Kind-Beschränkung als aktuellen Stand vermerkt.

## Out of Scope

- Keine Änderung an der Rendering-/Marker-Logik im Backend.
- Keine neuen Bausteine.
- Keine Änderung am Berechtigungsmodell für Kochdienst-Jobs.
