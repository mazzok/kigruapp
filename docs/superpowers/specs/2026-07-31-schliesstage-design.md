# Schließtage — Design

Datum: 2026-07-31
Status: Entwurf genehmigt, Implementierungsplan ausstehend

## Ziel

Admins können Schließzeiten des Kindergartens erfassen: frei definierbare Arten von
Schließtagen (Ferien, Fortbildung, …) und deren Zuordnung zu Kalendertagen. Eltern
sehen dieselben Daten schreibgeschützt für das laufende Semester. Kochdienste können
an Schließtagen nicht mehr angelegt werden.

## Datenmodell

Zwei MongoDB-Collections, Panache-Entities nach dem Muster von `Currency`.

### `closure_definitions`

| Feld | Typ | Bedeutung |
| --- | --- | --- |
| `label` | String | Anzeigename, z.B. „Weihnachtsferien" |
| `color` | String | Hex-Farbe, z.B. `#4285f4` |
| `active` | boolean | `false` = deaktiviert |
| `createdAt` | Instant | Sortierung der Liste, neueste zuerst |

Definitionen sind generell und immer gültig; sie haben keinen Datums- und keinen
Semesterbezug. Deaktivierte Definitionen erscheinen nicht mehr in der Auswahlliste,
werden für bereits verknüpfte Zeiträume aber weiterhin mit ihrem Label und ihrer
Farbe gerendert.

### `closure_periods`

| Feld | Typ | Bedeutung |
| --- | --- | --- |
| `from` | LocalDate | erster Tag, inklusive |
| `to` | LocalDate | letzter Tag, inklusive |
| `definitionId` | ObjectId | Verweis auf `closure_definitions` |

`LocalDate` statt `Instant`: Schließtage sind Kalendertage ohne Uhrzeit. `Instant`
würde bei jedem Zonenwechsel Tagesgrenzen verschieben.

Kein Semesterfeld. Das Semester ist ausschließlich das Zeitfenster, das der Kalender
lädt und anzeigt. Dadurch können verschobene Semestergrenzen die Zeiträume nicht
inkonsistent machen, und Merge funktioniert über Semestergrenzen hinweg.

**Bekannte Einschränkung:** Tage, die in keinem Semester liegen — etwa zwischen dem
Ende des Sommersemesters und dem Beginn des Wintersemesters — sind über keinen
Kalender erreichbar und damit nicht erfassbar. Bewusst akzeptiert.

## Normalisierung

`ClosurePeriodNormalizer` ist die einzige Stelle mit Split- und Merge-Regeln. Die
Klasse arbeitet auf Listen von Zeiträumen ohne Datenbankzugriff und ist dadurch
vollständig per Unit-Test abdeckbar.

### Zuweisen

Der neue Bereich wird mit vorhandenen Zeiträumen **derselben** Definition
verschmolzen, sobald sie überlappen oder direkt angrenzen. Zeiträume anderer
Definitionen bleiben unangetastet — daraus entsteht die Mehrfachzuordnung.

Wird eine Lücke zwischen zwei Zeiträumen derselben Definition vollständig gefüllt,
entsteht ein einziger Zeitraum über alle drei Abschnitte.

### Entfernen

| Fall | Ergebnis |
| --- | --- |
| Tage mitten aus einem Zeitraum | Split in zwei Zeiträume |
| Tage am Anfang oder Ende | Update von `from` bzw. `to` |
| gesamter Zeitraum | Zeitraum wird gelöscht |

### Wochenend-Regel

Besteht die Lücke zwischen zwei Zeiträumen derselben Definition ausschließlich aus
Samstagen und Sonntagen, gelten sie als angrenzend und werden verschmolzen. Ohne
diese Regel würden zusammenhängende Ferien in einzelne Wochenpakete zerfallen.

## Backend-API

```
GET    /api/v1/closure-definitions?includeInactive=false
POST   /api/v1/closure-definitions
PUT    /api/v1/closure-definitions/{id}
POST   /api/v1/closure-definitions/{id}/revise
DELETE /api/v1/closure-definitions/{id}
GET    /api/v1/closure-periods?from=&to=
POST   /api/v1/closure-periods/apply
GET    /api/v1/holidays?from=&to=
```

`POST /closure-periods/apply` nimmt `{ days: LocalDate[], definitionId, mode:
"assign" | "remove" }` entgegen. Die Normalisierung läuft serverseitig; das Frontend
sendet die rohe Tagesauswahl und kennt die Split- und Merge-Regeln nicht.

`DELETE` auf eine Definition löscht nicht, sondern setzt `active = false`.

## Änderung einer Definition

Eine gespeicherte Definition mit verknüpften Zeiträumen wird nie überschrieben.

1. Der Admin ändert Label oder Farbe und verlässt das Feld.
2. Sind **keine** Zeiträume verknüpft, wird ohne Rückfrage normal gespeichert
   (`PUT`). Es gibt nichts, was beschädigt werden könnte.
