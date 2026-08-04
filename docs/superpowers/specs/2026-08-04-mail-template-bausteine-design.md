# Mail-Template-Bausteine: Kochdienst-Baustein (Design)

## Kontext

Mail-Templates unterstützen bisher nur einzelne Platzhalter-Pills (Tokens wie
`{{firstName}}`) im Quill-Editor (`mail-template-editor.component.ts`,
`mail-token.blot.ts`, `mail-token.util.ts`). Es gibt kein Baustein-/Block-Konzept.

Ziel: ein generisches, erweiterbares Baustein-Gerüst für Mail-Templates, mit dem
Kochdienst-Baustein als erstem konkreten Typ. Ein Baustein wird per Drag & Drop
aus einer Seitenleiste in den Editor gezogen und rendert beim tatsächlichen
Mail-Versand eine Tabelle mit den Kochdienst-Einträgen einer konfigurierten
Gruppe über einen relativen Zeitraum.

## Architektur-Überblick

Analog zum bestehenden Token-Muster wird die Baustein-Konfiguration direkt als
Daten-Attribut im Editor-HTML (`bodyHtml`) gespeichert — kein zusätzliches
Persistenz-Feld am `MailTemplate`, kein Sync-Problem zwischen Text und
Konfiguration.

- **Frontend-Registry**: `MailBlockDefinition[]` (type, label, icon) treibt eine
  neue "Bausteine"-Sektion in der Editor-Seitenleiste, zusätzlich zu den
  bestehenden Platzhaltern.
- **Editor-Repräsentation**: neuer nicht editierbarer Block-Blot `mail-block`
  (block-scoped Custom Blot, analog `mail-token.blot.ts` aber block- statt
  inline-Element). Wird beim Drop mit Default-Konfiguration eingefügt und zeigt
  Icon + Kurzbeschreibung (z.B. "Kochdienst: Gruppe Sonne, nächste 2 Wochen")
  plus einen Bearbeiten-Button.
- **Konfiguration**: Klick auf den Bearbeiten-Button öffnet einen Dialog, der je
  nach `blockType` das passende Formular lädt. Speichern aktualisiert das
  `data-config`-Attribut des Blots.
- **Speicherung**: Der bestehende OWASP-HTML-Sanitizer (`MailTemplateResource
  .sanitizeBody`) erlaubt beim Speichern nur eine feste Attribut-Allowlist
  (`style`, `href`) — ein `data-config`-Attribut würde entfernt. Deshalb wird
  der Baustein exakt wie die Platzhalter behandelt: im Editor ist er ein Blot
  (visuelles Element mit Icon/Label), **gespeichert** wird er als reiner
  Text-Marker: `{{block.cookingDuty:<base64url-kodierte-config-json>}}`. Die
  Konvertierung Editor-Blot ↔ Text-Marker läuft über `mail-block.util.ts`,
  analog zu `tokensToPills`/`pillsToTokens` in `mail-token.util.ts`.
- **Rendering beim Versand**: `MailTemplateRenderer` (Backend) erweitert sein
  bestehendes Regex-basiertes Scanning (es nutzt schon `Pattern`/`Matcher` für
  `{{person.xxx}}`-Tokens, kein Jsoup im Projekt) um ein zweites Pattern für
  `{{block.<type>:<config>}}`-Marker. Für jeden Treffer wird die
  Base64url-kodierte Config dekodiert, als JSON geparst (`ObjectMapper`, CDI,
  bereits im Projekt genutzt) und an eine Registry von
  `MailBlockRenderer`-Implementierungen übergeben (CDI,
  `Instance<MailBlockRenderer>`, `supports(type)` / `render(JsonNode config)`),
  die den Marker durch fertiges HTML ersetzen. `CookingDutyMailBlockRenderer`
  ist die erste Implementierung.

## Kochdienst-Baustein: Konfiguration & Rendering

- **Konfig-Modell**:
  ```ts
  interface CookingDutyBlockConfig {
    type: 'cookingDuty';
    groupId: string;
    periodUnit: 'week' | 'month';
    periodAmount: number; // 1, 2, 3, ...
  }
  ```
  Genau eine Gruppe pro Baustein. Zeitraum ist relativ und wird beim
  tatsächlichen Versandzeitpunkt (manuell oder Cron-Job) ab "jetzt" berechnet,
  z.B. `periodUnit=week, periodAmount=2` → die nächsten 2 Wochen ab
  Versanddatum.

