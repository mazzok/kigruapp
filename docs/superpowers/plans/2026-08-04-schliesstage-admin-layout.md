# Schließtage-Admin: Horizontale Monatsreihe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Schließtage-Admin-Maske zeigt die Monate des Semesters horizontal scrollbar nebeneinander an, statt vertikal gestapelt, damit Zuweisungsleiste, Definitionsformular und Definitionstabelle ohne langes Scrollen erreichbar sind.

**Architecture:** `ClosureCalendarComponent` bekommt ein neues Input `layout: 'stacked' | 'row'` (Default `'stacked'`). Im Template wird abhängig davon eine Host-Klasse `layout-row` gesetzt, für die im SCSS eine feste Kachelbreite und `overflow-x: auto` statt `flex-wrap: wrap` gelten. Die Admin-Maske (`settings/schliesstage`) übergibt `[layout]="'row'"`. Die Eltern-Ansicht ändert sich nicht, da sie `layout` nicht setzt.

**Tech Stack:** Angular (standalone components), SCSS, Jasmine/Karma für Tests.

## Global Constraints

- Nur die Admin-Maske (`settings/schliesstage`) wechselt auf horizontales Layout; die Eltern-Ansicht (`schliesstage/schliesstage-view.component`) bleibt beim bisherigen `'stacked'`-Layout (Default).
- Bei üblicher Fensterbreite sollen ca. 3–4 Monate gleichzeitig sichtbar sein; weitere Monate sind über die native horizontale Scrollbar/Wischgeste erreichbar.
- Keine zusätzlichen Navigationselemente (keine Pfeil-Buttons) und kein Sticky-/Fixiert-Verhalten für die Definitionsleiste.
- Kein responsiver Breakpoint: Das `row`-Layout bleibt auch auf schmalen Bildschirmen horizontal scrollbar.
- Bestehendes Verhalten der Kalender-Komponente (Tagesauswahl, Tooltips, Wochenend-/Feiertagsdarstellung) darf sich nicht ändern.

---

### Task 1: `layout` Input und Host-Klasse in `ClosureCalendarComponent`

**Files:**
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html:1-5`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`

**Interfaces:**
- Produces: `ClosureCalendarComponent.layout: 'stacked' | 'row'` (Input, Default `'stacked'`), gesetzt als Host-Klasse `layout-row` auf dem Root-`<div class="closure-calendar">`, wenn `layout === 'row'`.

- [ ] **Step 1: Failing Test schreiben**

Füge am Ende der bestehenden `describe('ClosureCalendarComponent', ...)`-Suite in `closure-calendar.component.spec.ts` einen neuen `describe`-Block hinzu:

```typescript
describe('layout', () => {
  it('setzt standardmaessig kein layout-row', () => {
    const root = fixture.nativeElement.querySelector('.closure-calendar');
    expect(root.classList.contains('layout-row')).toBe(false);
  });

  it('setzt layout-row wenn layout auf row steht', () => {
    component.layout = 'row';
    fixture.detectChanges();

    const root = fixture.nativeElement.querySelector('.closure-calendar');
    expect(root.classList.contains('layout-row')).toBe(true);
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `npx ng test --watch=false --include='**/closure-calendar.component.spec.ts'`
Expected: FAIL — `Property 'layout' does not exist on type 'ClosureCalendarComponent'` (Compile-Fehler) bzw. der zweite Test schlägt fehl, weil die Klasse `layout-row` nie gesetzt wird.

- [ ] **Step 3: Minimale Implementierung**

In `closure-calendar.component.ts`, nach dem `readonly` Input (Zeile 28) ergänzen:

```typescript
  /** 'stacked' (Default, Elternansicht): Monate untereinander. 'row': Monate horizontal scrollbar (Admin-Maske). */
  @Input() layout: 'stacked' | 'row' = 'stacked';
```

In `closure-calendar.component.html`, Zeile 1-5 anpassen, um die Host-Klasse zu ergänzen:

```html
<div class="closure-calendar"
     [class.layout-row]="layout === 'row'"
     [class.ctrl-active]="ctrlActive"
     [class.readonly]="readonly"
     (mouseenter)="onPointerEnter()"
     (mouseleave)="onPointerLeave()">
```

- [ ] **Step 4: Test laufen lassen, Erfolg bestätigen**

Run: `npx ng test --watch=false --include='**/closure-calendar.component.spec.ts'`
Expected: PASS — alle Tests der Datei grün, einschließlich der beiden neuen.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts
git commit -m "feat(fe): layout-Input fuer horizontale Monatsreihe in ClosureCalendarComponent"
```

---

### Task 2: SCSS für `layout-row` (horizontale Scroll-Reihe)

