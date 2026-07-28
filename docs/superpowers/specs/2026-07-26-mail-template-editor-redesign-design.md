# Mail-Vorlagen-Editor — Neugestaltung

**Datum:** 2026-07-26
**Status:** Design freigegeben, bereit für Implementierungsplan
**Betrifft:** Frontend `settings/mail/mail-template-editor` (die im Feature `mail-templates-jobs`, Commit `d1f9026`, eingeführte Komponente)

## Problem

Der ausgelieferte Vorlagen-Editor ist unbedienbar (siehe Screenshot 2026-07-26):

1. **Kein sichtbares Editor-Feld.** Toolbar-Icons rendern als riesige rohe SVGs (Dreiecke, QR-artige Muster), das Schreibfeld hat keine Höhe/Rahmen.
2. **Platzhalter lassen sich nicht an eine Textstelle ziehen.** Aktuell fügt nur ein Klick am Cursor ein; kein Drag & Drop.
3. **Unklar, wie man es bedient** — kein Feedback, was ein Platzhalter bewirkt.

## Ursache (verifiziert)

Das Quill-Stylesheet `quill.snow.css` wird nirgends geladen — weder in `angular.json` noch in `src/styles.scss` (per grep bestätigt). ngx-quill nutzt standardmäßig das „snow"-Theme, dessen CSS Toolbar **und** Schreibfläche formatiert. Ohne dieses CSS ist der Editor optisch zerstört. Das ist ein Bug, kein Design-Fehler — und die Voraussetzung für alles Weitere.

## Entscheidungen (im Visual-Companion festgelegt)

- **Layout:** Variante C — volle Breite, gestapelt (statt der bisherigen drei gequetschten Spalten).
- **Platzhalter-Darstellung im Text:** farbige, zusammenhängende **Pille** mit lesbarem Label und ×-Entfernen (nicht roher `{{…}}`-Text).
- **Einfügen:** **beide** Wege — Chip anklicken (am Cursor) *und* Chip an eine Textstelle ziehen (Drag & Drop).
- **Live-Vorschau:** ja, mit **Beispiel-Daten** (Musterwerte pro Feld), live beim Tippen.

## Design

### 0. Bugfix — Fundament

Quill-Theme-CSS einbinden, damit der Editor überhaupt korrekt rendert:
- `quill/dist/quill.snow.css` zu den globalen Styles hinzufügen (`angular.json` `styles`-Array oder `@import` in `src/styles.scss` — die im Repo etablierte Methode nachahmen).
- Danach rendern Toolbar und Schreibfläche normal.

### 1. Layout (Variante C — gestapelt, volle Breite)

`mail-template-editor.component.html`/`.scss` von Flex-3-Spalten auf eine vertikale Anordnung umbauen:

1. **Vorlagen-Auswahl** oben: Dropdown (`mat-select`) mit allen Vorlagen + „Neue Vorlage"-Button + Löschen-Button für die aktuell gewählte Vorlage. (Ersetzt die bisherige `mat-nav-list` links.)
2. **Name**-Feld (`mat-form-field`, wie bisher).
3. **Platzhalter-Chip-Leiste**: horizontale, umbrechende Reihe von Chips aus `placeholders()`. Jeder Chip ist klickbar **und** ziehbar.
4. **Editor** (`<quill-editor>`) über die volle Breite, `min-height` ~300px.
5. **Live-Vorschau** darunter (siehe §3).
6. **Speichern**-Button.

### 2. Platzhalter als Pille + Einfügen

**Darstellung.** Ein Custom-Quill-Blot (inline embed, `contenteditable=false`) rendert einen Platzhalter als Pille: `<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>` mit ×-Affordanz zum Entfernen. Das Blot speichert `fieldName`/`token`/`label`.

**Einfügen — zwei Wege:**
- **Klick** auf einen Chip → `quill.insertEmbed(index, 'mail-token', {...})` an der aktuellen Cursor-Position (bzw. Ende, wenn keine Selektion).
- **Drag & Drop** → Chip mit `draggable`; beim Drop in den Editor die Ziel-Index-Position aus der Caret-Position am Drop-Punkt bestimmen und dort das Blot einfügen.

