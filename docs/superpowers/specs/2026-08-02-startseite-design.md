# Konfigurierbare Startseite — Design

**Datum:** 2026-08-02
**Status:** Entwurf zur Freigabe

## Ziel

Administratoren gestalten eine Übersichtsseite, die alle eingeloggten Nutzer beim
Betreten der App sehen. Die Gestaltung erfolgt in einem Editor nach dem Vorbild
des Mail-Vorlagen-Editors: WYSIWYG, einfügbare Platzhalter, umschaltbarer
HTML-Quelltext und eine Vorschau, die die Seite mit echten Werten zeigt.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Sichtbarkeit | Neue Startseite für alle eingeloggten Nutzer, Admins eingeschlossen. Route `''` zeigt künftig die Übersichtsseite; `cooking` bleibt als eigener Menüpunkt erhalten. Die beiden Lese-Endpunkte müssen im `SecurityFilter` freigeschaltet werden, sonst greift dessen Default-Deny und Nicht-Admins bekommen 403. |
| HTML-Eingabe | Ein Editor mit zwei Ansichten: Quill-WYSIWYG und rohes HTML-Quelltextfeld, synchronisiert über dasselbe Formularfeld. |
| Dynamische Inhalte | Platzhalter-Kacheln wie im Mail-Editor, drei Token-Familien. |
| Publizieren | Kein Entwurfszustand. Speichern ist sofort live; die Vorschau ist die Absicherung davor. |
| Vorschau | Tab-Umschalter *Bearbeiten* / *Vorschau*. |
| HTML-Umfang | Gelockerte Web-Policy: Text, Überschriften, Listen, Tabellen, Bilder, Links, Inline-Styles. Kein `<script>`, kein `<iframe>`. |
| Architektur | Backend liefert Roh-HTML plus eine Werte-Map; die Ersetzung passiert im Frontend. |

### Warum die Ersetzung im Frontend passiert

Die Alternative — der Server liefert fertig gerendertes HTML — erzwingt für die
Live-Vorschau entweder einen Request pro Tastendruck oder eine zweite,
abweichende Renderlogik im Frontend. Genau diese Doppelung soll es nicht geben:
Vorschau und Nutzeransicht rufen dieselbe Funktion mit derselben Werte-Map auf,
also können sie nicht auseinanderlaufen. Dass das Roh-HTML mit Token-Syntax den
Client erreicht, ist unkritisch — es ist redaktioneller Inhalt, den ohnehin
jeder Nutzer sehen darf.

Der Admin sieht in der Vorschau seine **eigenen** echten Werte, nicht
Beispieldaten. Das ist ehrlicher als das Sample-Verfahren des Mail-Editors und
kostet keinen Zusatzaufwand, weil `/context` ohnehin existiert.

## Backend

### Entity

`LandingPage` — Singleton, genau eine Zeile:

- `bodyHtml` (Text, sanitisiert gespeichert)
- `updatedAt`
- `updatedBy`

Existiert die Zeile nicht, liefert `GET` einen leeren Inhalt statt eines 404;
das erste `PUT` legt sie an.

### Resource `/api/v1/landing-page`

| Methode | Pfad | Rechte | Zweck |
|---|---|---|---|
| `GET` | `/` | eingeloggt | Roh-HTML inklusive Tokens |
| `PUT` | `/` | Admin | Speichern, sanitisiert |
| `GET` | `/placeholders` | Admin | Kachel-Liste für den Editor |
| `GET` | `/context` | eingeloggt | `Map<Token, Wert>` für den aufrufenden Nutzer |

### Sanitizing

Eine neue `WEB_HTML_POLICY` neben der bestehenden Mail-Policy, zusammengesetzt
aus den OWASP-Bausteinen Formatting, Blocks, Links, Images, Tables und Styles.
`<script>` und `<iframe>` sind nicht enthalten und werden entfernt.

Wie bei `MailTemplateResource.sanitizeBody` müssen die vom Sanitizer zwischen
die Klammern geschobenen Leerkommentare (`{<!-- -->{`) anschließend gestrippt
werden — sonst zerfallen die gespeicherten `{{…}}`-Tokens und weder die
Ersetzung noch die Token-zu-Pill-Umwandlung im Editor findet sie wieder.

### Token-Resolver

Ein Interface `LandingTokenProvider` mit zwei Aufgaben: Kacheln beschreiben und
Werte für eine gegebene Person liefern. Drei Implementierungen:

| Familie | Tokens | Quelle |
|---|---|---|
| person | `{{person.<feld>}}` | `PersonPropertyResolver`, dessen Allowlist bereits dieselbe ist wie die der Mail-Platzhalter |
| stunden | `{{stunden.geleistet}}`, `{{stunden.soll}}`, `{{stunden.bilanz}}` | dieselbe Berechnung wie `/hour-entries/our` (`OurHoursDto`) |
| kochdienst | `{{kochdienst.naechsterTermin}}` | nächster künftiger Kochdienst der eigenen Familie: `Person.schedules` → `FieldInstance` der Definition `cookingDuty`, Feld `date` |

