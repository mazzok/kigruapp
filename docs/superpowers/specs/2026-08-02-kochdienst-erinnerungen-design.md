# Kochdienst-Erinnerungen — Design

Datum: 2026-08-02
Status: entworfen

## Ziel

Eltern können sich vor ihrem Kochdienst automatisch per E-Mail erinnern lassen.
Beim Anlegen oder Bearbeiten eines Kochdienstes haken sie "Erinnerung aktivieren"
an und wählen, wie viele Tage vorher die Mail kommen soll. Ein täglicher
Backend-Lauf verschickt die fälligen Erinnerungen.

Voraussetzung ist, dass ein Admin in der Kochdienst-Maske ein Mailkonto und eine
Mail-Vorlage hinterlegt hat. Ohne diese Konfiguration ist die Funktion für
Eltern unsichtbar.

## Ausgangslage im Code

- Kochdienste sind generische `FieldInstance`s der Definition `cookingDuty`,
  angehängt an `Person.schedules`. Der `value` enthält heute `date`, `groups`,
  `description`, `foodProperties`.
- CRUD läuft über den generischen `FieldInstanceService` bzw. dessen Ressource,
  nicht über `CookingDutyResource` (dort existiert nur `GET`).
- Mail-Infrastruktur ist vorhanden: `MailAccount`, `MailTemplate`,
  `MailTemplateRenderer`, `MailService.sendHtml`, `MailJobScheduler`
  (programmatischer Quarkus-Scheduler, Europe/Vienna), `MailJobStartupRearmer`.
- Die Admin-Maske "Dienst-Einstellungen" liegt in `organisation.component`.
- Für Singletons existiert das Muster `LandingPage.findSingleton()`.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Mailtext | Admin wählt eine bestehende `MailTemplate` aus; gerendert über den vorhandenen Renderer |
| Empfänger | alle Eltern der Familie der eingetragenen Person |
| Vorlaufzeit | frei wählbar 1–14 Tage, vorbelegt mit 3 |
| Erinnerungstag bereits vorbei | keine Mail; der Dialog weist beim Ausfüllen sichtbar darauf hin |
| Versandzeit | in der Kochdienst-Maske einstellbar (HH:mm, Europe/Vienna) |
| Datenhaltung | Konfiguration im Kochdienst selbst, Kollektion nur als Sende-Log |

Die Datenhaltung ist der Kern: Weil `reminderEnabled` und `reminderDaysBefore`
im `value` der cookingDuty-FieldInstance stehen, erledigt sich die Forderung
"Kochdienst gelöscht oder geändert → Erinnerung gelöscht oder geändert" von
selbst. Es gibt keinen Synchronisationscode und keinen Eingriff in den
generischen Field-Instance-Pfad. Die Alternative — eine eigene
Reminder-Kollektion als Wahrheit — hätte dedizierte CRUD-Endpunkte für
Kochdienste oder einen Hook im generischen CRUD gebraucht und genau die
Sync-Fehlerklasse eingeführt, die dieser Entwurf ausschließt. Ihr einziger
Vorteil wäre eine Admin-Übersicht anstehender Erinnerungen, die niemand
gefordert hat.

## Datenmodell

### Einstellungen — Singleton, Kollektion `cooking_reminder_settings`

```
CookingReminderSettings
  senderAccountId : String   (ObjectId-Hex einer MailAccount, null = aus)
  templateId      : ObjectId (MailTemplate)
  subject         : String
  sendTime        : String   ("HH:mm", Europe/Vienna)
  updatedAt       : Instant
```

`findSingleton()` analog `LandingPage`. Es gibt kein eigenes Aktiv-Flag: Die
Funktion ist genau dann aktiv, wenn `senderAccountId` und `templateId` gesetzt
sind, das referenzierte Konto existiert und `enabled` ist.

### Erinnerung am Kochdienst

Zwei neue Felder im `value` der cookingDuty-FieldInstance:

```
reminderEnabled    : boolean
reminderDaysBefore : int (1..14)
```