3. Sind Zeiträume verknüpft, erscheint der Hinweis: *„ACHTUNG: die Definition wurde
   geändert, bereits verknüpfte Daten werden nicht geändert."*
   - **OK** → `POST /{id}/revise`: es entsteht eine Kopie mit den neuen Werten, das
     Original wird auf `active = false` gesetzt. Bestehende Zeiträume behalten das
     Original samt damaligem Label und damaliger Farbe. Die Kopie steht oben in der
     Liste und ist ab sofort die auswählbare Definition.
   - **Abbrechen** → das Formular wird auf den zuletzt gespeicherten Stand
     zurückgesetzt.

`PUT` auf eine Definition mit verknüpften Zeiträumen antwortet mit 409 und einer
Begründung; der Kopie-Weg führt ausschließlich über `revise`. Damit ist die Regel
auch dann durchgesetzt, wenn ein Client den Dialog umgeht.

Effektiv ist das eine Versionierung: jeder Name steht in der Auswahlliste genau
einmal, die Historie bleibt vollständig erhalten.

## Feiertage

Das Backend liefert zum abgefragten Zeitraum die Feiertage über
[Jollyday](https://github.com/focus-shift/jollyday) (`de.focus_shift`, gepflegter
Fork des eingestellten `de.jollyday`). Konfiguration einmalig beim Deploy:

```properties
kigruapp.holidays.country=AT
kigruapp.holidays.subdivision=W
```

Fehlt die Property oder ist der Wert unbekannt, bleibt die Liste leer und der
Kalender zeigt schlicht keine Feiertage — kein Fehler, kein Startabbruch.

Über `Intl` oder die Server-Locale sind Feiertage nicht ermittelbar; es gibt dafür
weder eine Browser-API noch einen Standard. Die Berechnung im Backend hält die
Feiertagsdaten aus dem Frontend-Bundle heraus und liefert Admin- und Elternansicht
dieselbe Quelle.

Feiertage sind reine Anzeige: nicht persistiert und keine Schließzeiträume. Im
Kalender sind sie nicht auswählbar — an einem gesetzlichen Feiertag ist der
Kindergarten ohnehin geschlossen, eine zusätzliche Schließtag-Zuordnung wäre
bedeutungslos. Ein gezogener Bereich überspannt Feiertage wie Wochenenden.

## Kalender-Komponente

`ClosureCalendarComponent` liegt im `shared`-Bereich und wird von Admin- und
Elternansicht geteilt.

**Inputs:** `from`, `to`, `periods`, `definitions`, `holidays`, `readonly`
**Output:** `selectionChange` mit der Liste der markierten Tage

### Darstellung

- CSS Grid, ein Monat pro Zelle, Monate umbrechend nebeneinander. Angezeigt wird
  genau der übergebene Bereich, im Regelfall Semesterbeginn bis Semesterende.
- Ein Tag mit *n* Zuordnungen wird als `linear-gradient` mit *n* gleich breiten
  Segmenten gefüllt. Kein Limit für *n*; ein Tooltip listet alle Label.
- Samstage, Sonntage und Feiertage sind ausgegraut.

### Auswahl

- Klick markiert einen Tag, Ziehen markiert einen Bereich.
- Gedrückte STRG-Taste ändert den Cursor zu `+` und schaltet einzelne Tage in der
  Auswahl um — Excel-Semantik. Die Auswahl selbst ist definitionsneutral.
- Der Key-Handler ist an die Komponente gebunden, nicht an das Dokument. STRG wirkt
  ausschließlich innerhalb des Kalenders.
- Wochenenden und Feiertage sind nicht anklickbar, ein gezogener Bereich überspannt
  sie aber. Eine Auswahl vom 14.12. bis zum 08.01. bleibt ein einziger Zeitraum.
- `readonly` schaltet Auswahl und sämtliche Handler ab.

## Admin-Maske

Neuer Tab „Schließtage" in `settings/organisation`, eingebunden als eigenständige
Kind-Komponente `<app-schliesstage>`. `organisation.component.ts` umfasst bereits
582 Zeilen; die Logik dort zu ergänzen würde die Datei endgültig unhandlich machen.

Aufbau von oben nach unten:

1. **Semester-Dropdown.** Der Kalender lädt ausschließlich beim Wechsel der
   Semesterauswahl neu.
2. **Kalender** über die volle Breite, Bereich = Semesterbeginn bis Semesterende.
3. **Zuweisungsleiste**, nur sichtbar solange eine Auswahl besteht. Sie enthält alle
   aktiven Definitionen als Checkboxliste mit drei Zuständen:
   - angehakt — gilt für alle gewählten Tage
   - leer — gilt für keinen gewählten Tag
   - unbestimmt — gilt für einen Teil der gewählten Tage

   Anhaken weist zu, Abhaken entfernt. Zuweisen und Entfernen sind damit dieselbe
   Geste, und der Admin sieht unmittelbar, was auf der Auswahl bereits liegt.
4. **Definitionstabelle**, Material-Tabelle im Stil von Währungen und
   Kosten-Definitionen: Farbfeld, Label, Status, Aktionen. Die Farbe wird über
   `<input matInput type="color">` mit Default `#4285f4` gewählt — identisch zu
   Gruppen und Teams. Deaktivierte Definitionen werden abgeschwächt dargestellt und
   lassen sich reaktivieren.

