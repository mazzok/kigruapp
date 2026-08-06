# Sichtbare Eltern-Attribute — Design

Datum: 2026-08-03
Status: entworfen, nicht implementiert

## Ziel

Admins wählen zentral aus, welche Attribute in der Eltern-Übersicht (`/eltern`)
erscheinen. Die Auswahl gilt global — unabhängig von Gruppe und Semester. Der
Katalog umfasst die bekannten Personenfelder sowie benutzerdefinierte Felder,
die an Personen gepflegt werden.

Ergänzt die Spec `2026-08-02-eltern-uebersicht-design.md`, die unter "Zugriff"
noch "kein globaler Admin-Schalter" festhielt. Diese Festlegung wird hiermit
abgelöst.

## Ausgangslage

`FieldDefinition` kennt keine Entitäts-Zuordnung: nichts im Schema unterscheidet
ein Personenfeld von einer Gruppen- oder Team-Definition. Der Code behilft sich
überall mit fest verdrahteten Allowlists (`ParentsStepComponent.ALLOWED_FIELDS`,
`PersonPropertyResolver.SCALAR_PERSON_FIELD_ALLOWLIST`,
`MailTemplateResource`). Im Dev-Bestand stehen 24 Definitionen nebeneinander:
Personenfelder, `group`, `board`, `parent-team`, `parent-team-role`,
`cookingDuty` und sechsmal `food-property`.

Daraus folgen zwei Entwurfsentscheidungen:

1. Der Katalog wird nicht aus "allen Definitionen" abgeleitet, sondern aus einer
   festen Kernliste plus den Definitionen, die tatsächlich in
   `Person.customProperties` vorkommen.
2. Benutzerdefinierte Felder werden über die `definitionId` adressiert, nicht
   über `fieldName` — `food-property` existiert sechsmal, Namen sind nicht
   eindeutig.

## Attributkatalog

| Bereich | Schlüssel | Label (de) | Quelle |
|---|---|---|---|
| Kind | `childName` | Vorname | `Person.basicProperties.firstName` des Kindes — Zeilenanker, nicht abwählbar |
| Kind | `childEntryDate` | Eintritt | `semester_assignments.entryDate`, `section=group`, laufendes Semester |
| Kind | `childExitDate` | Austritt | `semester_assignments.exitDate`, `section=group`, laufendes Semester |
| Eltern | `firstName` | Vorname | `Person.basicProperties` über `PersonPropertyResolver` |
| Eltern | `lastName` | Nachname | dito |
| Eltern | `email` | E-Mail | dito |
| Eltern | `phone` | Telefon | dito |
| Eltern | `team` | Team | `semester_assignments`, `section=team`, Label der `field_instance` |
| Eltern | `role` | Rolle | `semester_assignments`, `section=role`, Label der `field_instance` |
| Eltern | `custom:<definitionId>` | Label der Definition | `Person.customProperties` |
| Familie | `address` | Adresse | `Family.address`, Format "Strasse, PLZ Ort" wie bisher |

Ein- und Austrittsdatum stammen bewusst aus der Gruppen-Zuweisung, nicht aus
gleichnamigen Personenfeldern: die Zuweisung trägt die gepflegten Werte, die
Personenfelder sind im Bestand leer (siehe "Altlast").

Team und Rolle sind semesterabhängige Daten. Die Konfiguration bleibt global;
angezeigt werden die Werte des laufenden Semesters — dieselbe Logik, nach der
schon die Gruppenzugehörigkeit bestimmt wird.

## Speicherung

Neue Collection `parent_directory_settings` mit genau einem Dokument, Muster wie
`AliquotConfig`:

```java
@MongoEntity(collection = "parent_directory_settings")
public class ParentDirectorySettings extends PanacheMongoEntity {
    public List<String> visibleAttributes = new ArrayList<>();
}
```

Fehlt das Dokument, gilt als Vorgabe das heutige Verhalten:
`childName`, `firstName`, `lastName`, `email`, `phone`, `address`.
Kein Migrationsschritt nötig — der Standard steht im Code.

## API

### `GET /api/v1/parent-directory/attributes` (admin)

Liefert den Katalog, bei jedem Aufruf frisch gebaut, damit neu angelegte Custom
Fields ohne Neustart erscheinen:

```json
{
  "attributes": [
    { "key": "childName", "label": "Vorname", "scope": "CHILD", "selected": true, "locked": true },
    { "key": "email", "label": "E-Mail", "scope": "PARENT", "selected": true, "locked": false }
  ]
}
```

`locked` markiert `childName` als nicht abwählbar.

### `PUT /api/v1/parent-directory/attributes` (admin)

Rumpf `{ "visibleAttributes": ["childName", "firstName", "email"] }`.

- Unbekannter Schlüssel → `400` mit Nennung des Schlüssels.
- `childName` wird serverseitig erzwungen, auch wenn der Aufrufer ihn weglässt.
- Reihenfolge im Rumpf ist bedeutungslos; die Anzeigereihenfolge ergibt sich aus
  der Katalogreihenfolge oben.

### `GET /api/v1/parent-directory` (alle angemeldeten Eltern)

Unverändert erreichbar, Antwort erweitert:

```json
{
  "semesterId": "...",
  "columns": [
    { "key": "childName", "label": "Vorname", "scope": "CHILD" }
  ],
  "groups": [
    {
      "groupInstanceId": "...",
      "groupName": "Käfergruppe",
      "families": [
        {
          "familyId": "...",
          "isOwnFamily": true,
          "children": [{ "name": "Lena", "entryDate": "2026-09-01", "exitDate": null }],
          "parents": [{ "values": { "firstName": "Anna", "email": "anna@x.at" } }],
          "address": "Hauptstrasse 1, 1010 Wien"
        }
      ]
    }
  ]
}
```