Fehlen sie, gilt "keine Erinnerung". Das JSON-Schema der Definition
`cookingDuty` in `FieldDefinitionSeedMigration` wird um beide Felder erweitert —
ohne diese Anpassung lehnt `JsonSchemaValidatorService` das Speichern mit 400 ab.
Die Felder sind optional, damit bestehende Kochdienste gültig bleiben.
`CookingDutyDTO` und die `list`-Methode in `CookingDutyResource` liefern beide
Werte mit aus.

### Sende-Log — Kollektion `cooking_reminders`

```
CookingReminder
  dutyId         : ObjectId (FieldInstance des Kochdienstes)
  dueDate        : String ("yyyy-MM-dd", Tag des Versands)
  dutyDate       : String ("yyyy-MM-dd", Tag des Kochdienstes)
  sentAt         : Instant
  status         : SENT | FAILED | NO_RECIPIENTS | ACCOUNT_UNAVAILABLE
  recipientCount : int
  error          : String (null bei Erfolg)
```

Unique-Index auf (`dutyId`, `dueDate`). Das ist die Idempotenz-Garantie: Ein
zweiter Lauf am selben Tag sendet nicht erneut; verschiebt sich das Dienstdatum,
ändert sich `dueDate` und die Erinnerung darf neu feuern. Einträge zu gelöschten
Kochdiensten bleiben als Historie liegen.

## Backend

### Ressource `/api/v1/cooking-reminder-settings`

- `GET` — für alle eingeloggten Nutzer, damit der Kochdienst-Dialog weiß, ob die
  Funktion aktiv ist. Antwort enthält `senderAccountId`, `templateId`, `subject`,
  `sendTime` und ein abgeleitetes `active`.
- `PUT` — nur Admin (`@RolesAllowed` analog der übrigen Admin-Ressourcen).
  Validiert `sendTime` gegen `HH:mm` und die Referenzen auf Konto und Vorlage.
  Nach erfolgreichem Speichern wird der Cron neu geplant.

### Renderer-Erweiterung

`MailTemplateRenderer` kennt heute nur `{{person.<feld>}}`. Er bekommt eine
überladene Methode, die zusätzlich `{{duty.<feld>}}` auflöst; die bestehende
Signatur und das bestehende Verhalten bleiben unverändert. Verfügbare
Duty-Tokens:

- `duty.date` — Datum des Kochdienstes im Format `dd.MM.yyyy`
- `duty.groups` — Namen der Gruppen, kommagetrennt
- `duty.description` — Beschreibung des Kochdienstes
- `duty.daysBefore` — gewählte Vorlaufzeit in Tagen
- `duty.personName` — Name der eingetragenen Person

Für Werte, die kein Token findet, wird wie bisher der leere String eingesetzt.
Die HTML-Escaping-Regeln gelten unverändert auch für Duty-Werte.

### Täglicher Lauf — `CookingReminderScheduler`

Registriert einen programmatischen Cron aus `sendTime`, Zeitzone Europe/Vienna,
analog `MailJobScheduler`. Das Speichern der Einstellungen plant neu, ein
Startup-Rearmer registriert beim Hochfahren, analog `MailJobStartupRearmer`.
Ein In-Memory-Guard verhindert überlappende Läufe.

Ablauf pro Lauf:

1. Einstellungen laden. Ist die Funktion inaktiv, beenden ohne Logeintrag.
2. Mailkonto laden. Fehlt es oder ist es deaktiviert, für jeden fälligen
   Kochdienst einen Logeintrag `ACCOUNT_UNAVAILABLE` schreiben und beenden.
3. Alle cookingDuty-FieldInstances mit `reminderEnabled == true` laden.
4. Die behalten, für die `dutyDate − reminderDaysBefore == heute` gilt. Der
   Vergleich ist exakt; vergangene Fälligkeiten werden nicht nachgeholt.
5. Bereits geloggte Kombinationen (`dutyId`, `dueDate`) überspringen.
6. Empfänger auflösen: Familie der eingetragenen Person bestimmen, alle Eltern
   dieser Familie mit hinterlegter E-Mail-Adresse einsammeln (Auflösung der
   Personen-Properties wie in `RecipientResolverService`). Ist die Menge leer,
   Logeintrag `NO_RECIPIENTS`.
