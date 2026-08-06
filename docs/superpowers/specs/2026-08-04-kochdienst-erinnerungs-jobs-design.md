# Kochdienst-Erinnerungen als Jobs mit eigener Vorlagen-Maske

Datum: 2026-08-04
Status: Design, freigegeben

## Ausgangslage

Die Kochdienst-Erinnerungen sind heute ein Singleton: `CookingReminderSettings`
haelt Mailkonto, Vorlagen-Id, Betreff und Versandzeit. In Organisation →
Dienst-Einstellungen steht dafuer ein Formular mit einem Dropdown auf eine
bestehende Mail-Vorlage. Die Vorlage selbst wird ausschliesslich in
Mail-Einstellungen → Vorlagen gepflegt.

Der Vorlagen-Editor (`MailTemplateEditorComponent`) ist ein Master-Detail-Panel:
links die Vorlagenliste, rechts die Maske mit Name, Platzhalter-Chips (Klick
oder Drag), Quill-Editor und Vorschau. Die Platzhalter kommen aus
`GET /api/v1/mail-templates/placeholders` und sind heute ausschliesslich
Person-Felder aus einer serverseitigen Allowlist.

Zwei Probleme:

1. Die Kochdienst-Erinnerung kann ihren Text nicht dort bearbeiten, wo sie
   konfiguriert wird, und ihre eigenen Platzhalter (`{{duty.*}}`) tauchen in der
   Maske gar nicht auf — sie stehen nur als Hinweistext unter dem Formular.
2. Es kann genau eine Erinnerungs-Konfiguration geben.

## Ziel

Kochdienst-Erinnerungen werden zu vollwertigen, mehrfach anlegbaren Jobs mit je
einer fest verdrahteten Mail-Vorlage, die direkt in den Kochdienst-Einstellungen
bearbeitet wird. Die Vorlagen-Maske wird dafuer so gekapselt, dass
Mail-Einstellungen → Vorlagen sie unveraendert weiterverwendet.

## Datenmodell

### `MailTemplate.kind` und `MailJob.kind`

Beide Entities bekommen ein Feld `kind` mit den Werten `GENERAL` (Default,
Bestandsdaten ohne Feld gelten als `GENERAL`) und `COOKING`. Kochdienst-Vorlagen
und -Jobs bleiben damit gewoehnliche Datensaetze in ihren bestehenden
Collections und erscheinen in den Uebersichten der Mail-Einstellungen.

### Kochdienst-Job

Ein `MailJob` mit `kind=COOKING` nutzt:

| Feld | Bedeutung |
|---|---|
| `name` | Anzeigename in der Liste |
| `templateId` | die fest zugeordnete Vorlage (1:1, nicht teilbar) |
| `subject` | Betreff der Erinnerungsmail |
| `senderAccountId` | Mailkonto |
| `sendTime` | neues Feld, `HH:mm`, Zeitzone Europe/Vienna |
| `active` | steuert, ob der Job Mails verschickt |

`cron`, `allParents` und `recipientSelections` bleiben bei `COOKING` leer.
Empfaenger sind immer die Erwachsenen der Familie des jeweiligen Kochdienstes,
aufgeloest wie bisher ueber `RecipientResolverService.resolveFamilyRecipients`.

`sendTime` ersetzt bei `COOKING` den Cron. Der Scheduler leitet daraus wie
bisher ueber `toCron` einen taeglichen Quartz-Trigger ab.

### `CookingReminderSettings` entfaellt

Die Collection `cooking_reminder_settings` wird nicht mehr gelesen oder
geschrieben. Eine Startup-Migration ueberfuehrt das Singleton:

- Existiert ein Singleton mit `senderAccountId` und `templateId`, entsteht
  daraus ein `MailJob` mit `kind=COOKING`, `name="Kochdienst-Erinnerung"`,
  uebernommenem Konto, Betreff und `sendTime`. `active` ist `true`, wenn die
  bisherige `isActive`-Pruefung zutraf, sonst `false`.
- Die referenzierte Vorlage wird auf `kind=COOKING` umgestellt — aber nur, wenn
  kein `GENERAL`-Job sie ebenfalls referenziert. Andernfalls wird eine Kopie
  angelegt (`name` + " (Kochdienst)"), der neue Job zeigt auf die Kopie, das
  Original bleibt unveraendert und in den Mail-Einstellungen editierbar.
