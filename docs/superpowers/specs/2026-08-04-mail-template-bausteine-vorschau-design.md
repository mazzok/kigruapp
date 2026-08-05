# Mail-Template-Bausteine: Vorschau im Konfig-Dialog (Design)

## Kontext

Erweiterung von [`2026-08-04-mail-template-bausteine-design.md`](2026-08-04-mail-template-bausteine-design.md)
(bereits implementiert, `feature/mail-template-bausteine`). Feedback nach
Fertigstellung: der Baustein-Konfigurations-Dialog (`MailBlockConfigDialogComponent`)
zeigt nur das Formular — man sieht nicht, was die konfigurierte Kochdienst-Tabelle
tatsächlich enthalten wird, bevor man speichert.

## Architektur

Ein neuer, generischer Backend-Endpoint rendert die Baustein-Konfiguration über
**dieselbe** `MailBlockRenderer`-Registry, die auch beim echten Mail-Versand
läuft (`MailTemplateRenderer`) — keine zweite, parallele Render-Logik, die aus
dem Takt geraten könnte.

- `POST /api/v1/mail-templates/blocks/preview`
  Body: die aktuelle Baustein-Konfiguration als JSON (exakt die Form, die der
  Dialog auch beim Speichern liefert, z.B.
  `{"type":"cookingDuty","groupId":"...","periodUnit":"week","periodAmount":2}`).
  Response: `{"html": "<table>...</table>"}` bzw. `{"html": "<p>Keine
  Kochdienst-Einträge...</p>"}`.
  Erbt automatisch den Admin-only-Schutz von `/api/v1/mail-templates` (kein
  neuer Whitelist-Eintrag in `SecurityFilter` nötig — Default-Deny greift für
  jeden Pfad unter diesem Prefix).
  Liest den `type`-Schlüssel aus dem Body, sucht den passenden
  `MailBlockRenderer` (CDI `@All List<MailBlockRenderer>`, dieselbe
  Injektionsart wie in `MailTemplateRenderer`) und ruft dessen `render(JsonNode)`
  auf. Da `CookingDutyMailBlockRenderer.render()` nie wirft (liefert immer
  Tabelle oder Hinweistext), braucht der Endpoint keine Sonderbehandlung für
  fehlende Gruppe/leeren Zeitraum — nur wenn `type` fehlt oder kein Renderer
  ihn unterstützt, kommt eine Fehlerantwort (404).

- `MailBlockConfigDialogComponent` bekommt zwei Tabs (Angular Material
  `mat-tab-group`, gleiches Muster wie `mail.component.html`):
  - **"Konfiguration"** — bestehendes Formular (`app-cooking-duty-block-config`).
  - **"Vorschau"** — lädt die gerenderte HTML-Ausgabe **erst beim Wechsel auf
    diesen Tab** (`(selectedTabChange)`), und nur erneut, wenn sich Gruppe/
    Zeitraum seit dem letzten Laden geändert haben. Kein automatisches
    Live-Update bei jeder Formularänderung — spart Backend-Aufrufe.
  - Lädt nur, wenn das Formular gültig ist (Gruppe gewählt); zeigt sonst einen
    Hinweis statt einen Request zu feuern.
  - Ladezustand während des Requests; bei Fehlschlag ein Fehler-Hinweistext
    ("Vorschau nicht verfügbar."), kein Absturz des Dialogs.

## Komponenten

### Backend (neu)

- `MailTemplateResource` (Erweiterung) — neue Methode `previewBlock`,
  `@Inject @All List<MailBlockRenderer> blockRenderers`. Die Resource-Methode
  nimmt den Request-Body direkt als `JsonNode`-Parameter entgegen (kein
  `Map<String,Object>`/`ObjectMapper.valueToTree(...)`-Umweg nötig).

### Frontend (neu)

- `mail-template.service.ts` (Erweiterung) — `previewBlock(config: MailBlockConfig):
  Observable<{ html: string }>`.
- `mail-block-config-dialog.component.ts`/`.html` (Erweiterung) — Tab-Struktur,
  Lade-/Fehlerzustand, Lade-nur-bei-Tab-Wechsel-Logik.

## Testing

- Backend: Unit-/Integrationstest für `previewBlock` — liefert Tabelle bei
  Einträgen, Hinweistext bei leerem Zeitraum, 400 bei fehlendem `type`.
- Frontend: Dialog-Test — Tab-Wechsel triggert genau einen Request,
  wiederholter Wechsel ohne Konfigurationsänderung triggert **keinen**
  weiteren Request, Konfigurationsänderung danach löst beim nächsten
  Tab-Wechsel einen neuen Request aus, Fehlerfall zeigt Hinweistext statt zu
  werfen.

## Out of Scope

- Kein automatisches Live-Update bei jeder Formularänderung.
- Keine Vorschau in der bestehenden Mail-Vorschau-Box im Editor (nur im
  Konfig-Dialog).

## Nachtrag 2026-08-05: Kind-Beschränkung

Seit der Rebase auf main (nach der Aufteilung in `MailTemplateKind.COOKING_REMINDER`/
`COOKING_OVERVIEW`) ist der Kochdienst-Baustein ausschließlich im Editor für
Kochdienst-Übersichtsjobs (`kind === 'COOKING_OVERVIEW'`) verfügbar, nicht mehr in
allgemeinen Mail-Vorlagen oder bei Kochdienst-Erinnerungen. Details siehe
[2026-08-05-mail-bausteine-rebase-kochdienst-scoping-design.md](2026-08-05-mail-bausteine-rebase-kochdienst-scoping-design.md).
