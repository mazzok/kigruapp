# Familie bearbeiten: Übersicht statt Wizard

## Kontext

Der `FamilyWizardComponent` ist aktuell ein sequentieller `mat-stepper`-Dialog mit drei
Schritten (Familie → Kind → Eltern), verbunden mit "Weiter"/"Zurück"-Buttons. Sowohl beim
Neuanlegen als auch beim Bearbeiten einer Familie muss man diese Reihenfolge durchlaufen,
selbst wenn man nur einen einzelnen Teilbereich ändern will (z.B. nur ein Kind hinzufügen).

Ziel: Familie, Kinder und Eltern werden zu drei unabhängigen Bereichen, zwischen denen über
eine Übersicht navigiert wird. Jeder Bereich hat nur noch **Abbrechen**/**Speichern**, kein
"Weiter" mehr.

## Navigation & View-State

`FamilyWizardComponent` ersetzt den `mat-stepper` durch einen einfachen View-State:

```ts
view: 'overview' | 'family' | 'children' | 'parents' = 'overview';
resolvedFamilyId?: string; // = data?.familyId, oder gesetzt nach erstem erfolgreichen saveFamily()
```

- **Bearbeiten** (`data.familyId` vorhanden): Familie, Kinder und Eltern werden wie heute beim
  Öffnen geladen (`loadEditData()`), Dialog startet bei `view = 'overview'`.
- **Neuanlegen** (`data` null): Dialog startet ebenfalls bei `'overview'`, aber ohne Daten;
  `resolvedFamilyId` ist `undefined`.
- Von der Übersicht aus wählt man eine Kachel → `openSection(view)` setzt `this.view`.
- Rücksprung zur Übersicht (Abbrechen innerhalb eines Bereichs, oder nach erfolgreichem
  Speichern) setzt `view = 'overview'`. Die jeweilige Step-Komponente wird per `@if` entfernt
  und beim nächsten Öffnen aus den zuletzt gespeicherten Daten (`existingChildren` /
  `existingParents` / `editFamily`) neu aufgebaut — kein manuelles Reset nötig.
- **Kinder- und Eltern-Kachel sind deaktiviert**, solange `resolvedFamilyId` nicht gesetzt ist
  (neue Familie muss zuerst gespeichert werden). Deaktivierte Kacheln zeigen einen Hinweistext
  ("Erst Familie speichern").
- Dialog-Titel ist dynamisch: `resolvedFamilyId` gesetzt → "Familie bearbeiten", sonst
  "Familie erstellen". Wechselt live, sobald die Familie beim Neuanlegen zum ersten Mal
  gespeichert wurde.

## Speicher-Logik pro Bereich

`submitCreate()`/`submitEdit()` entfallen; stattdessen drei unabhängige Methoden, die nur
ihren Bereich persistieren und danach zur Übersicht zurückkehren:

- **`saveFamily()`**: `familyService.create()` wenn `resolvedFamilyId` fehlt, sonst `.update()`.
  Bei Erfolg: `resolvedFamilyId` setzen, lokale Familien-Daten (Name/Adresse) aktualisieren,
  Kinder/Eltern-Kacheln entsperren, `view = 'overview'`.
- **`saveChildren()`**: bestehende Create/Update/Delete-Logik für Kinder (aus dem heutigen
  `submitEdit`, Schritte 2 + Kinder-Teil von Schritt 4). Nach Erfolg `existingChildren` neu
  laden (für korrekten Kachel-Zähler), `view = 'overview'`.
- **`saveParents()`**: analog für Eltern.

Da Kinder/Eltern-Speichern nur läuft, wenn `resolvedFamilyId` bereits existiert, ist der
Ablauf für "Kind zu bestehender Familie hinzufügen" und "Kind bei Neuanlage hinzufügen"
identisch — ein Code-Pfad für beide Fälle, keine Unterscheidung von Create/Edit-Modus mehr
nötig für Kinder/Eltern.

Fehlerfall (analog heute): `console.error`, `submitting = false`, Nutzer bleibt im aktuellen
Bereich und kann erneut speichern.

## Übersicht-UI

Drei Kacheln (Icon + Titel, bei Kinder/Eltern zusätzlich Anzahl):

```
Familie bearbeiten

[ 🏠 Familie        > ]
[ 🧒 Kinder (2)      > ]
[ 🧑 Eltern (2)      > ]

[Abbrechen]
```

- Klick auf eine aktive Kachel öffnet den jeweiligen Bereich (`openSection(...)`).
- Deaktivierte Kacheln (Kinder/Eltern ohne `resolvedFamilyId`) sind ausgegraut mit Hinweistext.
- Die Übersicht hat wie die Bereiche eine sticky Aktionsleiste oben, hier nur mit **Abbrechen**.
  Abbrechen auf der Übersicht schließt den gesamten Dialog; Rückgabewert an den Aufrufer
  (`dialogRef.close(anyChanges)`) ist `true`, falls in dieser Dialog-Sitzung irgendein Bereich
  erfolgreich gespeichert wurde (damit `family-list` bei Bedarf neu lädt).

Jeder Bereich (Familie/Kinder/Eltern) behält die sticky Aktionsleiste aus der letzten Änderung,
umbenannt von `.step-actions` zu `.section-actions` (es gibt keine "Steps" mehr):

```
[Abbrechen]                              [Speichern]
--- Formularinhalt (scrollt darunter) ---
```

- **Abbrechen** im Bereich verwirft nicht gespeicherte Änderungen und springt zurück zur
  Übersicht (nicht: Dialog schließen).
- **Speichern** ruft die jeweilige `save*()`-Methode auf.
- Kein "Weiter"-Button mehr in irgendeinem Bereich.

## Vereinfachung von FamilyStepComponent

Die Radio-Auswahl "neue Familie" / "existierende Familie auswählen" entfällt, da sie durch
das neue Modell redundant wird (um einer bestehenden Familie ein Kind hinzuzufügen, nutzt man
jetzt Bearbeiten → Kinder-Kachel statt diese Option im Anlage-Flow). Entfernt werden:

- `mode: FormControl<'new' | 'existing'>`
- `existingFamilyId`, `existingFamilies`, zugehöriges `familyService.list()` in `ngOnInit`
- Getter `isNewFamily`, `selectedFamilyId`

Übrig bleibt ein reines Formular für Name + Adresse. `isValid` prüft nur noch, dass der Name
nicht leer ist.

`FamilyWizardComponent.saveFamily()` ruft entsprechend immer `familyService.create()` (wenn
`resolvedFamilyId` fehlt) bzw. `.update()` auf — die bisherige Verzweigung über
`familyStep.isNewFamily` entfällt.

## Vorausfüllen von Nachname/Adresse

Bisher übernahm `onStepChange` (Stepper-Event) das Vorausfüllen von Nachname/Adresse in
Kind-/Eltern-Formulare beim Wechsel zwischen Wizard-Schritten. Ohne Stepper passiert das
stattdessen direkt in `openSection('children' | 'parents')`:

```ts
openSection(target: 'family' | 'children' | 'parents'): void {
  this.view = target;
  if (target === 'children' || target === 'parents') {
    const name = this.familyName; // aus editFamily bzw. zuletzt gespeicherter Familie
    const address = this.familyAddress;
    if (name || address) {
      setTimeout(() => {
        if (target === 'children') this.childStep?.prefill(name, address);
        else this.parentsStep?.prefill(name, address);
      }, 0);
    }
  }
}
```

Der `setTimeout(0)` ist nötig, damit die per `@if` neu gerenderte Step-Komponente (und ihr
`@ViewChild`) existiert, bevor `prefill()` aufgerufen wird — analog zum bestehenden
`applyKeycloakPrefill`-Muster in `ParentsStepComponent`.

## Neuer Einstiegspunkt zum Anlegen

Der in dieser Session entfernte "Kind erstellen"-Button in `family-list.component.html` wird
durch einen neuen Button **"Familie erstellen"** ersetzt, der denselben Dialog öffnet wie
Bearbeiten, nur mit `data: null`:

```ts
openCreateWizard(): void {
  const dialogRef = this.dialog.open(FamilyWizardComponent, {
    width: '700px',
    maxWidth: '95vw',
    disableClose: true,
    data: null,
  });

  dialogRef.afterClosed().subscribe((result) => {
    if (result) {
      this.loadData();
    }
  });
}
```

## Betroffene Dateien

- `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts` —
  View-State statt Stepper, `saveFamily`/`saveChildren`/`saveParents` statt
  `submitCreate`/`submitEdit`, `openSection`-Vorausfüllen.
- `frontend/src/app/administration/families/family-wizard/family-wizard.component.html` —
  Übersicht-Template mit drei Kacheln, Section-Templates ohne "Weiter"-Button,
  `.section-actions` statt `.step-actions`.
- `frontend/src/app/administration/families/family-wizard/family-wizard.component.scss` —
  Klassenname-Umbenennung, ggf. Kachel-Styles.
- `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts` (+
  `.html`) — Radio "neu/existierend" entfernt, nur noch Name+Adresse.
- `frontend/src/app/administration/families/family-list/family-list.component.html` (+ `.ts`)
  — neuer Button "Familie erstellen" mit `openCreateWizard()`.

`child-step.component.ts` und `parents-step.component.ts` bleiben unverändert (weiterhin
Listen mit Hinzufügen/Entfernen mehrerer Personen und einem gemeinsamen Speichern).

## Nicht Teil dieser Änderung

- Umbenennung von `FamilyWizardComponent`/Selektor `app-family-wizard` (bleibt trotz
  Wegfalls des Steppers bestehen, um den Diff klein zu halten).
- Änderungen an der internen Struktur von `child-step`/`parents-step`.