**Kein `{{org.*}}`:** Es gibt im System keine Organisations-Stammdaten (die
`Organisation`-Entity ist ein Tag-Container für Felddefinitionen und
Dienst-Arten, kein Vereinsprofil). Vereinsname und Adresse sind ohnehin für alle
Nutzer identisch — der Admin schreibt sie direkt in den Text, ein Token brächte
gegenüber getipptem Text keinen Nutzen.

`/placeholders` und `/context` iterieren ausschließlich über die
Provider-Liste. Eine weitere Token-Familie ist damit eine neue Klasse, keine
Änderung an der Resource.

Liefert ein Provider keine Daten (kein Semester, kein Kochdienst eingeteilt),
gibt er für seine Tokens einen leeren Wert zurück und wirft nicht — ein
fehlender Kochdienst darf die Startseite nicht kippen.

## Frontend

### Editor

Neue Komponente unter `settings/landing-page/`, erreichbar über Route
`settings/landing-page` mit `authGuard` und `adminGuard`, als Menüpunkt
„Startseite" in den Einstellungen. Aufbau analog `mail-template-editor`.

- `mat-tab-group` mit den Tabs **Bearbeiten** und **Vorschau**
- Tab *Bearbeiten* trägt einen Umschalter **WYSIWYG ⇄ HTML-Quelltext**:
  - WYSIWYG: Quill mit einer Web-Toolbar, die gegenüber `EMAIL_SAFE_QUILL_TOOLBAR`
    um Überschriften, Listen, Einzug und Bild erweitert ist. Der Bild-Button
    fragt nach einer URL; Quills Standardverhalten, die Datei als `data:`-URI
    einzubetten, wird abgeschaltet — sonst wächst `bodyHtml` mit jedem Bild um
    Hunderte Kilobyte. Passend dazu lässt die Sanitizer-Policy bei `<img src>`
    nur `http`/`https` zu, keine `data:`-URIs.
  - Quelltext: `textarea` mit dem rohen HTML
  - Beide schreiben in dasselbe `bodyHtml`-FormControl. Beim Wechsel in den
    Quelltext werden Token-Pills über `pillsToTokens` zu `{{…}}` zurückgewandelt,
    beim Wechsel zurück über `tokensToPills` wieder zu Pills.
- Platzhalter-Kacheln seitlich, mit Klick-Einfügen an der Cursorposition und
  Drag&Drop in den Editor — dieselbe Mechanik wie im Mail-Editor
- Tab *Vorschau*: das gerenderte HTML mit den Werten aus `/context`

### Anzeige

Neue Komponente `landing/landing.component.ts` auf Route `''` mit `authGuard`.
Sie ersetzt den bisherigen Redirect auf `cooking`; `cooking` bleibt als eigene
Route und eigener Menüpunkt bestehen.

Ablauf: `GET /landing-page` und `GET /landing-page/context` laden, ersetzen,
über `DomSanitizer.bypassSecurityTrustHtml` anzeigen — zulässig, weil das
Backend beim Speichern bereits sanitisiert hat.

### Geteilte Logik

`shared/landing-token.util.ts` mit der Token-Ersetzung und der Token-zu-Pill-
Umwandlung. Editor-Vorschau und Nutzeransicht rufen dieselbe Funktion auf.

### Randfälle

- Nie befüllte oder leere Seite: freundlicher Leerzustand statt weißer Fläche
- `/context` schlägt fehl: Inhalt trotzdem anzeigen, unauflösbare Tokens durch
  „–" ersetzen. Die Startseite darf unter keinen Umständen blockieren.
- Token im gespeicherten HTML, zu dem es keinen Wert mehr gibt (Feld gelöscht):
  gleiche Behandlung, „–"

## Tests

**Backend**

- Sanitizer entfernt `<script>` und `<iframe>`, lässt Tabellen, Bilder und
  Inline-Styles stehen
- Sanitizer entfernt ein `<img>` mit `data:`-URI, behält eines mit `https:`-URL
- `{{…}}`-Tokens überstehen das Sanitizing unverändert
- `GET /` ohne existierende Zeile → 200 mit leerem Inhalt
- `PUT` überschreibt die vorhandene Zeile, statt eine zweite anzulegen
- `/context` liefert Werte für alle drei Familien
- `/placeholders` bleibt vollständig, wenn ein Provider leer ausgeht
- Provider ohne Daten (kein Semester, kein Kochdienst) liefert leere Werte statt
  einer Exception

**Frontend**

- Token-Roundtrip `HTML → Pills → HTML` ist verlustfrei, auch über einen
  Wechsel WYSIWYG ⇄ Quelltext hinweg
- Vorschau-Tab rendert mit den Werten aus `/context`
- Landing-Component zeigt bei leerem Inhalt den Leerzustand
- Landing-Component zeigt bei fehlgeschlagenem `/context` den Inhalt, Tokens
  als „–"
- Route `''` lädt die Landing-Component

## Bewusst nicht enthalten

- Entwurf/Veröffentlichen-Workflow
- Mehrere Seiten oder rollenspezifische Varianten
- Versionshistorie der Seite
- Datei-Upload für Bilder — Bilder werden über URL eingebunden
