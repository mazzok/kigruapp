# Gruppenabhängige Stundensätze, Soll-Aufschlüsselung und Ring-Farbverlauf

Datum: 2026-08-03

## Ziel

Drei zusammenhängende Änderungen rund um die zu leistenden Stunden:

1. Der Stunden-Ring im Header färbt sich nach dem Erfüllungsgrad statt einer festen
   Statusfarbe — fünf Stufen von dunkelrot bis grün.
2. Die Seite „Unsere Stunden" zeigt oben, wie viele Stunden die Familie schuldet und
   wie sich diese Zahl zusammensetzt (Kinder, Gruppen, Rabatte, anteilige Monate).
3. Die zu leistenden Stunden lassen sich je Gruppe konfigurieren. Die Staffelung
   (Geschwisterrabatt) bleibt global und wird von absoluten Minuten auf Prozent umgestellt.

Punkt 3 verändert die Berechnung, Punkt 2 macht sie sichtbar, Punkt 1 hängt an denselben
Zahlen — deshalb eine gemeinsame Spec.

## Datenmodell

### `RequiredHours` (Collection `requiredHours`, ein Dokument je Semester)

| Feld | Typ | Bedeutung |
|---|---|---|
| `semesterId` | ObjectId | wie bisher |
| `defaultMinutesPerMonth` | int | Satz je Kind und Monat, gilt bei `allGroups = true` |
| `allGroups` | boolean | `true` (Default): ein Satz für alle Gruppen. `false`: Satz je Gruppe |
| `groupRates` | `[{ groupInstanceId: ObjectId, minutesPerMonth: int }]` | nur bei `allGroups = false` ausgewertet |
| `tiers` | `[{ fromChild: int, percent: int }]` | Geschwisterrabatt in Prozent, global |
| `order` | String | `MOST_EXPENSIVE_FIRST` (Default) oder `LEAST_EXPENSIVE_FIRST` |

`groupInstanceId` verweist auf die `fieldInstanceId` der Gruppe, wie sie auch in
`semester_assignments` mit `section = "group"` steht.

Die Struktur spiegelt bewusst `KostenDiscount` (dort bereits `applyToAll`, `order`,
Prozent-Staffeln), damit Stunden- und Kostenrabatte gleich zu lesen sind.

### Migration

Eine Mongo-Migration in der Art der bestehenden Migrationsklassen wandelt jedes
`requiredHours`-Dokument um:

- Für jede Staffel: `percent = round(100 − 100 × minutesPerMonth / defaultMinutesPerMonth)`,
  auf 0–100 geklemmt. Ist `defaultMinutesPerMonth` gleich 0, wird `percent = 0`.
  Beispiel: 6:00 bei Default 8:00 → 25 %.
- `minutesPerMonth` in den Staffeln entfällt.
- `allGroups = true`, `order = "MOST_EXPENSIVE_FIRST"`, `groupRates = []`.

Die Rundung auf ganze Prozent kann das Soll um wenige Minuten pro Monat verschieben
(5:00 von 8:00 = 62,5 % → 63 % Rabattbasis). Das ist bewusst in Kauf genommen;
Nachkommastellen im Prozentwert wären die Alternative und werden nicht umgesetzt.

`SemesterResource` kopiert beim Anlegen eines Folgesemesters die neuen Felder mit.

## Berechnung

`HoursBalanceService` rechnet monatsweise, weil sowohl die Aliquotierung als auch der
Rang eines Kindes im Semesterverlauf wechseln können.

`ChildPlacement` bekommt ein zusätzliches Feld `groupInstanceId`.

Für jeden Kalendermonat des Semesters:

1. **Basissatz je Kind**: bei `allGroups = true` der `defaultMinutesPerMonth`, sonst der
   Wert aus `groupRates` für die Gruppe des Kindes. Ist für die Gruppe kein Wert
   hinterlegt, gilt 0 (die Oberfläche verhindert diesen Zustand, siehe unten).
   Hat ein Kind Zuweisungen zu mehreren Gruppen, gilt der höchste Satz.
2. **Aliquotierung**: `AliquotService.monthFraction` liefert wie bisher den Monatsanteil
   aus Eintritts- und Austrittsdatum; anwesend ist ein Kind bei Anteil > 0.
   `AliquotMode.NONE` ergibt weiterhin den vollen Anteil für jeden Monat des Semesters.
   Aliquotierter Basisbetrag = `basissatz × anteil`.
3. **Rang**: Die anwesenden Kinder werden nach dem aliquotierten Basisbetrag sortiert —
   absteigend bei `MOST_EXPENSIVE_FIRST`, aufsteigend bei `LEAST_EXPENSIVE_FIRST`.
   Gleichstand wird nach `childId` aufgelöst. Der Rang ist 1-basiert.
4. **Rabatt**: die Staffel mit dem höchsten `fromChild ≤ rang` gewinnt, sonst 0 %.
   `minuten = round(aliquotierterBasisbetrag × (100 − percent) / 100)`, kaufmännisch gerundet.
5. **Monats-Soll** = Summe über alle anwesenden Kinder.

