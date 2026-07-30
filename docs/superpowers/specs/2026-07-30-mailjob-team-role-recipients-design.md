# Mailjob-Empfänger: Teams und Rollen

Datum: 2026-07-30
Status: Entwurf zur Review

## Problem

Ein Mailjob kann heute nur an *alle Eltern* oder an *ausgewählte Gruppen* gehen
(`MailJob.recipientMode` ∈ {`ALL_PARENTS`, `GROUPS`}). Elternteams, der Vorstand,
Team-Rollen und Vorstandsrollen sind nicht adressierbar, obwohl sie im
Datenmodell bereits als Semester-Zuweisungen existieren. Zusätzlich schließen
sich die beiden heutigen Modi gegenseitig aus — „Gruppe A plus Vorstand"
erfordert zwei Jobs.

## Ziel

Ein Mailjob adressiert eine beliebige Kombination aus Gruppen, Elternteams,
Vorstand, Team-Rollen und Vorstandsrollen. Die Empfängermengen werden vereinigt
und dedupliziert. „Alle Eltern" bleibt als Schalter erhalten.

## Ausgangslage im Code

Alle Zuweisungen liegen in `semester_assignments` und unterscheiden sich nur im
Feld `section`:

| Topf | Org-Tag | Definition (`fieldName`) | `section` | zugewiesene Person |
|---|---|---|---|---|
| Gruppen | `groups` | `group` | `group` | Kind |
| Elternteams | `parent-teams` | `parent-team` | `team` | Elternteil |
| Vorstand | `board` | `board` | `team` | Elternteil |
| Team-Rollen | `parent-team-roles` | `parent-team-role` | `role` | Elternteil |
| Vorstandsrollen | `board-roles` | `board-role` | `role` | Elternteil |

Jeder Topf hat genau eine aktive Template-Definition; die einzelnen Teams bzw.
Rollen sind deren Field-Instances. Vorstand und Elternteams sind für die
Auflösung nicht unterscheidbar, Vorstands- und Team-Rollen ebenso wenig. Es
braucht daher nur zwei neue Empfängerarten, nicht vier.

Der entscheidende Unterschied zu Gruppen: Gruppen hängen an *Kindern* und müssen
über Kind → Familie → Eltern aufgelöst werden (`RecipientResolverService
.resolveGroupParents`). Teams und Rollen hängen direkt an Eltern-Personen.

## Datenmodell

```java
public enum RecipientKind { GROUP, TEAM, ROLE }

public class RecipientSelection {
    public RecipientKind kind;
    public ObjectId fieldInstanceId;
}

@MongoEntity(collection = "mail_jobs")
public class MailJob extends PanacheMongoEntity {
    // ...
    public boolean allParents = false;
    public List<RecipientSelection> recipientSelections = new ArrayList<>();
    // entfallen: recipientMode, recipientGroupDefinitionIds
}
```

`RecipientMode` wird gelöscht.

Anmerkung zur Benennung: das alte Feld hieß `recipientGroupDefinitionIds`, hielt
aber Instance-IDs. `fieldInstanceId` benennt den Inhalt korrekt.

### Regeln

- `allParents == true` → alle übrigen Selektionen werden ignoriert; sie sind eine
  Teilmenge. Die UI blendet die Auswahl in diesem Fall aus.
- `allParents == false` und `recipientSelections` leer → der Job läuft, verschickt
  nichts und wird als erfolgreicher Lauf ohne Empfänger protokolliert. Kein
  Fehlerzustand.
- Selektionen, deren Field-Instance nicht mehr existiert oder im gewählten
  Semester niemandem zugewiesen ist, liefern eine leere Menge und werden still
  übersprungen.

## Resolver

`RecipientResolverService.resolve(job, semesterId)`:

1. Bei `job.allParents` → `resolveAllParents()`.
2. Sonst `job.recipientSelections` nach `kind` bündeln.
3. `GROUP` → bestehendes `resolveGroupParents(instanceIds, semesterId)`,
   unverändert.
4. `TEAM` / `ROLE` → neue Methode
   `resolveAssignedParents(String section, List<ObjectId> instanceIds, ObjectId semesterId)`.
   Sie liest `semester_assignments` gefiltert auf `section`, `semesterId` und
   `fieldInstanceId ∈ instanceIds`, sammelt die `personId`s und filtert über die
   vorhandenen Helfer `isParent` und `hasNonBlankEmail`. Ein Aufruf mit
   `"team"`, einer mit `"role"`.