- Die Migration ist idempotent: laeuft sie erneut und existiert bereits ein
  `COOKING`-Job, passiert nichts. Das Singleton bleibt als Datensatz liegen
  (nicht geloescht), damit ein Rollback moeglich bleibt.

### `CookingReminder.jobId`

Der Versand-Log bekommt `jobId`. Der Unique-Index auf `cooking_reminders`
wandert von `(dutyId, dueDate)` auf `(dutyId, dueDate, jobId)`. Ohne diese
Erweiterung wuerde der zweite aktive Job fuer denselben Kochdienst am selben
Faelligkeitstag am Duplicate-Key scheitern. Die Migration droppt den alten Index
und legt den neuen an; Bestandseintraege ohne `jobId` erhalten den Wert `null`,
was im Index zulaessig ist.

## Backend

### Vorlagen

- `GET /api/v1/mail-templates?kind=GENERAL|COOKING` filtert. Ohne Parameter
  werden weiterhin alle geliefert (Mail-Einstellungen → Vorlagen). Das
  Vorlagen-Dropdown im allgemeinen Job-Formular ruft `?kind=GENERAL`.
- `POST`/`PUT` legen ausschliesslich `GENERAL`-Vorlagen an; ein im Request
  mitgeschicktes `kind` wird ignoriert. Bestehende Vorlagen mit `kind=COOKING`
  weisen `PUT` und `DELETE` mit HTTP 409 und Begruendung ab — sie werden
  ausschliesslich ueber den Kochdienst-Job-Endpunkt gepflegt.
- Der bestehende Loeschschutz (409, solange ein `MailJob` die Vorlage
  referenziert) bleibt unveraendert.

### Platzhalter

`GET /api/v1/mail-templates/placeholders?kind=GENERAL|COOKING`

`PlaceholderTile` bekommt zwei zusaetzliche Felder: `group` (`PERSON` |
`KOCHDIENST`) und `groupLabel` (deutsche Ueberschrift). Die Allowlist bleibt
serverseitig und haengt am `kind`:

- `GENERAL` (Default, unveraendertes Verhalten): die bisherigen Person-Felder
  aus `SCALAR_PERSON_FIELD_ALLOWLIST`, Gruppe `PERSON`.
- `COOKING`: Gruppe `KOCHDIENST` mit `{{duty.date}}`, `{{duty.groups}}`,
  `{{duty.description}}`, `{{duty.daysBefore}}`, `{{duty.personName}}` sowie
  Gruppe `PERSON` eingeschraenkt auf `{{person.firstName}}` und
  `{{person.lastName}}`.

Die `KOCHDIENST`-Tokens spiegeln exakt die Keys aus
`CookingReminderScheduler.buildDutyProperties`. Ein Test haelt beide Listen
zusammen, damit ein zusaetzliches Property nicht stillschweigend ohne Chip
bleibt.

`/placeholders` bleibt admin-only.

### Kochdienst-Job-Endpunkt

Neu: `/api/v1/cooking-reminder-jobs`, admin-only (kein Eintrag im
`SecurityFilter`, damit greift Default-Deny).

- `GET` — Liste aller `COOKING`-Jobs samt eingebetteter Vorlage
  (`{ job, template }`), damit die Maske ohne zweiten Request befuellt werden
  kann.
- `POST` — legt Vorlage und Job in einem Request an. Reihenfolge: Vorlage
  persistieren, dann Job mit deren Id. Schlaegt der Job-Insert fehl, wird die
  gerade angelegte Vorlage wieder entfernt, damit keine Karteileiche entsteht.
- `PUT /{id}` — aktualisiert beides. Zeigt der Job auf keine Vorlage (theoretisch
  nur durch Fremdeingriff moeglich), wird eine angelegt.
- `DELETE /{id}` — loescht Job und zugehoerige Vorlage. Die Log-Eintraege in
  `cooking_reminders` bleiben als Historie erhalten.

Validierung wie bisher: `sendTime` muss `HH:mm` entsprechen, Betreff und
Vorlagen-Name duerfen nicht leer sein, das Mailkonto muss existieren. Ein Job
darf nur `active=true` sein, wenn das Konto existiert und `enabled` ist —
andernfalls 400 mit Begruendung. Der Vorlagen-Body durchlaeuft dieselbe
Sanitize-Pipeline wie in `MailTemplateResource` (gemeinsam genutzte Methode,
nicht kopiert).

