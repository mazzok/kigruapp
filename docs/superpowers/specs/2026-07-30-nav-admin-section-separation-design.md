# Navigation: Admin-Bereich von Eltern-Bereich abtrennen

## Kontext

Die linke Navigationsleiste (`app.component.html`) zeigt aktuell für alle Nutzer die zwei Eltern-Punkte "Kochen" und "Unsere Stunden". Ist der Nutzer Admin (`currentUser.isAdmin`), werden direkt im Anschluss 11 weitere Admin-Punkte gerendert (Familien, Platzzuweisung, Kosten pro Semester, Bilanzen, Elterneinteilung, Vorstand, Stundenübersicht, danach eine `mat-divider` als optische Untergliederung, gefolgt von Organisation, Benutzerdefinierte Felder, Berechtigungen, Mail). Es gibt keine visuelle Trennung zwischen "für alle Eltern zugänglich" und "nur Admin".

## Ziel

Admin-Punkte klar und gruppiert von den für alle Eltern zugänglichen Punkten abtrennen, im Stil der bereits vorhandenen `mat-divider`-Trennlinie, mit Bezeichnung und Ausklapp-Funktion.

## Design

**Struktur der Sidenav (von oben nach unten):**

1. Kochen, Unsere Stunden — bleiben wie bisher fix sichtbar, kein eigenes Label, nicht einklappbar.
2. Eine klickbare Trennlinie/Section-Header mit Text **"Administration"** und einem Chevron-Icon, der den Auf-/Zu-Zustand anzeigt. Nur sichtbar wenn `currentUser.isAdmin`.
3. Bei ausgeklapptem Zustand: alle 11 bisherigen Admin-Punkte, in der bestehenden Reihenfolge, inklusive der bereits vorhandenen kleinen `mat-divider` zwischen den operativen Punkten (Familien … Stundenübersicht) und den Settings-Punkten (Organisation … Mail) als optische Untergliederung innerhalb der Admin-Gruppe.

**Verhalten:**

- Default-Zustand beim Laden: **ausgeklappt**.
- Klick auf die Trennlinie togglet den Zustand.
- Zustand ist reine Component-State (Boolean-Feld), **keine Persistierung** (kein localStorage) — bei jedem Neuladen/Login wieder ausgeklappt.
- Für Nicht-Admins ändert sich nichts sichtbar (kein Administration-Header, keine Admin-Punkte, wie bisher durch `@if (currentUser.isAdmin)` gesteuert).

**Visuelle Umsetzung:**

- Der "Administration"-Header nutzt denselben Trennlinien-Stil wie die bestehende `mat-divider`, ergänzt um einen zentrierten/linksbündigen Text ("Administration") und ein Chevron-Icon (`expand_more` / `expand_less`) rechtsbündig, das den Klapp-Zustand spiegelt.
- Der Header ist klickbar (ganze Zeile), mit Hover-Feedback passend zum bestehenden `mat-nav-list`-Stil.
- Die Admin-Punkte werden bei eingeklapptem Zustand nicht gerendert (nicht nur versteckt), analog zu Angular `@if`.

## Betroffene Dateien

- `frontend/src/app/app.component.ts` — neues Boolean-Feld (z.B. `adminSectionExpanded = true`) und Toggle-Methode.
- `frontend/src/app/app.component.html` — Restrukturierung der `mat-nav-list`, neuer klickbarer Trennlinien-Header mit Chevron, `@if` um den Admin-Punkte-Block.
- `frontend/src/app/app.component.scss` — Styling für den neuen Section-Header (Trennlinie + Label + Icon, Hover-Zustand).

## Nicht Teil dieser Änderung

- Keine neuen Berechtigungen oder Backend-Änderungen.
- Keine Änderung an den Routen oder Zielseiten der Admin-Punkte.
- Keine Persistierung des Klapp-Zustands.
- Keine Aufteilung in weitere Untergruppen (z.B. "Verwaltung" vs. "Einstellungen") — bleibt eine Admin-Gruppe mit der bestehenden internen `mat-divider`.

## Testing

- Manueller Smoke-Test: Als Admin einloggen, Trennlinie klicken → Admin-Punkte klappen ein/aus, Chevron dreht sich. Als Nicht-Admin einloggen → kein Administration-Header sichtbar, wie bisher.
- Bestehende Frontend-Tests für `AppComponent` (falls vorhanden) müssen weiterhin grün sein; ggf. Test für Toggle-Verhalten ergänzen.