**Backend-Grenze (unverändert!).** Persistiert wird weiterhin **roher Token** `{{person.<fieldName>}}` im HTML — exakt das Vertrag mit dem Backend-Renderer (`MailTemplateRenderer`, Feature `mail-templates-jobs`) und dem OWASP-Sanitizer. Deshalb zwei reine Transform-Funktionen:
- **Beim Speichern:** Editor-HTML → jede Pille-`<span>` durch ihren rohen `{{person.fieldName}}`-Text ersetzen, dann `bodyHtml` persistieren.
- **Beim Laden/Auswählen:** gespeichertes HTML → jeden `{{person.fieldName}}`-Token durch eine Pille-`<span>` ersetzen, bevor der Quill-Inhalt gesetzt wird.

Diese Transformation ist die einzige Kopplung; **kein Backend-Code, kein Sanitizer, keine Backend-Tests werden angefasst.**

**Toolbar** bleibt unverändert (`EMAIL_SAFE_QUILL_TOOLBAR`): fett/kursiv/unterstrichen, Farbe/Hintergrund, Größe, Ausrichtung, Link.

### 3. Live-Vorschau mit Beispiel-Daten

Ein Vorschau-Bereich unter dem Editor zeigt die gerenderte Mail:
- Nimmt die **Speicherform** des HTML (mit `{{person.x}}`-Tokens) und ersetzt jeden Token clientseitig durch einen festen Musterwert (z. B. `firstName`→„Anna", `lastName`→„Muster", `email`→„anna@example.org", `phone`→„+43 …" usw. — eine Beispiel-Map pro erlaubtem Feldnamen aus der R3-Allowlist).
- Aktualisiert **live** beim Tippen (auf Wertänderung der `bodyHtml`-Control).
- Beschriftung „Vorschau mit Beispiel-Daten", da der echte Versand serverseitig rendert (die clientseitige Ersetzung ist eine Näherung mit demselben `{{person.x}}`-Muster).
- Gerendert als HTML in einer abgegrenzten Box.

## Umfang

**Betroffen:**
- `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.{ts,html,scss,spec.ts}`
- `frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts` (Custom-Blot-Registrierung ergänzen)
- ggf. eine neue kleine Datei für Blot + Token↔Pille-Transforms + Beispiel-Daten (z. B. `mail-token.blot.ts` / `mail-token.util.ts`)
- `frontend/angular.json` **oder** `frontend/src/styles.scss` (Quill-CSS)

**Nicht betroffen:** gesamtes Backend, `mail-job-editor`, `mail.component`, alle Services/Modelle.

## Tests (Repo-Konvention: direkte Instanziierung, Fake-Services, kein TestBed)

- **Reine Funktionen** als Unit-Tests: Token→Pille und Pille→Token (Round-Trip: `{{person.firstName}}` → Pille-HTML → `{{person.firstName}}`); Vorschau-Ersetzung (Token-HTML + Beispiel-Map → erwartetes gerendertes HTML).
- **Einfügen:** Klick auf Chip aktualisiert die `bodyHtml`-Control mit dem Token; Drag-Drop-Pfad ruft dieselbe Einfüge-Logik.
- **Laden/Speichern-Round-Trip:** eine Vorlage mit Token auswählen → Pille sichtbar → speichern → gesendetes `bodyHtml` enthält wieder den rohen Token.
- **CSS-Einbindung:** build-verifiziert (`ng build`).

## Nicht-Ziele

- Keine Änderung am Backend-Render-/Sanitize-/Versand-Pfad.
- Kein serverseitiges Preview-Rendering (clientseitige Näherung genügt).
- Keine neuen Toolbar-Formate (Listen/Einzug bleiben ausgeschlossen — kein Inline-Style-Äquivalent in Quill core, wie im Ursprungs-Feature entschieden).
- Keine Änderung am Job-Editor, auch wenn er dieselbe Tab-Gruppe teilt.

## Offene Risiken

- **Drop-Index aus Caret-Position** bei Drag & Drop ist der fummeligste Teil (Browser-`caretPositionFromPoint`/`caretRangeFromPoint` → Quill-Index). Fällt es aus, bleibt Klick-Einfügen der robuste Standard; Drag ist der Komfort-Zusatz.
- **Pille im gespeicherten HTML:** sicherstellen, dass nach dem Save-Transform **keine** `mail-token`-`<span>`s im persistierten `bodyHtml` landen (sonst strippt der Sanitizer sie und der Token geht verloren). Der Round-Trip-Test deckt das ab.