Semester-Soll = Summe der Monats-Solls. Das bisherige `familyMonthlyMinutes(cfg, childCount)`
(gruppenblind, ohne Platzierungen) entfällt als Berechnungsweg; alle Aufrufer — `/hours/our`,
die Admin-Stundenübersicht in `HourEntryResource` und daraus abgeleitet der Ring — nutzen die
monatsweise Rechnung. Wo eine einzelne Monatszahl angezeigt wird, ist es der Wert eines
Monats mit voller Anwesenheit aller Kinder; gibt es keinen solchen Monat, entfällt die Angabe.

Kinder ohne Gruppenplatz im Semester zählen wie bisher nicht mit.

## API

### `GET /hours/our`

Bestehende Felder bleiben. Neu:

```jsonc
{
  "allGroups": false,
  "children": [
    {
      "childId": "...",
      "name": "Lena",
      "groupLabel": "Käfergruppe",
      "groupColor": "#43a047",
      "baseMinutesPerMonth": 480,
      "entryDate": null,          // null = ganzes Semester
      "exitDate": null,
      "sollMinutes": 3840          // Semestersumme dieses Kindes
    }
  ],
  "months": [
    {
      "month": "2026-11",
      "sollMinutes": 593,
      "istMinutes": 450,
      "children": [
        { "childId": "...", "minutes": 480, "fractionPercent": 100, "discountPercent": 0 },
        { "childId": "...", "minutes": 113, "fractionPercent": 50,  "discountPercent": 25 }
      ]
    }
  ]
}
```

`familyMonthlyMinutes` bleibt im DTO erhalten und trägt den Monatswert bei voller
Anwesenheit; ist ein solcher Monat nicht vorhanden, steht dort 0 und die Oberfläche
blendet die Angabe aus. Die Summe über `children[].sollMinutes` entspricht `sollMinutes`.

`groupLabel` und `groupColor` stammen aus der Feldinstanz der Gruppe. Bei `allGroups = true`
werden sie weiterhin geliefert, die Oberfläche zeigt die Spalte aber nicht.

### `GET/PUT /required-hours`

Das DTO bekommt `allGroups`, `groupRates`, `order`; `tiers[].minutesPerMonth` wird zu
`tiers[].percent`. Beim Speichern gilt:

- `percent` muss zwischen 0 und 100 liegen, `fromChild ≥ 2`.
- Bei `allGroups = false` muss für jede Gruppe des Semesters ein `minutesPerMonth > 0`
  vorliegen; fehlt einer, antwortet der Server mit 400 und nennt die Gruppe.
- Bei `allGroups = true` wird `groupRates` ignoriert, aber nicht gelöscht, damit ein
  versehentliches Umschalten die Eingaben nicht vernichtet.

## Oberfläche

### Stunden-Ring im Header

Neue Kennzahl im `RingState`: `fulfillmentPercent = round(ist / sollBisHeute × 100)`,
bei 100 gekappt. Ist `sollBisHeute = 0` (Semester noch nicht angelaufen), gilt 100 %.

Farbstufen, angewendet auf den Ringbogen:

| Erfüllungsgrad | Farbe |
|---|---|
| unter 20 % | dunkelrot |
| 20 bis unter 40 % | rotorange |
| 40 bis unter 60 % | orange |
| 60 bis unter 80 % | gelb |
| ab 80 % | grün |

Die Ringfüllung (`ringPercent`) und der Prozenttext in der Mitte bleiben der
Gesamtfortschritt gegenüber dem Semester-Soll — Farbe und Füllung messen also bewusst
Verschiedenes. Der Tooltip erklärt das mit einer zusätzlichen Zeile, etwa:
„Farbe: 74 % des bis heute Fälligen geleistet — ab 80 % grün, darunter gelb bis rot."

`RingStatus` bleibt für die Textzeile („im Plan", „Rückstand") erhalten, steuert aber
keine Farbe mehr. Die `status-*`-Klassen im Stylesheet werden durch `level-*`-Klassen der
fünf Stufen ersetzt.

### Seite „Unsere Stunden"

Aufbau von oben nach unten:

1. **Kennzahlen-Leiste**: Soll Semester (mit Monatszahl), Geleistet (mit Prozent),
   Bilanz (Vorsprung/Rückstand, grün/rot), Ø geleistet je Monat (mit benötigtem Wert).