Jede schreibende Operation ruft `cookingReminderScheduler.reschedule()`.

### Status-Endpunkt fuer den Eltern-Dialog

`GET /api/v1/cooking-reminder-settings` bleibt bestehen und fuer alle
Angemeldeten lesbar, liefert aber nur noch `{ active: boolean }`. `active` ist
`true`, wenn mindestens ein `COOKING`-Job `active` ist und dessen Konto sowie
Vorlage existieren und das Konto `enabled` ist. `PUT` entfaellt (405). Der
`SecurityFilter`-Eintrag fuer den GET bleibt unveraendert.

### Scheduler

`CookingReminderScheduler`:

- `reschedule()` hebt alle bisherigen Registrierungen auf und registriert je
  aktivem `COOKING`-Job einen Quartz-Job mit Id `cooking-reminder-<jobId>` und
  dem aus `sendTime` abgeleiteten Cron. Existiert kein aktiver Job, wird nichts
  registriert.
- `runFor(LocalDate today, MailJob job)` uebernimmt die bisherige Logik, liest
  Konto, Vorlage, Betreff aber aus dem Job. Der Ueberlappungsschutz
  (`AtomicBoolean`) wird pro Job gefuehrt, damit ein langsamer Job einen anderen
  nicht ueberspringen laesst.
- Log-Eintraege erhalten `jobId`.
- `CookingReminderStartupRearmer` ruft weiterhin `reschedule()` beim Start.

`MailJobScheduler` ueberspringt Jobs mit `kind=COOKING` — sie haben keinen Cron
und wuerden dort ins Leere laufen.

## Frontend

### Kapselung der Vorlagen-Maske

`MailTemplateEditorComponent` wird aufgeteilt:

- **`MailTemplateFormComponent`** (neu) — Name, gruppierte Platzhalter-Chips
  (Ueberschrift je Gruppe), Quill-Editor mit Drag-und-Klick-Einfuegen und
  Vorschau. Inputs: `kind` (steuert den Platzhalter-Request) und der aktuelle
  Vorlagenwert. Output: der bearbeitete Wert (`{ name, bodyHtml }` mit
  Token-Form, nicht Pill-Form). Die Komponente speichert nicht selbst — sie ist
  reine Maske. Die Token/Pill-Konvertierung und die Quill-Konfiguration bleiben
  in den bestehenden Utilities.
- **`MailTemplateEditorComponent`** (bestehend) behaelt Liste, Auswahl,
  Speichern und Loeschen und nutzt intern die neue Maske. Fuer
  Mail-Einstellungen aendert sich funktional nichts — das ist der
  Regressionsschutz.

Die bestehenden Specs von `MailTemplateEditorComponent` bleiben gruen; sie sind
der Nachweis, dass die Kapselung nichts verschoben hat.

### Mail-Einstellungen

- Vorlagen-Tab listet weiterhin alle Vorlagen. `COOKING`-Vorlagen bekommen einen
  Chip „Kochdienst"; Auswahl zum Bearbeiten und Loeschen sind dort deaktiviert,
  Tooltip verweist auf Organisation → Dienst-Einstellungen.
- Jobs-Tab listet weiterhin alle Jobs. `COOKING`-Jobs bekommen denselben Chip
  und sind dort nicht bearbeit- oder loeschbar. Das Vorlagen-Dropdown im
  Job-Formular bietet nur noch `GENERAL`-Vorlagen an.

### Kochdienst-Einstellungen

Organisation → Dienst-Einstellungen: der Abschnitt „Kochdienst — Erinnerungen"
wird durch eine neue Komponente `CookingReminderJobsComponent` ersetzt. Das
bisherige Formular (Mailkonto, Vorlagen-Dropdown, Betreff, Versandzeit) und der
Platzhalter-Hinweistext entfallen.

Aufbau als Master-Detail:

- **Links**: Liste der Erinnerungs-Jobs mit Name, Aktiv-Schalter und
  Loeschen-Button, darueber „Neuer Erinnerungs-Job".