Abgewählte Attribute fehlen in `values` und in `columns`; `address` ist `null`,
wenn abgewählt. Nicht freigegebene Werte verlassen den Server nicht — das ist der
Grund, warum nicht einfach das Frontend ausblendet.

## DTO-Änderungen

`ParentDirectoryDTO`:

- neu: `List<ColumnEntry> columns` am Wurzel-DTO (nicht je Gruppe — die Auswahl
  ist global)
- `FamilyEntry.children`: `List<String>` → `List<ChildEntry>` mit `name`,
  `entryDate`, `exitDate`
- `ParentEntry`: die vier festen Felder weichen einer `Map<String, String> values`
- `FamilyEntry.address` bleibt, wird `null` bei Abwahl

## Aufräumen: Label-Auflösung

Die Logik "Anzeigename aus `field_instances.value`" (`value.label`, sonst
skalarer Wert, sonst Label bzw. `fieldName` der Definition) steht bereits in
`HourEntryResource.labelFromValue` und `ParentDirectoryService.resolveGroupNames`
und bekäme für Team und Rolle eine dritte Kopie. Sie wandert in einen
`FieldInstanceLabelResolver` mit Batch-Auflösung; alle drei Aufrufer nutzen ihn.

## Altlast: `entryDate` / `exitDate` als Personenfelder

Beide Definitionen werden von `FieldDefinitionSeedMigration` angelegt, stehen in
zwei Allowlists — und sind im Bestand bei keiner einzigen Person befüllt. Der
Familien-Wizard schreibt Ein- und Austritt seit jeher über
`PersonService.setEnrollmentDates` in die Semester-Zuweisung. Sie verschwinden
daher als Personen-Attribute:

- raus aus `PersonPropertyResolver.SCALAR_PERSON_FIELD_ALLOWLIST`
- raus aus der Platzhalter-Allowlist in `MailTemplateResource`
- raus aus der Token-Liste in `mail-token.util.ts`
- `FieldDefinitionSeedMigration` legt sie nicht mehr an
- eine Migration setzt bei bestehenden Installationen `outdatedAt` statt zu
  löschen, damit vorhandene `FieldRef`-Verweise nicht ins Leere zeigen

Gespeicherte Mail-Vorlagen mit `{{entryDate}}` rendern danach leer — sie tun es
heute schon, weil das Feld nirgends befüllt ist.

Unberührt bleiben die Ein-/Austrittsdaten der Gruppen-Zuweisung: Platzzuweisung,
`BilanzCalculationService`, `AliquotService` und `HoursBalanceService` arbeiten
weiter mit ihnen.

## Frontend

### Admin-Sektion

Neue Komponente `parent-directory-attributes`, eingebunden in
`organisation.component`, Tab *Gruppen*, unterhalb der Gruppentabelle. Eigene
Komponente, weil `organisation.component.ts` mit 582 Zeilen bereits sechs Tabs
bedient.

Aufbau: Überschrift *Sichtbare Eltern-Attribute*, ein Hinweissatz, dass die
Auswahl für alle Gruppen und Semester gilt, darunter drei Blöcke — *Kind*,
*Eltern*, *Familie* — mit je einer `mat-checkbox` pro Attribut. `childName`
erscheint als aktive, deaktivierte Checkbox, damit sichtbar bleibt, dass die
Spalte existiert und warum sie nicht abwählbar ist. Gespeichert wird per
Schaltfläche, nicht bei jedem Klick, mit `MatSnackBar`-Rückmeldung über
`NotificationService` wie in den übrigen Einstellungen.

`ParentDirectorySettingsService` kapselt die beiden Endpunkte.

### Eltern-Übersicht

`eltern.component` baut die Spalten aus `columns` der Antwort statt aus fest
verdrahtetem Markup:

- eine Spalte je freigegebenem Eltern-Attribut, Werte aus `parent.values[key]`
- die Kind-Spalte zeigt Ein- und Austritt als Zusatzzeile unter dem Namen,
  sofern freigegeben
- die Adressspalte entfällt, wenn `address` nicht in `columns` steht
- `email` und `phone` bleiben `mailto:`- bzw. `tel:`-Verweise, alle übrigen Werte
  werden als Text ausgegeben

Die farbliche Markierung der eigenen Familie (`tr.own-family`) bleibt unverändert.

## Tests

Backend:

- Katalog enthält Custom Fields, die bei einer Person gesetzt sind
- Katalog enthält keine Gruppen-, Team- oder Rollen-Definitionen
- abgewähltes Attribut fehlt in `values`, statt leer geliefert zu werden
- unbekannter Schlüssel im `PUT` ergibt 400
- `childName` bleibt gesetzt, auch wenn der Aufrufer ihn weglässt
- Nicht-Admin erhält auf beiden Einstellungs-Endpunkten 403
- Team und Rolle werden aus dem laufenden Semester aufgelöst
- `PersonPropertyResolver` liefert `entryDate`/`exitDate` nicht mehr

Frontend:

- Sektion lädt die Auswahl und speichert sie
- `childName`-Checkbox ist deaktiviert und gesetzt
- Eltern-Tabelle rendert genau die gelieferten Spalten
- Adressspalte fehlt, wenn `address` nicht geliefert wird

## Bewusst nicht enthalten

- keine Sortierung der Spalten durch den Admin — Katalogreihenfolge genügt
- keine Sichtbarkeitsregeln je Gruppe, Semester oder Rolle
- keine neuen Mail-Platzhalter für die Zuweisungs-Daten