**Files:**
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`

**Interfaces:**
- Consumes: Host-Klasse `.closure-calendar.layout-row` aus Task 1.
- Produces: Feste Kachelbreite `.layout-row .month` (240px, dimensioniert für ca. 3–4 gleichzeitig sichtbare Monate bei typischer Fensterbreite) und `overflow-x: auto` mit `flex-wrap: nowrap` auf `.closure-calendar.layout-row`.

- [ ] **Step 1: Failing Test schreiben**

Ergänze im `describe('layout', ...)`-Block aus Task 1 einen weiteren Test, der die inline berechneten Styles prüft (Karma/Jasmine liest `getComputedStyle` im Headless-Browser zuverlässig für `overflow-x` und `flex-wrap`):

```typescript
  it('scrollt horizontal statt zu umbrechen im row-layout', () => {
    component.layout = 'row';
    fixture.detectChanges();

    const root = fixture.nativeElement.querySelector('.closure-calendar');
    const style = getComputedStyle(root);
    expect(style.overflowX).toBe('auto');
    expect(style.flexWrap).toBe('nowrap');
  });
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `npx ng test --watch=false --include='**/closure-calendar.component.spec.ts'`
Expected: FAIL — `style.overflowX` ist `'visible'` (Default) statt `'auto'`, da die SCSS-Regel noch fehlt.

- [ ] **Step 3: Minimale Implementierung**

In `closure-calendar.component.scss`, nach dem bestehenden `.month`-Block (nach Zeile 13) ergänzen:

```scss
.closure-calendar.layout-row {
  flex-wrap: nowrap;
  overflow-x: auto;
  padding-bottom: 8px; // Platz fuer die Scrollbar, damit sie den Inhalt nicht ueberlagert.
}

.layout-row .month {
  flex: 0 0 240px;
  min-width: 240px;
}
```

- [ ] **Step 4: Test laufen lassen, Erfolg bestätigen**

Run: `npx ng test --watch=false --include='**/closure-calendar.component.spec.ts'`
Expected: PASS — alle Tests der Datei grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts
git commit -m "feat(fe): horizontale Scroll-Reihe fuer layout-row in ClosureCalendarComponent"
```

---

### Task 3: Admin-Maske auf `layout="row"` umstellen

**Files:**
- Modify: `frontend/src/app/settings/schliesstage/schliesstage.component.html:15-22`
- Test: `frontend/src/app/settings/schliesstage/schliesstage.component.spec.ts`

**Interfaces:**
- Consumes: `layout` Input aus Task 1 auf `<app-closure-calendar>`.

- [ ] **Step 1: Failing Test schreiben**

Prüfe zuerst mit `Read` den Aufbau von `schliesstage.component.spec.ts`, um das dort verwendete Test-Setup (TestBed-Imports, `fixture`) zu übernehmen. Ergänze dann am Ende der Haupt-`describe`-Suite:

```typescript
  it('uebergibt layout row an app-closure-calendar', () => {
    const calendar = fixture.debugElement.query(By.directive(ClosureCalendarComponent));
    expect(calendar.componentInstance.layout).toBe('row');
  });
```

Ergänze die nötigen Imports am Dateikopf, falls noch nicht vorhanden:

```typescript
import { By } from '@angular/platform-browser';
import { ClosureCalendarComponent } from '../../shared/components/closure-calendar/closure-calendar.component';
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `npx ng test --watch=false --include='**/schliesstage.component.spec.ts'`
Expected: FAIL — `calendar.componentInstance.layout` ist `'stacked'` (Default) statt `'row'`.

- [ ] **Step 3: Minimale Implementierung**

In `schliesstage.component.html`, Zeile 15-22 anpassen:

```html
  <app-closure-calendar
    [from]="from"
    [to]="to"
    [periods]="periods"
    [definitions]="definitions"
    [holidays]="holidays"
    layout="row"
    (selectionChange)="onSelectionChange($event)">
  </app-closure-calendar>
```

- [ ] **Step 4: Test laufen lassen, Erfolg bestätigen**

Run: `npx ng test --watch=false --include='**/schliesstage.component.spec.ts'`
Expected: PASS — alle Tests der Datei grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/schliesstage/schliesstage.component.html frontend/src/app/settings/schliesstage/schliesstage.component.spec.ts
git commit -m "feat(fe): Schliesstage-Admin-Maske nutzt horizontale Monatsreihe"
```

---

### Task 4: Gesamte Frontend-Testsuite verifizieren

**Files:**
- Keine Änderungen — nur Verifikation.

**Interfaces:**
- Keine.

- [ ] **Step 1: Vollständige Testsuite laufen lassen**

Run: `npx ng test --watch=false`
Expected: Alle Tests grün, keine neuen Fehlschläge gegenüber dem Stand vor diesem Plan (bekannte, unabhängige Vorab-Fehlschläge laut `[[project_broken_baseline]]` bleiben unverändert bestehen — falls diese Datei nicht existiert, ignoriere den Verweis und vergleiche stattdessen manuell den Testlauf-Output vor/nach diesem Plan).

- [ ] **Step 2: Manueller Smoke-Test im Browser**

Admin-Maske unter Einstellungen → Schließtage öffnen, Semester mit mehreren Monaten wählen, prüfen dass:
- Die Monate in einer horizontal scrollbaren Reihe nebeneinander stehen.
- Zuweisungsleiste, Formular und Definitionstabelle ohne großes vertikales Scrollen sichtbar sind.
- Tagesauswahl (Klick/Ziehen, STRG-Toggle) weiterhin funktioniert.
- Die Eltern-Ansicht (`/schliesstage`) weiterhin die Monate vertikal gestapelt zeigt (unverändert).

Kein Commit für diesen Task — reine Verifikation.
