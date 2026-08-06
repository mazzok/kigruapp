# Komponentenweise Rechtevergabe (Pilot: Platzzuweisung)

## Kontext

Die Admin-Maske "Rollen" (aktuell `administration/board`, Vorstand/Rollen-Verwaltung) bekommt einen Button "Rechte vergeben". Damit soll es möglich werden, pro einzelner UI-Komponente festzulegen, welche Rolle oder welches Team sie sehen bzw. bearbeiten darf — unabhängig vom heutigen, binären `isAdmin()`-Check.

Aktueller Stand (Rechercheergebnis):
- Kein granulares Berechtigungssystem im Backend, nur `CurrentUserService.isAdmin()` (binär).
- Rollen und Teams existieren nur als generische `field_instances` (kein First-Class-Entity), verwaltet über `ParentTeamRolesSeedMigration` / `BoardSeedMigration` / `ParentTeamsSeedMigration`.
- Kein Component-Registry, keine `*hasRole`-Direktive im Frontend — Sichtbarkeit wird bisher ad hoc per `*ngIf` auf `isAdmin` geprüft.

Dieses Feature führt einen neuen, komponentenweisen Sichtbarkeits-/Editierbarkeits-Layer ein, zusätzlich zum bestehenden `isAdmin()`.

## Scope

**Pilot, nicht Rollout auf die ganze Admin-Oberfläche.** Zielbereich: `administration/platzzuweisung` (Platzzuweisung-Seite), mit genau drei Komponenten-Keys in einer Eltern-Kind-Hierarchie:

- `platzzuweisung` (Seite, Elternknoten) — eigenes Zahnrad oben auf der Seite, Rechte hier gelten für die ganze Seite
  - `platzzuweisung.semester-auswahl` — das Semester-Dropdown
  - `platzzuweisung.zuweisungstabelle` — die gesamte Zuweisungstabelle (Spalten Gruppe + Eintritt zusammen als ein Block)

Granularität generell: ganze Komponente/Karte, nicht einzelnes Feld. Für den Pilot ergeben sich daraus genau diese drei Einheiten, da die Platzzuweisung-Seite aktuell aus einem einzigen Component-File ohne Unterkomponenten besteht.

Erweiterung auf weitere Admin-Screens ist bewusst nicht Teil dieses Specs — die Architektur soll das aber ohne Bruch ermöglichen (neue `componentKey`s registrieren, keine Strukturänderung nötig).

## Hierarchie & Vererbung

Rechte werden hierarchisch vererbt: eine Regel auf einem Elternknoten (z.B. `platzzuweisung`) gilt automatisch auch für alle Kindknoten (`platzzuweisung.semester-auswahl`, `platzzuweisung.zuweisungstabelle`), ohne dass dort etwas explizit gesetzt werden muss.

- **Vererbte Rechte können weiter unten nicht entzogen werden.** Ein Kindknoten kann eine Rolle/ein Team nicht auf ein niedrigeres Level zurückstufen als das vom Elternteil Geerbte.
- **Ein Kindknoten kann zusätzliche Rechte gewähren**, die über das Geerbte hinausgehen (z.B. Elternteil gibt Rolle X nur `VIEW`, die Tabelle im speziellen gewährt Rolle X zusätzlich `EDIT`).
- Formal: effektives Level an einem Knoten für eine Person = `MAX` über alle Level entlang der Kette von der Wurzel bis zu diesem Knoten (eigene Regel am Knoten selbst eingeschlossen, sonst Default `VIEW`). Da `MAX` verwendet wird, kann ein niedriger gesetzter Wert weiter unten ein höheres geerbtes Recht nie überschreiben — Monotonie ist strukturell garantiert, keine zusätzliche Validierung nötig.
- Admin-Bypass gilt unabhängig von der Hierarchie immer (überall `EDIT`).

## Komponenten-Baum (Registry)

Neue Entity `ComponentTreeNode`:

| Feld | Typ | Beschreibung |
|---|---|---|
| `componentKey` | String (PK) | z.B. `"platzzuweisung.zuweisungstabelle"` |
| `parentKey` | String, nullable | Verweist auf den Eltern-`componentKey`, `null` bei Wurzelknoten |
| `label` | String | Anzeigename für die Baum-Verwaltung (z.B. "Zuweisungstabelle") |

