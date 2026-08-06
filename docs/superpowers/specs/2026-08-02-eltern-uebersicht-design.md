# Eltern-Übersicht — Design

Datum: 2026-08-02
Branch: `feature/eltern-uebersicht` (von `main`, 574a778)

## Ziel

Eltern sehen die Kontaktdaten der anderen Eltern, deren Kinder mit den eigenen
Kindern in einer Gruppe sind. Darstellung als Tabelle mit einem Dropdown über
die Gruppen, in denen eigene Kinder zugeteilt sind.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Sichtbare Daten | Name des Elternteils, E-Mail, Telefon, Name des Kindes, Adresse der Familie |
| Zugriff | Alle authentifizierten Eltern, kein Opt-out, kein globaler Admin-Schalter |
| Zeitraum | Laufendes Semester, nicht wählbar |
| Gruppenauswahl | Nur Gruppen, in denen eigene Kinder zugeteilt sind |
| Zeilenstruktur | Eine Zeile pro Familie |
| Eigene Familie | Wird angezeigt, markiert, steht an erster Stelle |
| Navigation | Eigener Menüpunkt, Route `/eltern` |

Bewusst nicht umgesetzt: Opt-out pro Person, Semesterwahl, Sortier-/Filter-UI,
Export. Bei Gruppengrößen um 20 Familien tragen sie nichts bei.

## Datenmodell (bestehend)

Gruppenzuteilung liegt in der Collection `semester_assignments` als
`SemesterAssignment` mit `section: "group"`, `personId` (das Kind), `semesterId`
und `fieldInstanceId` (die konkrete Gruppe; `definitionId` ist für alle Gruppen
dasselbe Template). Der Gruppenname ist der `value` der Field-Instance.

`Person` trägt die Property `personType` (`CHILD` / `PARENT`) als `FieldRef` in
`basicProperties`; `familyId` verweist auf `Family`, das die `address`-Map hält.
Skalare Personen-Properties (`firstName`, `lastName`, `email`, `phone`) löst
`PersonPropertyResolver` gebündelt auf.

## Backend

### `ParentDirectoryService` (neu, Package `service`)

Eine öffentliche Methode, die für die aktuelle Person das gesamte Verzeichnis
aufbaut:

1. `CurrentUserService.getCurrentPerson()` — ist keine Person auflösbar, wird
   403 signalisiert.
2. Eigene Kinder: `Person.findByFamilyId(familyId)`, gefiltert auf
   `personType == CHILD`.
3. Laufendes Semester über die vorhandene Semester-Auflösung.
4. Assignments der eigenen Kinder mit `section: "group"` im laufenden Semester
   → Menge der eigenen `fieldInstanceId`s. Das sind die Gruppen des Dropdowns.
   Der Client benennt sie nie selbst — dadurch existiert kein Parameter, über
   den fremde Gruppen angefragt werden könnten.
5. Je Gruppe: alle Assignments derselben Instanz → Kind-Personen → deren
   `familyId`s → je Familie die Kinder **dieser Gruppe** (nicht alle Kinder der
   Familie), die Eltern (`personType == PARENT`) und `Family.address`.

Aufwand: je eine Query für die eigenen und die fremden Assignments, eine für
die beteiligten Personen, eine gebündelte Auflösung über
`PersonPropertyResolver` — kein N+1.

### Geteilte Helfer

`isChild` / `personType`-Prüfung und die Semester-Auflösung liegen heute privat
in `PersonResource`. Beide wandern nach `service`, damit Resource und neuer
Service dieselbe Logik nutzen statt sie zu duplizieren. `PersonResource` ruft
danach die ausgelagerte Variante auf; sein Verhalten ändert sich nicht.

`RecipientResolverService.resolveGroupParents` bleibt unverändert. Es dient dem
Mailversand und filtert Eltern ohne E-Mail heraus — hier sollen sie erscheinen.

### `ParentDirectoryResource` (neu)

`GET /api/v1/parent-directory`, eine einzige Methode, keine Query-Parameter.
Eintrag in der `SecurityFilter`-Whitelist als authentifiziert, nicht
admin-pflichtig (Muster wie `GET /api/v1/cooking-reminder-settings`).

Response:

```json
{
  "semesterId": "...",
  "groups": [
    {
      "groupInstanceId": "...",
      "groupName": "Käfergruppe",
      "families": [
        {
          "familyId": "...",
          "isOwnFamily": true,
          "children": ["Lena"],
          "parents": [
            { "firstName": "Anna", "lastName": "M.", "email": "anna@x.at", "phone": "0660 111" }
          ],
          "address": "Hauptstraße 1, 1010 Wien"
        }
      ]
    }
  ]
}
```

Festlegungen:

- `address` wird serverseitig aus der `Family.address`-Map zu einer Zeile
  zusammengesetzt; das Frontend kennt die Map-Struktur nicht.
- Fehlende Werte sind `null`, nie ein Platzhaltertext. Die Darstellung
  entscheidet das Frontend.
- Keine Personen-IDs im Payload — es gibt nichts, was der Client damit
  nachladen dürfte.
- Gruppen nach Name sortiert, Familien nach dem ersten Kindernamen; die eigene
  Familie steht in jeder Gruppe an erster Stelle.
- Kein laufendes Semester → `semesterId: null`, `groups: []`, Status 200. Der
  Zustand zwischen zwei Semestern ist kein Fehler.

## Frontend

Neuer Ordner `frontend/src/app/eltern/` mit `eltern.component.{ts,html,scss}`
(standalone, lazy geladen) und `services/parent-directory.service.ts` — dasselbe
Muster wie `cooking/`. Route `eltern` mit `authGuard`, ohne `adminGuard`, plus
Menüeintrag in `app.component.html` neben Kochdienst und Stunden.

Verhalten:

- Ein Request beim Laden. `mat-select` mit den Gruppen, die erste
  vorausgewählt. Auch bei nur einer Gruppe wird das Select angezeigt.
- `mat-table`, eine Zeile pro Familie, Spalten: Kind(er) | Eltern | E-Mail |
  Telefon | Adresse. Mehrere Eltern beziehungsweise Kinder stehen untereinander
  in derselben Zelle, zeilenweise ausgerichtet zwischen den Eltern-Spalten.
- E-Mail als `mailto:`-Link, Telefon als `tel:`-Link. Fehlende Werte bleiben
  leer.
- Die eigene Zeile ist dezent hinterlegt und mit „(meine Familie)"
  gekennzeichnet.
- Keine Gruppen → Leerzustand mit Hinweis statt leerer Tabelle.
- HTTP-Fehler → Snackbar wie in den übrigen Seiten, plus Hinweis zum erneuten
  Laden.
- Schmale Viewports: die Tabelle scrollt horizontal, wie in den bestehenden
  Tabellen gelöst.

Kein clientseitiges Sortieren oder Filtern.

Randnotiz: Der ungemergte Branch `feature/startseite` macht die Startseite
konfigurierbar. Nach dessen Merge wäre `/eltern` dort als Option nachzutragen —
außerhalb dieses Scopes.

## Tests

`ParentDirectoryResourceTest` (Quarkus, Muster der bestehenden Resource-Tests):

- Ein Elternteil sieht ausschließlich Gruppen der eigenen Kinder; eine
  Kontrollgruppe im selben Semester taucht nicht auf.
- Eine Familie mit zwei Kindern in derselben Gruppe erscheint als eine Zeile
  mit beiden Kindernamen.
- Eltern ohne E-Mail erscheinen mit `email: null`.
- Zuteilungen anderer Semester werden ignoriert.
- Die eigene Familie ist enthalten und trägt `isOwnFamily: true`.
- Kind ohne Gruppenzuteilung → leere Gruppenliste.
- Kein laufendes Semester → 200 mit leerer Gruppenliste.

`SecurityFilterTest`: Der Endpoint ist für authentifizierte Nicht-Admins
erreichbar und wird anonym abgewiesen.

`eltern.component.spec.ts`: Tabelle rendert aus einer Mock-Response, der
Gruppenwechsel tauscht die Zeilen, Leerzustand ohne Gruppen, eigene Familie
zuerst.

Baseline: `main` hat 13 vorbestehende fehlschlagende Backend-Tests. Verglichen
wird gegen diesen Stand, nicht gegen „alles grün".
