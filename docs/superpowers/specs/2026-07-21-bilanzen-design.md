# Bilanzen — Familien-Kostenmatrix pro Jahr — Design Spec

**Datum:** 2026-07-21
**Status:** Entwurf

---

## Überblick

Eine neue Admin-Rubrik **"Bilanzen"** zeigt eine Matrix: pro Zeile eine Familie (Name),
danach 12 Spalten für die Monate Jan–Dez eines wählbaren Jahres, dann eine **Summe**-Spalte.
Jede Monatszelle zeigt die für diese Familie in diesem Monat anfallenden Kosten, aufsummiert
aus den real erfassten Kostenwerten ihrer Kinder.

Die Kostenwerte stammen aus dem bestehenden Kosten-System (`KostenDefinition` + `KostenValue`,
siehe `2026-07-20-kosten-design.md`): dort wird pro **Semester + Gruppe + Definition** ein
Betrag erfasst. Dieser Betrag ist ein **monatlicher** Betrag (nicht eine Semester-Summe) und
dient hier als **Default**, der pro Familie/Kind/Monat überschrieben werden kann
(Sondervereinbarungen).

Monate in der Zukunft sind ausgegraut, nicht editierbar und zählen nicht zur Summe.

---

## Fachliche Grundannahmen (bestätigt)

- **Zeitbezug des Kostenwerts:** `KostenValue.amount` ist ein **monatlicher** Betrag und der
  editierbare Default.
- **Familie → Kosten:** **Summe pro Kind** — jedes Kind einer Familie trägt die Kosten seiner
  für das Semester des jeweiligen Monats zugewiesenen Gruppe bei. Zwei Kinder in derselben
  Gruppe ⇒ doppelte Kosten.
- **Jahr:** wählbar über ein Dropdown (Default: aktuelles Kalenderjahr, 2026).
- **Aktueller Monat:** aktiv/editierbar und zählt mit. "Zukunft" = **strikt nach** dem laufenden
  Monat.
- **Popup-Posten:** eine Zeile **pro Kind × aktiver Definition**; der Default je Posten ist der
  `KostenValue` (Field-Instance-Betrag) des Semesters/der Gruppe. Editierte Werte werden korrekt
  pro Kind/Monat/Definition gespeichert und zugeordnet.
- **Override-Semantik:** **pro-Zeile absoluter Override, eingefroren.** Nur editierte Zeilen
  werden gespeichert; nicht-editierte folgen weiter dem Default. Spätere Änderungen am
  Gruppen-Default ändern einen bestehenden Override nicht. Kein "Zurücksetzen auf Default"
  (out of scope).
- **Währung:** in der Praxis eine einzige Währung. Zellen summieren numerisch und zeigen ein
  Währungssymbol. Kommen in einer Zelle **mehrere** Währungen vor, wird statt einer Summe eine
  **Warnung** (⚠) angezeigt.
- **Zeilen:** **alle** Familien (nach Name, alphabetisch). Eine Familie ohne zugewiesene Kinder
  erscheint mit lauter 0-Zellen.
- **Ein-/Austritt pro Kind:** eine `SemesterAssignment` trägt `entryDate`/`exitDate`. Ein Kind
  trägt zu einem Monat nur bei, wenn sich sein Aktiv-Zeitraum mit dem Monat **überschneidet**
  (Überlappung zählt: bei Ein-/Austritt mitten im Monat zählt der Monat voll). Monate außerhalb
  des Ein-/Austritt-Zeitraums tragen für dieses Kind 0 bei. Eine Zelle, in der **kein** Kind der
  Familie in diesem Monat aktiv ist, ist leer (0), **nicht editierbar** und zählt nicht zur
  Summe.
- **Visuelle Zellzustände (3):** `future` (nach dem laufenden Monat) → ausgegraut;
  **inaktiv** (nicht zukünftig, aber kein Kind der Familie im Monat aktiv) → eigener,
  von `future` klar unterscheidbarer Stil (eigene Farbe); **aktiv** (nicht zukünftig, ≥ 1
  aktives Kind) → normale Zelle. Man erkennt so auf einen Blick die Aktiv-Spanne der Familie.