- **Rechts**: Job-Formular mit Name, Mailkonto, Betreff, Versandzeit und
  Aktiv-Schalter, darunter eingebettet `MailTemplateFormComponent` mit
  `kind=COOKING`. Ein gemeinsamer Speichern-Button sendet beides an
  `/cooking-reminder-jobs`. Ohne Auswahl steht rechts derselbe
  Platzhalter-Hinweis wie im Vorlagen-Editor.

Es gibt keinen separaten Vorlagen-Abschnitt in den Kochdienst-Einstellungen —
die Vorlage ist Teil des Jobs.

### Kochdienst-Dialog (Teil 2)

Keine Aenderung. Die Sektion mit „Erinnerung aktivieren" und „Tage vorher"
existiert bereits (`cooking-duty-dialog.component.html`) und haengt an
`data.reminderAvailable`, das aus `/cooking-reminder-settings` `active` gespeist
wird. Sobald ein aktiver Kochdienst-Job mit gueltigem Konto existiert, erscheint
sie. Dass sie heute fehlt, ist die korrekte Anzeige einer unvollstaendigen
Konfiguration, kein Fehler.

## Verhalten bei mehreren aktiven Jobs

Jeder aktive Job verschickt fuer jeden faelligen Kochdienst seine eigene Mail zu
seiner eigenen Versandzeit. Eltern erhalten damit bewusst mehrere Mails, wenn
mehrere Jobs aktiv sind. Der Log unterscheidet die Sendungen ueber `jobId`.

Die „Tage vorher"-Angabe bleibt am einzelnen Kochdienst und gilt fuer alle Jobs
gleichermassen — sie bestimmt den Faelligkeitstag, der Job nur die Uhrzeit und
den Inhalt.

## Fehlerfaelle

| Fall | Verhalten |
|---|---|
| Konto geloescht oder deaktiviert, Job aktiv | Wie bisher: Log-Eintrag `ACCOUNT_UNAVAILABLE` mit Grund, keine Mail. `/cooking-reminder-settings` meldet `active=false`, sobald kein einziger Job mehr sendefaehig ist. |
| Vorlage fehlt (Fremdeingriff in der DB) | Log-Eintrag `ACCOUNT_UNAVAILABLE` mit Grund „Mailvorlage fehlt", wie heute. |
| Versand an einen Empfaenger scheitert | Unveraendert: pro Empfaenger gefangen, Teilerfolg wird als solcher geloggt. |
| Vorlagen-Insert erfolgreich, Job-Insert scheitert | Die Vorlage wird zurueckgerollt, der Aufrufer erhaelt den Fehler. |

## Tests

- **Regressionsschutz**: die bestehenden Specs von
  `MailTemplateEditorComponent`, `MailJobEditorComponent`,
  `MailTemplateResource` und `MailJobResource` bleiben unveraendert gruen.
- **Neu Backend**: `kind`-Filter auf `/mail-templates`; Sperre fuer
  `COOKING`-Vorlagen ueber den allgemeinen Endpunkt; `/placeholders?kind`
  liefert die richtigen Gruppen; Gleichlauf von `KOCHDIENST`-Tokens und
  `buildDutyProperties`; CRUD auf `/cooking-reminder-jobs` inklusive Rollback
  bei fehlgeschlagenem Job-Insert; Migration des Singletons in beiden Varianten
  (uebernehmen und kopieren) sowie Idempotenz; Scheduler registriert je aktivem
  Job einen Trigger und verschickt pro Kochdienst je Job eine Mail; Index-Wechsel
  auf `(dutyId, dueDate, jobId)`.
- **Neu Frontend**: `MailTemplateFormComponent` rendert Gruppen-Ueberschriften
  und liefert Token-Form nach aussen; `CookingReminderJobsComponent` legt an,
  bearbeitet, schaltet aktiv und loescht; Mail-Einstellungen zeigen den Chip und
  sperren `COOKING`-Eintraege.

## Bewusst nicht enthalten

- Kein Umbau des `MailJobScheduler` auf einen generischen Trigger-Typ. Der
  Kochdienst-Versand bleibt in seinem eigenen Scheduler.
- Keine 1:1-Bindung von Job und Vorlage fuer `GENERAL`-Jobs. Dort bleibt das
  Dropdown, mehrere Jobs duerfen sich eine Vorlage teilen.
- Kein Gruppen-Filter je Kochdienst-Job.
- Keine Aenderung an der Person-Allowlist fuer allgemeine Vorlagen.