Für den Piloten wird der Baum initial per Seed-Migration angelegt (analog zu `BoardSeedMigration`): `platzzuweisung` (Wurzel) mit den zwei Kindern `platzzuweisung.semester-auswahl` und `platzzuweisung.zuweisungstabelle`.

**Baum-Pflege über Admin-UI:** Ein Admin kann Label und Elternteil bestehender Knoten in einer einfachen Baum-Verwaltungsansicht ändern (umbenennen, umhängen). Neue Knoten entstehen **nicht** über diese UI — sie entstehen ausschließlich, wenn ein Entwickler eine neue Komponente im Code mit `<app-permission-gate componentKey="...">` registriert; das Frontend meldet den neuen Key beim Start (oder per einmaligem Sync-Endpoint) beim Baum an, falls er noch nicht existiert (Default: als Wurzelknoten ohne Parent, bis ein Admin ihn manuell einhängt). Die Admin-UI erfindet also keine Komponenten, sondern verwaltet nur die Struktur/Metadaten bereits im Code registrierter Knoten.

Effektiv-Level-Berechnung (`ComponentPermissionService.getEffectiveLevel`) läuft die Elternkette im `ComponentTreeNode`-Baum von der Wurzel bis zum angefragten Knoten ab und bildet das `MAX` wie oben beschrieben.

## Enforcement: Frontend + Backend

Rechteprüfung passiert an beiden Stellen:
- **Frontend**: UI wird versteckt/deaktiviert je nach Effektiv-Level — reine UX, kein Sicherheitsmechanismus.
- **Backend**: Die betroffenen Schreib-Endpoints (Gruppenzuweisung, Eintrittsdatum) prüfen das Recht serverseitig. Wer die API direkt aufruft, kann die Beschränkung nicht umgehen.

## Datenmodell (Backend)

Neue Entity `ComponentPermissionRule`:

| Feld | Typ | Beschreibung |
|---|---|---|
| `id` | Long | PK |
| `componentKey` | String | z.B. `"platzzuweisung.zuweisungstabelle"` |
| `targetType` | Enum | `ROLE` \| `TEAM` |
| `targetInstanceId` | Long | Verweist auf die field-instance der Rolle/des Teams |
| `level` | Enum | `NONE` \| `EDIT` |

Keine Regel für eine (`componentKey`, Rolle/Team)-Kombination → Default-Level `VIEW`. Admin ist von allen Regeln ausgenommen und hat immer vollen Zugriff (`EDIT`).

`EDIT` impliziert `VIEW` — es gibt keinen Zustand "editieren ohne sehen".

## Backend-Services & Endpoints

- `ComponentPermissionService.getEffectiveLevel(personId, componentKey)` → `NONE | VIEW | EDIT`, berücksichtigt Admin-Bypass, Rollen- und Team-Zugehörigkeit der Person, Default `VIEW`.
- `ComponentPermissionService.requireLevel(componentKey, EDIT)` → wirft 403, wenn nicht erfüllt. Wird in die bestehenden Platzzuweisung-Schreib-Endpoints (Gruppenwechsel, Eintrittsdatum-Änderung) eingebaut.
- `GET /api/component-permissions/{componentKey}` — effektives Level für den aktuellen User (Frontend-Gate ruft das auf).
- `GET /api/component-permissions/{componentKey}/rules` — alle Regeln für diesen Key (Admin-only, für das Popup).
- `PUT /api/component-permissions/{componentKey}/rules` — Regelsatz für diesen Key ersetzen (Admin-only).

## Frontend

**Edit-Mode State:**
- `PermissionEditModeService` hält ein globales Signal `editMode: 'navigieren' | 'editieren'`, unabhängig von Routing/Navigation.
- Button "Rechte vergeben" (auf der Vorstand/Rollen-Seite) öffnet einen Dialog mit dem Navigieren/Editieren-Toggle. Auswahl von "Editieren" setzt das globale Signal; der Dialog wird danach normal geschlossen — kein dauerhaft sichtbares Panel.
- Navigation verhält sich in beiden Modi vollkommen normal.

**Permission-Gate:**
- Wrapper-Komponente `<app-permission-gate componentKey="...">`, wrappt die Seite selbst (`platzzuweisung`), `semester-auswahl` und `zuweisungstabelle`.
- Holt beim Init einmalig (pro Session pro Key gecacht) das bereits vererbungs-aufgelöste effektive Level via `GET /api/component-permissions/{componentKey}` (die Vererbung wird serverseitig berechnet, das Gate selbst kennt den Baum nicht). Admin überspringt den Fetch (immer `EDIT`).
- Rendering je Level: `NONE` → Inhalt komplett ausgeblendet. `VIEW` → Inhalt sichtbar, Eingabeelemente deaktiviert. `EDIT` → volle Interaktivität.
- Zusätzlich: wenn `editMode === 'editieren'` UND aktueller User Admin ist, wird oben rechts am gewrappten Element ein kleines Zahnrad-Icon eingeblendet.