- **Ein-/Austritt-Marker:** in dem Monat, in dem ein Kind ein- bzw. austritt (Monat des
  `entryDate` bzw. `exitDate`, sofern im angezeigten Jahr), zeigt die Zelle ein kleines
  Tür-Symbol: Material-Icon **`login`** (Tür mit Pfeil hinein) für Eintritt, **`logout`**
  (Tür mit Pfeil hinaus) für Austritt. **Kein** Tooltip/Kindname — die Position in der Matrix
  (Monat) genügt. Kinder ohne `exitDate` (offen) haben keinen Austritts-Marker.

---

## Datenmodell

### Neue Entity: `BilanzOverride`

Collection `bilanz_overrides`.

```java
@MongoEntity(collection = "bilanz_overrides")
public class BilanzOverride extends PanacheMongoEntity {
    public ObjectId personId;     // das Kind
    public int year;              // z.B. 2026
    public int month;             // 1–12
    public ObjectId definitionId; // welcher Kostenposten
    public BigDecimal amount;     // absoluter überschriebener Wert

    public static BilanzOverride findByKeys(
        ObjectId personId, int year, int month, ObjectId definitionId) { ... }
}
```

Pro `(personId, year, month, definitionId)` existiert maximal eine Zeile (Upsert-Semantik).
Fehlt die Zeile ⇒ Default wird verwendet. Die **Währung** wird nicht am Override gespeichert —
sie folgt immer der aktuellen Währung der `KostenDefinition` (Name und Währung eines Postens
ändern sich nie).

Es werden keine bestehenden Collections geändert. `Family`, `Person`, `Semester`,
`SemesterAssignment`, `KostenDefinition`, `KostenValue`, `Currency` bleiben unverändert.

---

## Backend

### Neu: `BilanzResource`

Dünne JAX-RS-Klasse, statische Panache-Finder, manuelles DTO-Mapping — analog zu
`KostenValueResource`/`SemesterResource`. Admin-only über den bestehenden `SecurityFilter`
(kein Whitelist-Eintrag nötig).

- `GET /api/v1/bilanzen?year=YYYY` — die komplette Matrix:

  ```
  {
    year,
    currentYearMonth,               // z.B. "2026-07" für Zukunfts-Bestimmung
    families: [
      {
        familyId, name,
        months: [                   // genau 12, month 1..12
          { month, amount, currencySymbol, mixedCurrency: bool,
            future: bool, editable: bool, active: bool,   // active = ≥1 aktives Kind
            entryMarker: bool, exitMarker: bool }         // ↦/⇥ falls ein Kind in dem Monat ein-/austritt
        ],
        total
      }
    ]
  }
  ```

- `GET /api/v1/bilanzen/cell?familyId=…&year=YYYY&month=M` — Posten für das Editier-Popup:

  ```
  {
    lines: [
      { personId, childName, definitionId, label,
        currencySymbol, defaultAmount, effectiveAmount }
    ],
    sum,
    mixedCurrency
  }
  ```

- `PUT /api/v1/bilanzen/overrides` `{ personId, year, month, definitionId, amount }` —
  Upsert genau einer Override-Zeile.

### Server-seitige Berechnung (gemeinsamer Helper für beide GET-Endpunkte)

1. **`month → semester`:** finde das Semester, dessen `[start, end]` den **1. des Monats**
   abdeckt. Keins ⇒ Zelle ist 0/leer.
2. **Kind → Gruppe:** für jedes Kind der Familie die `SemesterAssignment` (section `"groups"`)
   in diesem Semester ⇒ `groupId` (= `fieldInstanceId`). Keine Zuweisung ⇒ Kind trägt 0 bei.
   **Ein-/Austritt:** trägt die Zuweisung `entryDate`/`exitDate`, muss sich das Kind-Aktiv-Fenster
   mit dem Monat überschneiden (`entryDate <= letzter Tag des Monats` **und**
   (`exitDate` leer/null **oder** `exitDate >= erster Tag des Monats`)). Sonst trägt das Kind für
   diesen Monat 0 bei und erscheint nicht als Popup-Zeile.