7. Template pro Empfänger rendern (Person-Tokens empfängerspezifisch,
   Duty-Tokens für alle gleich) und über `MailService.sendHtml` mit dem
   konfigurierten Betreff versenden.
8. Logeintrag schreiben: `SENT` bei vollständigem Erfolg, sonst `FAILED` mit
   der letzten Fehlermeldung und der Zahl erfolgreicher Empfänger.

Jeder Kochdienst wird einzeln behandelt; ein Fehler bei einem Dienst bricht den
Lauf nicht ab.

## Frontend

### Admin — Sektion "Kochdienst — Erinnerungen"

Im Tab *Dienst-Einstellungen* der Organisation-Ansicht, unterhalb der
Essen-Eigenschaften: Select Mailkonto (mit Option "— keine —"), Select
Mail-Vorlage, Feld Betreff, Feld Uhrzeit, Speichern-Button. Ein Hinweistext
listet die verfügbaren Tokens. Solange kein Konto gewählt ist, steht sichtbar
"Erinnerungen sind deaktiviert". Erfolg und Fehler melden die vorhandenen
Snackbars.

### Eltern — Kochdienst-Dialog

`cooking.component` lädt die Einstellungen und reicht das `active`-Flag über die
Dialog-Daten weiter. Im Dialog erscheint unter der Beschreibung eine Checkbox
"Erinnerung aktivieren", sichtbar nur bei aktiver Funktion. Ist sie angehakt,
klappt eine Untersektion auf mit:

- Zahlenfeld "Tage vorher", Bereich 1–14, vorbelegt mit 3
- Klartextzeile "Erinnerung am Mo, 12.05.2026", live berechnet aus Dienstdatum
  und Vorlaufzeit

Liegt dieses Datum in der Vergangenheit, ersetzt der Warnhinweis "Erinnerung
liegt in der Vergangenheit — es wird keine Mail versendet" die Klartextzeile.
Speichern bleibt erlaubt. Beim Bearbeiten sind gespeicherte Werte vorbelegt.
Bei fremden Kochdiensten (`canEdit == false`) sind Checkbox und Feld deaktiviert
wie der Rest des Formulars.

## Tests

Backend:

- fällig heute → Mail geht raus, Logeintrag `SENT`
- nicht fällig → kein Versand
- bereits geloggt → kein zweiter Versand
- Dienstdatum verschoben → neue `dueDate`, Erinnerung feuert erneut
- Mailkonto fehlt oder deaktiviert → kein Versand, Logeintrag
  `ACCOUNT_UNAVAILABLE`
- Familie ohne E-Mail-Adressen → Logeintrag `NO_RECIPIENTS`
- Empfängerauflösung liefert beide Elternteile der Familie
- Renderer: `duty.`-Tokens werden ersetzt, `person.`-Tokens verhalten sich
  unverändert, unbekannte Tokens werden geleert, Werte werden escaped
- Ressource: `GET` liefert `active` korrekt, `PUT` nur für Admin, ungültige
  `sendTime` wird abgelehnt
- Kochdienst mit `reminderEnabled` lässt sich speichern (Schema-Migration greift)

Frontend:

- Checkbox nur sichtbar bei aktiver Funktion
- Anhaken klappt Untersektion auf, Default 3
- Grenzen 1 und 14 werden erzwungen
- Vergangenheits-Warnung erscheint bei zu kurzem Vorlauf
- gespeicherte Werte sind beim Bearbeiten vorbelegt
- fremder Kochdienst: Bedienelemente deaktiviert
- Admin-Sektion lädt Einstellungen, speichert, zeigt deaktivierten Zustand

## Bewusst nicht enthalten

Übersicht anstehender Erinnerungen für Admins, mehrere Erinnerungen pro
Kochdienst, Push- oder SMS-Benachrichtigung, automatische Wiederholung nach
fehlgeschlagenem Versand, Abmeldelink in der Mail.