**Permission-Popup:**
- Klick auf das Zahnrad öffnet einen Dialog für den jeweiligen `componentKey`.
- Listet bestehende Rollen (`board-roles`/`parent-team-roles` field-instances) und Teams, je mit Auswahl: *sichtbar (Standard)* / *nicht sichtbar* / *editieren*. Für Rollen/Teams, die bereits über einen Elternknoten ein Recht geerbt haben, ist die Auswahl nach unten hin gesperrt (z.B. bei geerbtem `VIEW` ist "nicht sichtbar" nicht anwählbar) — die UI verhindert das Entziehen strukturell, nicht nur durch Validierung im Nachhinein.
- Speichern ruft `PUT /api/component-permissions/{componentKey}/rules` mit dem vollständigen Regelsatz für diesen Key.
- Admin-Zugriff selbst ist von diesem Popup nie betroffen.

**Baum-Verwaltungsansicht:**
- Einfache Liste/Baumansicht (Teil der Vorstand/Rollen-Seite, neben dem "Rechte vergeben"-Button) mit allen registrierten `ComponentTreeNode`s: Label, aktueller Elternteil.
- Admin kann Label und Elternteil eines bestehenden Knotens ändern. Keine Möglichkeit, neue Knoten anzulegen oder zu löschen — das folgt ausschließlich aus der Code-Registrierung neuer `componentKey`s.

## Fehlerbehandlung

- Effektiv-Level-Abfrage schlägt fehl (Netzwerk/500): fail closed → `VIEW` für Nicht-Admins annehmen, Fehler nur in Konsole loggen, kein User-Fehlerdialog nötig.
- Speichern im Popup schlägt fehl: Inline-Fehlermeldung im Dialog, Dialog bleibt offen (Auswahl nicht verloren).
- Backend lehnt Schreibzugriff ab (403): bestehendes Fehler-Toast-Pattern, kein neues UI nötig — sollte selten auftreten, da UI bei reinem `VIEW` die Eingaben schon deaktiviert.

## Testing

- **Backend:** Unit-Tests für `ComponentPermissionService` (Admin-Bypass, Default `VIEW` ohne Regel, Rollen-Regel, Team-Regel, `EDIT` impliziert `VIEW`, Vererbung über die Baum-Kette via `MAX`, Kind kann nicht unter geerbtes Level fallen, Kind kann über geerbtes Level hinaus erweitern); Integrationstest, dass der Platzzuweisung-Schreib-Endpoint eine nicht-berechtigte Rolle auch bei direktem API-Call mit 403 ablehnt.
- **Frontend:** Unit-Tests für `PermissionEditModeService` (Toggle-Zustand), `app-permission-gate` (Rendering je Level, Zahnrad nur bei Admin+Editieren sichtbar), Permission-Popup (lädt bestehende Regeln, speichert Auswahl korrekt).
- **Manueller Smoke-Test:** Als Admin "Rechte vergeben" → Editieren wählen → zu Platzzuweisung navigieren → Zahnräder erscheinen auf Seite, Semester-Auswahl und Tabelle → der Seite (`platzzuweisung`) eine Rolle nur mit "sichtbar" zuweisen, dann bei der Tabelle im speziellen "editieren" für dieselbe Rolle ergänzen → als diese Rolle einloggen → Tabelle ist editierbar, Semester-Auswahl bleibt nur sichtbar (geerbt von der Seite) → Versuch, das geerbte Sichtbar-Recht der Seite bei einem Kind zu entziehen, ist in der UI gar nicht erst anwählbar → zurück auf Navigieren schalten → Zahnräder verschwinden überall.

## Out of Scope

- Rollout auf weitere Admin-Screens außerhalb von Platzzuweisung.
- Feld-genaue Granularität (einzelne Spalten/Eingabefelder statt ganzer Komponenten).
- Persistenter Floating-Panel-Modus über Navigation hinweg — der Editieren-Zustand ist reiner globaler State ohne eigenes UI-Overlay außer den Zahnrädern selbst.