3. **Effektiver Betrag je Posten:** für jede **aktive** `KostenDefinition`:
   `effectiveAmount = BilanzOverride(personId, year, month, definitionId)` falls vorhanden,
   sonst `KostenValue(semesterId, groupId, definitionId).amount`, sonst 0.
4. **Zellbetrag** = Summe aller effektiven Zeilenbeträge aller Kinder.
   `mixedCurrency = true`, falls die beitragenden Definitionen > 1 verschiedene Währungen
   referenzieren.
5. **`future`** = `(year, month)` strikt nach `currentYearMonth`.
   **`active`** = mindestens eine aktive Kind-Zeile in diesem Monat.
   **`editable`** = nicht `future` **und** `active`.
   **`entryMarker`/`exitMarker`** = wahr, falls (mind.) ein Kind seinen `entryDate`- bzw.
   `exitDate`-Monat (im angezeigten Jahr) in diesem Monat hat.
   **Total** = Summe der nicht-zukünftigen Zellen (leere/nicht-editierbare Zellen tragen 0 bei).

Das `month → semester`-Mapping über den 1. des Monats löst deterministisch auch Monate auf,
die an einer Semester-Grenze liegen (kein Doppelzählen).

---

## Frontend

### Neue Seite `administration/bilanzen`

Lazy-loaded Component, Route unter dem bestehenden `administration`-Parent (adminGuard).
Neuer Nav-Eintrag **"Bilanzen"** neben Platzzuweisung / Kosten pro Semester.

Neuer `BilanzService` in `shared/services`:
`getMatrix(year)`, `getCell(familyId, year, month)`, `upsertOverride(...)`.

**Layout:**

- Kopfzeile: **Jahr**-`mat-select` links (Default aktuelles Jahr; befüllt aus einem sinnvollen
  Bereich — Jahre der bestehenden Semester plus aktuelles Jahr). **"Editieren"**-Toggle-Button
  rechts oben.
- Horizontal scrollbare `mat-table`: erste Spalte **Familie** (Name, sticky), dann **Jan…Dez**
  (12 Spalten), dann **Summe** (sticky rechts).
- Jede Monatszelle: summierter Betrag + Währungssymbol. Drei visuelle Zustände:
  **Zukunft** (ausgegraut, nicht interaktiv, nicht in der Summe), **inaktiv** (kein Kind aktiv —
  eigener Stil/Farbe, klar von Zukunft unterscheidbar, 0, nicht editierbar) und **aktiv**
  (normale Zelle). Zellen mit gemischter Währung zeigen ein Warn-Icon (⚠) mit Tooltip statt
  eines einzelnen Symbols. Monate mit Ein-/Austritt eines Kindes zeigen zusätzlich ein kleines
  Tür-Symbol (`mat-icon` `login` = Eintritt, `logout` = Austritt, ohne Tooltip), gespeist aus
  `entryMarker`/`exitMarker`.
- Bei aktivem **Editieren**: jede **editierbare** Zelle (nicht zukünftig **und** mind. ein aktives
  Kind im Monat, siehe `editable`) zeigt einen kleinen Stift (✎). Klick öffnet das Popup. Zellen
  ohne aktives Kind sind ebenfalls ausgegraut/nicht interaktiv (0, keine Posten).

### Editier-Popup (`MatDialog`)

Geladen über `getCell`:

- Titel: Familienname + Monat/Jahr.
- Eine Zeile **pro Kind × aktiver Definition**: z.B. `Anna – Elternbeitrag`, Währungssymbol
  read-only, editierbares Zahlenfeld vorbefüllt mit dem effektiven Betrag (Default oder
  bestehender Override). Label und Währung sind read-only.