5. Vereinigung über eine `LinkedHashMap<ObjectId, Person>` — dedupliziert nach
   Person-ID, Reihenfolge stabil.
6. Property-Auflösung und E-Mail-Extraktion bleiben unverändert.

`resolveAssignedParents` filtert bewusst auf `isParent`, obwohl Team- und
Rollenzuweisungen fachlich ohnehin nur Eltern treffen — der Filter kostet nichts
und hält die Garantie „ein Mailjob erreicht nur Eltern" an einer Stelle.

## Migration

`MailJobRecipientSelectionMigration`, aufgebaut wie die bestehenden
Seed-Migrationen (`@Startup`, Eintrag in `migrations` mit ID
`mailjob-recipient-selections-v1`, Wiedereintritt ist damit ausgeschlossen).

Pro Dokument in `mail_jobs`:

- `recipientMode == "ALL_PARENTS"` → `allParents: true`, `recipientSelections: []`
- `recipientMode == "GROUPS"` → `allParents: false`, `recipientSelections` aus
  `recipientGroupDefinitionIds`, jeder Eintrag `{ kind: "GROUP", fieldInstanceId: <id> }`
- fehlender oder unbekannter `recipientMode` → wie `ALL_PARENTS` (entspricht dem
  bisherigen Feld-Default)

Abschließend `$unset` auf `recipientMode` und `recipientGroupDefinitionIds`.

## API

`MailJobResource` gibt die neuen Felder unverändert durch. Die Validierung beim
Anlegen und Ändern prüft: `kind` ist ein gültiger Enum-Wert und
`fieldInstanceId` ist eine parsbare `ObjectId`. Ob die Instance existiert, wird
*nicht* geprüft — Teams und Rollen können nach dem Anlegen des Jobs entfernt
werden, und der Resolver behandelt diesen Fall bereits still.

## UI

Im Empfänger-Card von `mail-job-editor`:

- Checkbox **„Alle Eltern"**. Ist sie gesetzt, wird das Select ausgeblendet.
- Darunter **ein** `mat-select multiple` mit fünf `mat-optgroup`-Kopfzeilen:
  Gruppen, Elternteams, Vorstand, Team-Rollen, Vorstandsrollen.

Optionswerte werden als `"<KIND>:<fieldInstanceId>"` kodiert (z.B.
`"TEAM:66f1…"`). Die Kind-Zuordnung steckt damit im Wert, nicht in der
Gruppierung — Vorstand und Elternteams tragen beide `TEAM`, obwohl sie in
getrennten Optgroups stehen. Beim Speichern wird jeder Wert am ersten `:`
gesplittet und auf ein `RecipientSelection` abgebildet; beim Laden umgekehrt.

Die fünf Töpfe werden nach dem etablierten Muster geladen: `getByTag(<tag>)` →
aktive Definition per `fieldName` und `outdatedAt == null` → `listByDefinitionId`.
Ein Topf ohne Definition oder ohne Instances erzeugt keine Optgroup.

## Tests

Backend:

- `RecipientResolverServiceTest`: Auflösung über `TEAM`; über `ROLE`; Union aus
  `GROUP` + `TEAM` mit einem Elternteil in beiden Mengen (Dedup); `allParents`
  überstimmt vorhandene Selektionen; leere Selektion liefert leere Liste;
  Selektion auf nicht existierende Instance liefert leere Liste.
- `MailJobResourceTest`: Roundtrip mit gemischten Selektionen; Ablehnung eines
  ungültigen `kind`; Ablehnung einer nicht parsbaren `fieldInstanceId`.
- Neuer `MailJobRecipientSelectionMigrationTest`: Alt-Dokument je Modus rein,
  erwartete Selektionen raus, Altfelder entfernt; zweiter Lauf ist ein No-op.

Frontend:

- `mail-job-editor.component.spec.ts`: Mapping der Optionswerte auf
  `recipientSelections` in beide Richtungen; „Alle Eltern" blendet das Select aus
  und leert die Selektion nicht (Auswahl bleibt beim Abwählen erhalten);
  Optgroup ohne Instances wird nicht gerendert.

## Bewusst nicht enthalten

- Adressierung einzelner Personen oder Familien.
- Rolle-innerhalb-eines-bestimmten-Teams als kombinierte Bedingung („Teamleitung
  des Gartenteams"). Rollen wirken teamübergreifend.
- Ausschlusslisten („alle Eltern außer Team X").
