# Stunden-Ring im Header — Design

Datum: 2026-07-31
Status: freigegeben (Design), Implementierung offen

## Ziel

Im App-Header, links neben dem Benutzernamen, zeigt ein kreisförmiger
Fortschrittsring auf einen Blick, wie viele Stunden die Familie im aktuellen
Semester geleistet hat und wie viele sie leisten muss. Die Farbe des Rings
sagt, ob die Familie im Plan liegt — gemessen am Soll, das bis heute
angefallen ist, nicht am Semester-Soll. Ein Tooltip erklärt die Rechnung.

## Umfang

- Neue Anzeige-Komponente im Header (nur lesend).
- Gemeinsamer Zustand, damit die Anzeige nach dem Erfassen von Stunden sofort
  aktuell ist.
- Kein Backend-Änderungsbedarf.

Nicht enthalten: Bearbeiten von Stunden im Header, Semesterauswahl im Header,
Anzeige je Familienmitglied.

## Datenquelle

`GET /api/v1/hour-entries/our?semesterId=` (leerer Parameter ⇒ Backend nimmt
das jüngste Semester) liefert bereits alles Nötige:

| Feld | Verwendung |
|---|---|
| `sollMinutes` | Semester-Soll der Familie (Ringziel) |
| `istMinutes` | geleistete Minuten der Familie (Ringfüllung) |
| `monthsInSemester` | Monatsanzahl für Ø-Werte |
| `familyMonthlyMinutes` | nicht verwendet (siehe Tooltip) |
| `months[].month` / `.sollMinutes` | Soll bis heute (aliquotiert) |
| `familyId` | `null` ⇒ Widget wird ausgeblendet |

Der Ring zeigt die Familie gesamt — dieselbe Zahl wie die Seite
„Unsere Stunden“.

## Rechenlogik

Reine Funktion in `hours-ring.util.ts` (kein Angular, direkt testbar).
Eingabe: `OurHours` + aktueller Monat als `YYYY-MM`. Ausgabe: `RingState | null`.

```
sollToDate   = Σ months[].sollMinutes für month <= heute (String-Vergleich)
elapsed      = Anzahl months[] mit month <= heute und sollMinutes > 0
delta        = istMinutes - sollToDate
totalMonths  = Anzahl months[] mit sollMinutes > 0, sonst monthsInSemester
monthlySoll  = sollMinutes / totalMonths
ringPercent  = min(100, istMinutes / sollMinutes * 100)
realPercent  = istMinutes / sollMinutes * 100      (ungekappt, für die Beschriftung)
avgDone      = elapsed > 0 ? istMinutes / elapsed : 0
```

Status (Farbe):

| Status | Bedingung |
|---|---|
| `done` / grün | `istMinutes >= sollMinutes` |
| `onTrack` / grün | `delta >= 0` |
| `slightlyBehind` / amber | `delta < 0` und `-delta < monthlySoll` |
| `behind` / rot | `-delta >= monthlySoll` |

Sonderfälle ⇒ Rückgabe `null`, Widget rendert nichts:

- `familyId == null` (z. B. Admin ohne Familie)
- `sollMinutes <= 0` (kein Soll konfiguriert; verhindert Division durch Null)
- kein Semester / Request schlägt fehl

Monatszeilen außerhalb des Semesterzeitraums (das Backend liefert solche mit
`sollMinutes = 0`) beeinflussen `sollToDate` nicht, ihr Ist zählt aber in
`istMinutes` — gewollt, damit Ring und Seite dieselbe Zahl zeigen.

## Darstellung

Position: `mat-toolbar`, links vom Benutzernamen, nur wenn `RingState != null`.

- Ring: Inline-SVG, zwei `<circle>`, Fortschritt über `stroke-dasharray` /
  `stroke-dashoffset`, runde Enden, ⌀ 40 px. Farbe des Bogens nach Status,
  Track gedämpft. Beim ersten Zeichnen animiert `stroke-dashoffset` per
  CSS-Transition von 0 % auf den Wert.
- In der Ringmitte: gerundetes `realPercent` (z. B. `112%`) — der Bogen bleibt
  bei 100 % stehen, die Zahl darf darüber gehen.
- Rechts daneben zwei Zeilen: geleistet (`12:30 h`, fett) über Soll
  (`von 30:00 h`, gedämpft).
- Das gesamte Widget ist ein Link auf `/stunden` (`routerLink`), mit
  `aria-label`, das Ist, Soll und Status in Worten nennt.