- **OK:** für jede geänderte Zeile `PUT` des Overrides; bei Erfolg schließt der Dialog und die
  Zelle + die Summe der Familie werden neu berechnet (Matrix neu laden oder lokal patchen).
- **Abbrechen:** schließen, nichts wird gespeichert.
- Leerzustand (keine Kinder / kein abdeckendes Semester): Popup zeigt
  "keine Posten für diesen Monat".

---

## Randfälle

- **Familie ohne Kinder / Kinder ohne Gruppen-Zuweisung im abdeckenden Semester** ⇒ alle Zellen
  0; Zeile wird trotzdem angezeigt. Popup: "keine Posten für diesen Monat".
- **Monat ohne abdeckendes Semester** (Lücke zwischen Semestern) ⇒ Zelle 0; editierbar wenn nicht
  zukünftig, aber Popup hat keine Zeilen (nichts zu überschreiben).
- **Monat an Semester-Grenze** ⇒ deterministisch über die 1.-des-Monats-Regel; kein
  Doppelzählen.
- **Monat außerhalb des Ein-/Austritt-Zeitraums eines Kindes** ⇒ dieses Kind trägt 0 bei und
  erscheint nicht als Popup-Zeile. Ist **kein** Kind der Familie im Monat aktiv, ist die Zelle
  leer (0), nicht editierbar und zählt nicht zur Summe. Bei Ein-/Austritt mitten im Monat zählt
  der Monat voll (Überlappung).
- **Inaktive `KostenDefinition`** ⇒ aus den Defaults ausgeschlossen (konsistent mit der
  Kosten-pro-Semester-Seite). Ein bestehender Override auf einer inzwischen inaktiven Definition
  wird in Summen ignoriert und nicht angezeigt.
- **Dangling Refs** (gelöschte Gruppe/Semester/Person) ⇒ bei der Auflösung ignoriert, analog zum
  bestehenden `SemesterAssignment`/`KostenValue`-Verhalten — kein aktives Cleanup.
- **Gemischte Währungen in einer Zelle** ⇒ numerische Summe wird zugunsten einer ⚠-Warnung +
  Tooltip unterdrückt; die Summe-Zelle markiert ebenfalls gemischte Währung, falls einer ihrer
  Monate betroffen ist.
- **Override, danach Default-Änderung** ⇒ Override bleibt eingefroren (absolut), by design.
- **Zukünftiges Jahr gewählt** ⇒ alle 12 Monate zukünftig/ausgegraut; vergangenes Jahr ⇒ alle
  editierbar.

---

## Tests

### Backend (`BilanzResourceTest`, analog `KostenValueResourceTest`)

Berechnungs-/Regel-Tests:

- `month → semester`-Mapping inkl. Grenze und Lücke.
- Summation pro Kind: 2 Kinder in derselben Gruppe (2×) und in verschiedenen Gruppen.
- Override-Vorrang vor Default.
- Ausschluss inaktiver Definitionen.
- Ausschluss von Zukunfts-Monaten aus der Summe.
- Ein-/Austritt: Monat außerhalb des Kind-Zeitraums trägt 0 bei; Überlappungs-Grenze (Eintritt/
  Austritt mitten im Monat zählt voll).
- Leere Familie.

**Vollständige Abdeckung aller vom Backend erzeugbaren Zell-Flag-Kombinationen** (die Gegenseite
der Frontend-Kombinationstests, datengetrieben über eine Fixture-Tabelle). Für jede Kombination
wird ein Fixture-Zustand aufgebaut und das erzeugte Monats-Zell-DTO geprüft auf
`{amount, future, active, editable, entryMarker, exitMarker, mixedCurrency}`:

1. **future** (`future=true`, `active` egal, `editable=false`, nicht in Σ), je mit:
   ohne Marker; nur `entryMarker`; nur `exitMarker`; beide; jeweils optional `mixedCurrency`.