2. **Fortschrittsbalken** in der Farbstufe des Rings.
3. **Karte „Zusammensetzung"** mit Kopfzeile „2 Kinder · 11:45 h/Monat · 8 Monate · 96:00 h"
   (der Monatswert entfällt, wenn es keinen Monat mit voller Anwesenheit gibt) und einer
   Tabelle je Kind: Kind · Gruppe · Satz/Monat · Summe. Ein abweichender Zeitraum steht als
   Zusatz beim Namen („ab 15.11."). Aufklappbar darunter der Monatsverlauf: je Zeile ein
   Monat oder eine Spanne zusammengefasster Monate mit identischen Werten („Dez – Apr"),
   Spalten je Kind mit Minuten sowie Anteil und Rabatt als Zusatz, rechts das Monats-Soll.
   Die Zusammenfassung gleicher Monate passiert im Frontend.
4. **Einträge** als flache Tabelle: Datum · Tätigkeit · Person · Dauer · Aktionen,
   absteigend nach Datum, mit Monatsfilter. Die Monatsblöcke der bisherigen Seite entfallen.
5. **Anlegen und Bearbeiten** in einem Dialog statt im Formular neben der Liste.

Leerzustand: Ist kein Soll konfiguriert (`sollMinutes = 0`), entfallen Kennzahlen-Leiste,
Balken und Zusammensetzung; es bleibt die Eintragstabelle mit einem Hinweis, dass für dieses
Semester keine Stunden hinterlegt sind.

Die heutige `stunden.component.ts` bündelt Liste, Formular und Monatsblöcke in einer Datei.
Mit dem neuen Kopfbereich wird sie aufgeteilt:

- `stunden.component` — Seitengerüst, lädt die Daten über den bestehenden `HoursSummaryService`
- `hours-breakdown.component` — Kennzahlen, Balken, Zusammensetzung, Monatsverlauf
- `hours-entries.component` — Eintragstabelle mit Filter
- `hours-entry-dialog.component` — Formular zum Anlegen und Bearbeiten

Die Monatszusammenfassung liegt als reine Funktion in `hours-breakdown.util.ts`, damit sie
ohne Komponente testbar ist.

### Organisation → „Zu leistende Stunden"

- Checkbox „Für alle Gruppen dieselben Stunden", gesetzt als Default.
- Gesetzt: das bisherige Feld „Stunden pro Monat pro Kind (HH:mm)".
- Nicht gesetzt: je Gruppe des Semesters ein Pflichtfeld (HH:mm), beim Umschalten mit dem
  bisherigen Default vorbefüllt. Ein leeres oder ungültiges Feld wird rot markiert und
  blockiert das Speichern; die bestehende Auto-Speicherung bei `change` läuft dann nicht.
  Das globale Default-Feld wird ausgeblendet.
- Staffelung: Überschrift „Staffelung (Geschwisterrabatt) — gilt für alle Gruppen",
  je Staffel „ab Kind" und „Rabatt %", darüber ein Dropdown „Reihenfolge" mit
  „Teuerste Gruppe zuerst" / „Günstigste Gruppe zuerst".
- Vorschau: bei einheitlichem Satz wie bisher Kinderzahl → Stunden/Monat. Bei
  gruppenabhängigen Sätzen zeigt sie Beispielkombinationen (ein Kind je konfigurierter
  Gruppe, dann Kombinationen aus den beiden teuersten Gruppen) mit dem jeweiligen
  Monatswert und der Aufschlüsselung in Klammern.

## Tests

**Backend**

- `HoursBalanceServiceTest`: einheitlicher Satz ergibt unveränderte Werte (Regressionsschutz);
  unterschiedliche Gruppensätze; beide Reihenfolgen; unterjähriger Eintritt und Austritt
  kombiniert mit wechselndem Rang; Kind ohne konfigurierten Gruppensatz; Kind in zwei Gruppen.
- Migrationstest: absolute Staffel → Prozent, Default 0, Staffelwert über dem Default.
- `HourEntryResourceTest`: `/hours/our` liefert `children` und `months[].children`;
  Summe über `children[].sollMinutes` gleich `sollMinutes`; Summe über `months[].children[].minutes`
  gleich `months[].sollMinutes`.
- `RequiredHoursResourceTest`: Speichern mit und ohne `allGroups`; 400 bei fehlendem Gruppenwert;
  400 bei `percent` außerhalb 0–100.
- `SemesterResourceTest`: die neuen Felder werden ins Folgesemester kopiert.

**Frontend**

- `hours-ring.util.spec.ts`: Farbstufen an den Grenzen 0, 19, 20, 79, 80, 100 %;
  `sollBisHeute = 0` ergibt die grüne Stufe; Tooltip enthält die Erklärzeile.
- `hours-breakdown.util.spec.ts`: Zusammenfassung gleicher Monate, Spanne über Jahreswechsel,
  einzelner Monat bleibt einzeln.
- Spec für `hours-breakdown.component`: Kinderzeilen, Aliquot-Zusatz beim Namen, Leerzustand
  ohne Soll, ausgeblendete Gruppenspalte bei `allGroups = true`.
- `required-hours-preview.util.spec.ts`: auf Prozent-Staffeln umgestellt, Beispielkombinationen
  bei gruppenabhängigen Sätzen.
- Organisation-Spec: Umschalten der Checkbox befüllt die Gruppenfelder, leeres Feld blockiert
  das Speichern.

## Abgrenzung

Kosten- und Bilanzrabatte (`KostenDiscount`, `BilanzCalculationService`) bleiben unverändert.
Die Admin-Stundenübersicht übernimmt die korrigierten Zahlen, ihr Layout bleibt wie es ist.
Eine Anzeige der Gruppensätze außerhalb von Organisation und „Unsere Stunden" ist nicht
vorgesehen.