Begründung Inline-SVG statt `mat-progress-spinner` oder `conic-gradient`:
Farbe pro Wert ohne Theme-Overrides, runde Bogenenden, animierbar, keine
zusätzliche Abhängigkeit.

## Tooltip

`matTooltip` mit `white-space: pre-line`, Inhalt aus derselben Rechenlogik:

```
Soll gesamt:      30:00 h  (6 Monate × 5:00 h)
Geleistet:        12:30 h
Fällig bis heute: 15:00 h  (3 von 6 Monaten)
Rückstand:         2:30 h
Ø geleistet: 4:10 h/Monat · benötigt 5:00 h/Monat
```

Bei `delta >= 0` steht statt „Rückstand“ die Zeile „Vorsprung“. Ist das
Semester-Soll erfüllt, entfällt die Zeile und es steht „Soll erfüllt“.
Zeitformat `HH:MM h` wie auf der Seite „Unsere Stunden“.

Beide Monatswerte im Tooltip — die Klammer in „Soll gesamt“ und „benötigt
… h/Monat“ — stammen aus dem abgeleiteten `monthlySoll`
(`sollMinutes / totalMonths`), nicht aus `familyMonthlyMinutes`. `totalMonths`
zählt dabei nur die Monatszeilen, die tatsächlich Soll tragen (`sollMinutes > 0`),
und fällt nur dann auf `monthsInSemester` zurück, wenn keine Monatszeile Soll
trägt. Bei aliquotierten Semestern (unterjähriger Ein- oder Austritt) liefert
das Backend für Monate, die die Familie nicht schuldet, `sollMinutes = 0`;
`familyMonthlyMinutes × monthsInSemester ≠ sollMinutes` wäre in diesem Fall
falsch. Nur die Monate mit Soll zu zählen hält `monthlySoll`, den
Tooltip-Bruch und das Amber/Rot-Toleranzband konsistent mit dem tatsächlich
angezeigten Soll. `familyMonthlyMinutes` wird deshalb nicht verwendet.

## Komponenten und Zustand

```
shared/services/hours-summary.service.ts   BehaviorSubject<OurHours|null> + reload()
shared/components/hours-ring/
  hours-ring.util.ts                       reine Rechenlogik + Formatierung
  hours-ring.component.ts|.html|.scss      Anzeige, standalone
app.component.html                         <app-hours-ring> in der Toolbar
stunden/stunden.component.ts               nutzt den Service statt eigenem Fetch
```

`HoursSummaryService` lädt einmal (ausgelöst vom `AppComponent` nach dem Laden
des Benutzers) und hält das Ergebnis in einem `BehaviorSubject<OurHours|null>`,
nach außen als `summary$`. `reload()` holt neu und schiebt an alle Abnehmer.
Bewusst kein Angular-Signal: der bestehende Code (z. B.
`CurrentUserService`) nutzt durchgehend `BehaviorSubject`, dem folgt die neue
Komponente.

`StundenComponent` verliert seinen eigenen `our`-Fetch und liest dasselbe
`summary$`. Beim Öffnen lädt die Seite nur nach, wenn der Service noch nichts
hält (`!hoursSummary.current`) — hat der Header-Ring schon geladen, entfällt
der zweite Request. Nach Speichern und Löschen ruft die Seite `reload()`
bedingungslos auf, damit der Ring sofort nach dem Erfassen stimmt.

## Fehlerbehandlung

Schlägt der Request fehl, bleibt das Signal `null` und das Widget verschwindet
still — kein Popup im Header. Die Seite „Unsere Stunden“ zeigt ihren
bestehenden Leerzustand.

## Tests

`hours-ring.util.spec.ts`

- Soll bis heute über Monatszeilen, Grenzfall „heute ist der letzte
  Semestermonat“
- alle vier Statusstufen, inklusive genau `delta == -monthlySoll` (rot)
- `realPercent > 100` bei gekapptem `ringPercent`
- `null` bei `sollMinutes = 0`, bei `familyId = null`
- Monatszeile nach Semesterende erhöht `sollToDate` nicht
- Formatierung: `HH:MM h`, Ø-Werte

`hours-ring.component.spec.ts`

- rendert nichts, wenn der Service `null` liefert
- Statusklasse am Bogen entspricht dem berechneten Status
- Tooltip-Text enthält die erwarteten Zeilen
- `routerLink` zeigt auf `/stunden`

`hours-summary.service.spec.ts`

- lädt beim ersten Aufruf, `reload()` löst neuen Request aus, Abnehmer sehen
  den neuen Wert

`stunden.component.spec.ts` (Anpassung)

- nach Speichern/Löschen wird `reload()` gerufen