2. **inaktiv** (`future=false`, `active=false`, `editable=false`, `amount=0`): genau eine Variante
   — `entryMarker=false`, `exitMarker=false`, `mixedCurrency=false`.
3. **aktiv** (`future=false`, `active=true`, `editable=true`): `entryMarker {0,1}` ×
   `exitMarker {0,1}` × `mixedCurrency {0,1}`, inkl. `entryMarker`+`exitMarker` im selben Monat
   (Kurz-Aufenthalt).

**Invarianten, die nie verletzt werden dürfen** (explizit getestet, über zufällige/erschöpfende
Fixtures): `editable == (active && !future)`; `active=false ⇒ entryMarker=false && exitMarker=false
&& mixedCurrency=false && amount=0`; `entryMarker`/`exitMarker` nur im tatsächlichen Ein-/
Austritt-Monat und nur wenn im angezeigten Jahr (Kind ohne `exitDate` ⇒ `exitMarker=false`);
`mixedCurrency=true` nur bei > 1 Währung unter den beitragenden Definitionen.

### Frontend (`bilanzen.component.spec.ts`)

**Vollständige Abdeckung aller valider visueller Zell-Kombinationen** (jede als eigener Test,
z.B. datengetrieben über eine Fixture-Tabelle). Die visuelle Darstellung ergibt sich aus:
Zustand `{future | inaktiv | aktiv}` × `entryMarker {0,1}` × `exitMarker {0,1}` ×
`mixedCurrency {0,1}` × Editier-Modus `{aus, an}`.

Valide Kombinationen, die getestet werden müssen:

1. **future** (ausgegraut, kein Stift in beiden Editier-Modi, aus Σ ausgeschlossen), je mit:
   - ohne Marker; nur `entryMarker`; nur `exitMarker`; `entryMarker`+`exitMarker` (Ein-/Austritt
     im selben zukünftigen Monat); jeweils optional `mixedCurrency` (⚠ statt Betrag).
2. **inaktiv** (eigener Inaktiv-Stil, Betrag 0, kein Stift in beiden Editier-Modi): genau **eine**
   Variante — ohne Marker, ohne `mixedCurrency` (per Definition kann eine inaktive Zelle keine
   Marker und keine gemischte Währung haben).
3. **aktiv**: `{Stift-aus, Stift-an}` × `entryMarker {0,1}` × `exitMarker {0,1}` ×
   `mixedCurrency {0,1}` — Stift nur bei Editier-Modus „an"; bei `mixedCurrency` ⚠ statt Betrag;
   `entryMarker`+`exitMarker` gleichzeitig (Kurz-Aufenthalt Ein-/Austritt im selben Monat) ist
   valide.

**Ungültige Kombinationen** (müssen im Test explizit als „rendert nie" abgesichert werden bzw.
kommen aus dem Backend nicht vor): `inaktiv`+Marker, `inaktiv`+`mixedCurrency`,
`future`+Stift, `inaktiv`+Stift.

Zusätzliche Verhaltens-Tests:

- Editieren-Toggle schaltet die Stifte nur auf **aktiven** Zellen um (nie auf future/inaktiv).
- Marker-Rendering nutzt die korrekten `mat-icon` (`login`/`logout`) ohne Tooltip.
- Popup füllt effektive Beträge vor.
- OK `PUT`t nur geänderte Zeilen und berechnet Zelle + Summe neu.
- Abbrechen speichert nichts.
- Jahr-Wechsel lädt neu.

---

## Nicht im Scope

- "Zurücksetzen auf Default" für einen einzelnen Override.
- Anzeige historischer Werte inaktiver Definitionen.
- Getrennte Teilsummen pro Währung in einer Zelle (nur Warnung bei Mischung).
- Summenzeile über alle Familien (nur Summe-Spalte pro Familie).
- Verknüpfung mit tatsächlichen Zahlungen/Buchhaltung — reine Kostendarstellung.
- Export (CSV/PDF) der Matrix.