## Elternansicht

Route `/schliesstage`, geschützt nur durch `authGuard`. Der Navigationspunkt liegt
oberhalb des Administration-Blocks, neben Kochdienst und Stunden.

Die Ansicht bestimmt aus dem heutigen Datum das laufende Semester. Es gibt keinen
Selector. Liegt heute in keinem Semester, erscheint ein Hinweistext statt eines
Kalenders.

Gerendert wird dieselbe `ClosureCalendarComponent` mit `readonly`, ergänzt um eine
Legende der vorkommenden Definitionen.

## Kochdienst

- Der Datepicker im Kochdienst-Dialog sperrt alle Tage, die in einem Schließzeitraum
  liegen, sowie alle gesetzlichen Feiertage.
- `CookingDutyResource` lehnt Anlegen und Verschieben auf einen solchen Tag mit 409
  ab. Die Sperre gilt damit auch für Clients, die den Dialog umgehen.
- Feiertage werden gleich behandelt wie Schließzeiträume, obwohl sie nicht
  persistiert sind: der Kindergarten ist geschlossen, also kann dort kein Kochdienst
  stattfinden. Andernfalls wäre ein Kochdienst am 25. Dezember erlaubt, ein
  identischer Dienst am selbst eingetragenen Schließtag daneben aber nicht.
- Schließtage werden im Kochdienst-Kalender eingefärbt. Umgesetzt über die
  Zell-Anpassung von `angular-calendar`, das dort bereits im Einsatz ist.
- Wird ein Schließzeitraum nachträglich über bestehende Kochdienste gelegt, wird er
  gespeichert und die Dienste bleiben unverändert erhalten. Sie werden im
  Kochdienst-Kalender als Konflikt hervorgehoben.

**Bewusste Asymmetrie:** vorwärts wird hart verhindert, rückwirkend nur markiert.
Das Löschen fremder Diensteinträge ohne Rückfrage wäre der größere Schaden. Derselbe
Zustand ist damit je nach Reihenfolge der Eingaben erreichbar oder nicht — das ist
gewollt und kein Fehler.

## Kalender-Technologie

Die Erfassungskomponente wird selbst gebaut. Recherchiert und verworfen:

| Kandidat | Befund |
| --- | --- |
| FullCalendar (MIT) | `multiMonth` vorhanden, aber [#7183 „Multi-Month dates can't be selected across months"](https://github.com/fullcalendar/fullcalendar/issues/7183) ist seit 2023 offen. Bereichsauswahl über Monatsgrenzen — die Kernanforderung — fehlt. |
| Ignite UI for Angular | `igx-calendar` deckt Mehrmonatsansicht, Mehrfachauswahl und deaktivierte Wochenenden ab, steht aber unter einem Dual-License-Modell statt schlicht MIT und wäre eine zweite UI-Bibliothek neben Material. |
| Kendo UI, Mobiscroll, Syncfusion, DevExtreme | fachlich passend, durchweg kommerziell lizenziert. |
| `angular-calendar` (bereits im Projekt) | Ereigniskalender ohne Mehrmonatsansicht und ohne Bereichsauswahl. |

Keine Bibliothek liefert die Kombination aus Mehrmonatsansicht, Ziehen über
Monatsgrenzen, STRG-Toggle und in Segmente geteilten Tageszellen. Die geteilten
Zellen erfordern in jedem Fall eigenes Rendering.

Wiederverwendung: „Schließtage erfassen" ist ein Auswahlraster, „Kochdienst" ist ein
Ereigniskalender — in jeder Bibliothek zwei verschiedene Komponenten. Geteilt wird
deshalb die *Darstellung* der Schließtage, nicht das Erfassungs-Widget.
`angular-calendar` bleibt beim Kochdienst.

## Tests

**Backend, Schwerpunkt `ClosurePeriodNormalizer`** — reine Unit-Tests ohne DB:
Split in der Mitte, Verschiebung des Anfangs, Verschiebung des Endes, vollständiges
Entfernen, Lückenfüllung mit derselben Definition, Lückenfüllung mit fremder
Definition, Lücke aus reinen Wochenendtagen, Mehrfachzuordnung auf demselben Tag.

**Backend, Resources:** Kopie-Flow über `revise`, 409 bei `PUT` auf eine verknüpfte
Definition, `DELETE` setzt `active = false`, `apply` in beiden Modi, 409 beim
Anlegen eines Kochdiensts auf einem Schließtag.

**Frontend:** Auswahl per Ziehen, STRG-Toggle, Wochenenden nicht auswählbar,
Tri-State-Berechnung der Checkboxliste, Neuladen nur bei Semesterwechsel,
`readonly` unterdrückt jede Interaktion.

## Nicht im Umfang

- Auswirkungen auf die Stundenberechnung und die aliquote Aufteilung.
- Automatisches Entfernen oder Umbuchen kollidierender Kochdienste.
- Erfassung von Tagen außerhalb aller Semester.
- Benachrichtigung der Eltern bei neuen Schließtagen.
