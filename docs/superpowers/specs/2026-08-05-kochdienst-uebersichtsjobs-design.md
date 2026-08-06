# Kochdienst — zwei Reiter: Erinnerungen und Übersichtsjobs

Datum: 2026-08-05
Status: Design, freigegeben

## Ausgangslage

Der Bereich „Kochdienst — Erinnerungen" in Organisation → Dienst-Einstellungen
wurde kürzlich als `CookingReminderJobsComponent` gebaut
(siehe `docs/superpowers/specs/2026-08-04-kochdienst-erinnerungs-jobs-design.md`).
Fachlich deckt diese Komponente korrekt die **Erinnerungen** ab: pro Kochdienst
X Tage vorher eine Mail an die zuständige Familie, gesteuert über einen
täglichen Check zur eingestellten Uhrzeit (`sendTime`), mit fest verdrahteter
1:1-Vorlage.

Es fehlt jedoch ein zweiter, eigenständiger Bereich: **Übersichtsjobs**. Das
sind Jobs mit echtem, frei wählbarem Cron-Zeitpunkt (nicht "täglich prüfen, ob
etwas fällig ist"), konfigurierbaren Empfängern (wie bei normalen Mail-Jobs)
und einer eigenen 1:1-Vorlage. Sie fassen eine Zusammenfassung der
Kochdienste zusammen; der eigentliche Tabellen-Inhalt kommt aus dem separaten,
noch nicht gemergten Feature „Mail-Template-Bausteine"
(`feature/mail-template-bausteine`, Worktree `D:\GIT\kigruapp-mail-bausteine`)
und ist nicht Teil dieses Designs.

Beide Bereiche gehören konzeptionell zusammen (beide sind „Kochdienst-Jobs mit
1:1-Vorlage"), unterscheiden sich aber in Trigger-Art und Empfängerlogik. Sie
sollen als zwei Reiter im selben UI-Bereich erscheinen.

## Ziel

- Organisation → Dienst-Einstellungen → Kochdienst bekommt zwei Reiter:
  „Erinnerungen" (bestehend, unverändert) und „Übersichtsjobs" (neu).
- Übersichtsjobs verhalten sich fachlich wie normale (`GENERAL`) Mail-Jobs
  (Cron, konfigurierbare Empfänger), nur mit fest verdrahteter statt
  gemeinsam nutzbarer Vorlage — analog zum bereits bestehenden Muster bei den
  Erinnerungen.

## Datenmodell

### `kind`-Erweiterung

`MailTemplate.kind` und `MailJob.kind` bekommen einen dritten Wert:

```
GENERAL | COOKING_REMINDER | COOKING_OVERVIEW
```

- Der bestehende Wert `COOKING` wird zu `COOKING_REMINDER` umbenannt — überall
  im Backend- und Frontend-Code, der bisher `COOKING` referenziert
  (`MailJobScheduler`, `CookingReminderScheduler`, Migration, Vorlagen-/Job-
  Sperrlogik, TypeScript-Modelle/Chips).
- Neu: `COOKING_OVERVIEW` für Übersichtsjobs.

Eine Startup-Migration setzt bestehende Dokumente mit `kind="COOKING"` in
`mail_templates` und `mail_jobs` auf `kind="COOKING_REMINDER"` um. Die
Migration ist idempotent (betrifft nur noch verbliebene `"COOKING"`-Werte).
Die bestehende Singleton→Job-Migration aus dem Vorgänger-Feature schreibt ab
sofort direkt `kind="COOKING_REMINDER"` statt `"COOKING"`.

### `COOKING_OVERVIEW`-Job

Ein `MailJob` mit `kind=COOKING_OVERVIEW` hat exakt dieselbe Form wie ein
`GENERAL`-Job:

| Feld | Bedeutung |
|---|---|
| `name` | Anzeigename in der Liste |
| `templateId` | fest zugeordnete Vorlage (1:1, nicht teilbar) |
| `subject` | Betreff |
| `senderAccountId` | Mailkonto |
| `cron` | echter Cron-Ausdruck (Quartz), frei wählbar |
| `allParents` / `recipientSelections` | Empfänger wie bei `GENERAL` |
| `active` | steuert, ob der Job läuft |

Kein `sendTime`, keine `duty.*`-Sonderlogik. Trigger und Empfängerauflösung
laufen unverändert über die bestehende `GENERAL`-Job-Pipeline
(`MailJobScheduler`, `RecipientResolverService`).

## Backend

### Neuer Endpoint `/api/v1/cooking-overview-jobs`

Admin-only, analog zu `/cooking-reminder-jobs`:

- `GET` — Liste aller `COOKING_OVERVIEW`-Jobs samt eingebetteter Vorlage
  (`{ job, template }`).
- `POST` — legt Vorlage und Job in einem Request an. Reihenfolge: Vorlage
  zuerst, dann Job. Schlägt der Job-Insert fehl, wird die Vorlage wieder
  entfernt (Rollback), wie beim Reminder-Endpoint.
- `PUT /{id}` — aktualisiert beides.
- `DELETE /{id}` — löscht Job und Vorlage. Historie (Job-Run-Log) bleibt
  erhalten.

Validierung: Cron muss ein gültiger Quartz-Ausdruck sein, Betreff und
Vorlagen-Name dürfen nicht leer sein, das Mailkonto muss existieren.
`active=true` ist nur zulässig, wenn Konto existiert und `enabled` ist,
sonst 400 mit Begründung — dieselbe Validierungslogik wie beim bestehenden
`MailJobResource`/`cooking-reminder-jobs`-Endpoint, wiederverwendet statt
kopiert. Der Vorlagen-Body durchläuft dieselbe Sanitize-Pipeline wie in
`MailTemplateResource`.

Jede schreibende Operation ruft dieselbe Reschedule-Logik wie
`MailJobResource` für `GENERAL`-Jobs (kein separater Scheduler nötig, siehe
unten).

### `MailJobScheduler`

Überspringt bisher alle Jobs mit `kind=COOKING`. Die Skip-Bedingung wird auf
`kind=COOKING_REMINDER` verengt — `COOKING_OVERVIEW`-Jobs laufen durch den
regulären Scheduler-Pfad wie `GENERAL`-Jobs (Cron-Trigger, Empfängerauflösung,
Versand), ohne Sonderfall.

### Vorlagen und Platzhalter

- `GET /api/v1/mail-templates?kind=GENERAL|COOKING_REMINDER|COOKING_OVERVIEW`
  filtert entsprechend. `POST`/`PUT` legen weiterhin nur `GENERAL`-Vorlagen an;
  ein mitgeschicktes `kind` wird ignoriert. Bestehende `COOKING_REMINDER`- und
  `COOKING_OVERVIEW`-Vorlagen weisen `PUT`/`DELETE` über den allgemeinen
  Endpoint mit 409 ab — sie werden ausschließlich über ihren jeweiligen
  Job-Endpoint gepflegt.
- `GET /api/v1/mail-templates/placeholders?kind=...`: `COOKING_OVERVIEW`
  bekommt dieselbe Platzhalter-Gruppe wie `GENERAL` (Personen-Felder aus
  `SCALAR_PERSON_FIELD_ALLOWLIST`) — keine `duty.*`-Tokens, da der
  Kochdienst-Inhalt über den separaten Mail-Baustein eingefügt wird, nicht
  über Platzhalter. `COOKING_REMINDER` verhält sich unverändert wie bisher
  `COOKING`.

### Mail-Einstellungen — Chip-Differenzierung

Vorlagen- und Jobs-Tab in Mail-Einstellungen zeigen für beide `COOKING_*`-Kinds
je einen eigenen, unterscheidbaren Chip statt eines generischen „Kochdienst"-
Chips:

- `COOKING_REMINDER` → Chip „Kochdienst-Erinnerung", Tooltip verweist auf
  Organisation → Dienst-Einstellungen → Reiter „Erinnerungen".
- `COOKING_OVERVIEW` → Chip „Kochdienst-Übersicht", Tooltip verweist auf
  Organisation → Dienst-Einstellungen → Reiter „Übersichtsjobs".

Auswahl zum Bearbeiten/Löschen bleibt für beide Kinds über den allgemeinen
Endpoint gesperrt, wie bisher für `COOKING`.

## Frontend

### `CookingOverviewJobsComponent` (neu)

`frontend/src/app/settings/organisation/cooking-overview-jobs/`. Master-Detail
wie `MailJobEditorComponent`, aber mit `MailTemplateFormComponent`
(`kind=COOKING_OVERVIEW`) statt Vorlagen-Dropdown — analog zum Aufbau von
`CookingReminderJobsComponent`.

- **Links**: Liste der Übersichtsjobs mit Name, Aktiv-Schalter,
  Löschen-Button, darüber „Neuer Übersichtsjob".
- **Rechts**: Formular mit Name, Mailkonto, Betreff, `app-cron-schedule-
  builder` (Cron), Empfänger-Auswahl (Alle Eltern / Gruppen, Elternteams,
  Vorstand-Teams und -Rollen — 1:1 aus `MailJobEditorComponent` übernommen),
  Aktiv-Schalter, darunter eingebettet `MailTemplateFormComponent`. Ein
  gemeinsamer Speichern-Button sendet Job und Vorlage an
  `/cooking-overview-jobs`.

### `CookingOverviewJobService` (neu)

`list/create/update/delete` gegen `/cooking-overview-jobs`, analog
`CookingReminderJobService`.

### Neue Models

`CookingOverviewJob`, `SaveCookingOverviewJobRequest`:

```ts
interface CookingOverviewJob {
  id: string;
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateId: string;
  templateName: string;
  templateBodyHtml: string;
}

interface SaveCookingOverviewJobRequest {
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateName: string;
  templateBodyHtml: string;
}
```

### `CookingJobsComponent` (neu)

Dünner Wrapper mit `mat-tab-group`, zwei Reitern „Erinnerungen" und
„Übersichtsjobs", die `<app-cooking-reminder-jobs>` bzw.
`<app-cooking-overview-jobs>` hosten. Enthält keine eigene Logik.

### `organisation.component.html`

```
<h3>Kochdienst — Erinnerungen</h3>
<app-cooking-reminder-jobs></app-cooking-reminder-jobs>
```

wird ersetzt durch

```
<h3>Kochdienst</h3>
<app-cooking-jobs></app-cooking-jobs>
```

Die Unterüberschrift „Erinnerungen" entfällt, da die beiden Reiter selbst
benennen.

## Tests

- **Regressionsschutz**: bestehende Specs von `CookingReminderJobsComponent`,
  `MailJobEditorComponent`, `MailTemplateEditorComponent`,
  `MailTemplateResource`, `MailJobResource` bleiben unverändert grün — Beweis,
  dass Rename und neue Endpoints nichts verschoben haben.
- **Neu Backend**: CRUD auf `/cooking-overview-jobs` inklusive Rollback bei
  fehlgeschlagenem Job-Insert; Validierung (Cron-Format, Konto-Existenz,
  `active`-Gate); `MailJobScheduler` überspringt nur noch
  `COOKING_REMINDER`-Jobs und plant `COOKING_OVERVIEW`-Jobs reguär über den
  GENERAL-Pfad ein; Migration `COOKING` → `COOKING_REMINDER` (inklusive
  Idempotenz bei wiederholtem Lauf); Sperrverhalten (409) für beide
  `COOKING_*`-Kinds über den allgemeinen Vorlagen-Endpoint.
- **Neu Frontend**: `CookingOverviewJobsComponent` legt an, bearbeitet,
  schaltet aktiv, löscht (inklusive Cron-Builder und Empfänger-Auswahl);
  `CookingJobsComponent` rendert beide Reiter und hostet die jeweils
  richtige Kindkomponente; Mail-Einstellungen leiten die zwei
  unterschiedlichen Chips/Tooltips clientseitig korrekt aus `kind` ab.

## Bewusst nicht enthalten

- Kein Rendering des Kochdienst-Tabellen-Inhalts in Übersichtsjob-Vorlagen —
  das liefert das separate, noch nicht gemergte Feature
  „Mail-Template-Bausteine". Dieses Design schafft nur den Job-/Vorlagen-
  Rahmen drumherum.
- Keine eigenen Empfänger-Einschränkungen für `COOKING_OVERVIEW`-Jobs
  (z.B. nur bestimmte Teams erlaubt) — Empfänger sind frei wählbar wie bei
  `GENERAL`-Jobs.
- Keine Änderung an der Erinnerungs-Logik (`COOKING_REMINDER`) selbst, nur
  Umbenennung des `kind`-Werts.
