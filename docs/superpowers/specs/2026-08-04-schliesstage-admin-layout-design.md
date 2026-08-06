# Schließtage-Admin: Horizontale Monatsreihe

**Datum:** 2026-08-04
**Status:** Genehmigt

## Problem

Die Admin-Maske für Schließtage (`settings/schliesstage`) stapelt alle Monate
des gewählten Semesters vertikal untereinander. Bei einem Semester mit
mehreren Monaten muss ein Admin weit nach unten scrollen, bevor er die
Zuweisungsleiste, das Definitionsformular und die Definitionstabelle sieht.
Das macht das Anlegen und Bearbeiten von Schließtage-Definitionen umständlich.

## Lösung

Die geteilte Kalender-Komponente (`shared/components/closure-calendar`)
bekommt einen neuen Layout-Modus, der die Monate horizontal statt vertikal
anordnet:

- Neues Input `layout: 'stacked' | 'row'` auf `ClosureCalendarComponent`,
  Default `'stacked'` (aktuelles Verhalten bleibt unverändert).
- Im Modus `'row'`:
  - Der Monatscontainer wechselt von `flex-wrap: wrap` zu `flex-wrap: nowrap`
    mit `overflow-x: auto`.
  - Jede Monatskachel bekommt eine feste Breite statt der variablen
    `flex: 1 1 220px`, so dimensioniert, dass bei üblicher Fensterbreite
    ca. 3–4 Monate gleichzeitig sichtbar sind. Weitere Monate sind per
    nativer horizontaler Scrollbar/Wischgeste erreichbar.
  - Keine zusätzlichen Navigationselemente (keine Pfeil-Buttons, kein
    Sticky/Fixiertes Element) — nur die native Scrollbar.
  - Kein responsiver Breakpoint: Das Layout bleibt auch auf schmalen
    Bildschirmen horizontal scrollbar (zeigt dort entsprechend weniger
    Monate gleichzeitig).

`settings/schliesstage/schliesstage.component.html` übergibt
`[layout]="'row'"` an `<app-closure-calendar>`.

Da die Monate im `row`-Modus nicht mehr die gesamte Seitenhöhe einnehmen,
rücken Zuweisungsleiste, Definitionsformular und Definitionstabelle allein
durch die DOM-Reihenfolge automatisch näher an den sichtbaren Bereich heran
— ohne weitere strukturelle Änderungen an der Admin-Maske nötig.

## Nicht betroffen

Die Eltern-Ansicht (`schliesstage/schliesstage-view.component`, Route
`/schliesstage`) setzt `layout` nicht und behält damit das bisherige
vertikale Stapel-Layout (`'stacked'`, Default).

## Betroffene Dateien

- `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`
  (neues `layout` Input)
- `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`
  (Host-Klasse abhängig von `layout`)
- `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss`
  (neue `.layout-row` Regeln)
- `frontend/src/app/settings/schliesstage/schliesstage.component.html`
  (übergibt `[layout]="'row'"`)

## Out of Scope

- Keine Änderung an der Eltern-Ansicht.
- Keine Pfeil-Buttons oder Sticky-Verhalten für die Definitionsleiste.
- Kein responsives Umschalten auf vertikales Layout bei schmalen Bildschirmen.