- **Datenquelle**: `CookingDutyResource` sucht heute direkt über
  `FieldDefinition`/`FieldInstance` (Feldname `cookingDuty`) und filtert nach
  Monat/Gruppen. Diese Query-Logik wird in einen gemeinsamen
  `CookingDutyQueryService` extrahiert (Methode akzeptiert Datumsbereich +
  Gruppen-ID statt nur Monat), den sowohl `CookingDutyResource` als auch
  `CookingDutyMailBlockRenderer` nutzen.

- **Tabellen-Ausgabe**: Spalten *Datum*, *Person*, *Beschreibung*. Email-sichere
  Inline-Styles, analog zur bestehenden Sanitizing-Strategie in
  `quill-email-safe.config.ts`.

- **Leerer Zustand**: keine Einträge im Zeitraum/Gruppe → Hinweistext
  ("Keine Kochdienst-Einträge im gewählten Zeitraum.") statt leerer Tabelle.

- **Fehlerfall**: referenzierte Gruppe existiert nicht mehr (gelöscht) →
  Renderer wirft nicht, sondern rendert einen Hinweistext
  ("Gruppe nicht mehr vorhanden."), damit ein Mail-Versand nie an einem
  einzelnen defekten Baustein scheitert.

## Komponenten

### Frontend (neu)

- `mail-block.model.ts` — `MailBlockDefinition`-Registry (Palette-Einträge),
  `MailBlockConfig`-Union-Type (aktuell nur `CookingDutyBlockConfig`).
- `mail-block.blot.ts` — Custom Quill-Block-Blot, rendert Icon/Label/
  Kurzbeschreibung + Bearbeiten-Icon aus `data-config`.
- `mail-block.util.ts` — Serialisierung/Parsing der Block-Divs im `bodyHtml`
  (analog `mail-token.util.ts`).
- `mail-block-config-dialog.component.ts` — generischer Dialog-Host, lädt je
  `blockType` das passende Formular.
- `cooking-duty-block-config.component.ts` — Formular: Gruppen-Dropdown
  (bestehender Gruppen-Service), Zeiteinheit-Dropdown (Woche/Monat),
  Anzahl-Dropdown (1, 2, 3, ...).

### Frontend (Erweiterung)

- `mail-template-editor.component.ts` — neue Seitenleisten-Sektion "Bausteine"
  als Drag-Quelle, analog zur bestehenden Platzhalter-Sektion.

### Backend (neu)

- `MailBlockRenderer` — Interface (`boolean supports(String type)`,
  `String render(JsonNode config)`).
- `CookingDutyMailBlockRenderer` — erste Implementierung, nutzt
  `CookingDutyQueryService`.
- `CookingDutyQueryService` — extrahiert aus `CookingDutyResource`, gemeinsam
  genutzt von Resource und Renderer.

### Backend (Erweiterung)

- `MailTemplateRenderer` — zusätzlicher Schritt: `{{block.<type>:<config>}}`-
  Marker im `bodyHtml` per Regex scannen (kein Jsoup, siehe oben) und über die
  `MailBlockRenderer`-Registry ersetzen, nach der bestehenden Token-Auflösung.

## Testing

- Unit-Tests für Zeitraum-Berechnung (Woche/Monat, relativ zu "jetzt").
- Unit-Tests für `CookingDutyMailBlockRenderer`-Output: mit Einträgen, leer,
  Gruppe fehlt.
- Erweiterung bestehender `MailTemplateRenderer`-Tests um den Block-Fall.
- Frontend-Tests: Blot-Insert, Config-Dialog-Roundtrip (Config speichern →
  Blot-Attribut aktualisiert → erneutes Öffnen zeigt gespeicherte Werte).

## Out of Scope

- Weitere Bausteintypen (nur das Gerüst wird generisch angelegt, nicht weitere
  konkrete Typen).
- Mehrere Gruppen pro Baustein (dafür mehrere Bausteine ins Template ziehen).
- Personalisierung der Tabelle je Empfänger (Tabelle ist für alle Empfänger
  eines Versands identisch).
