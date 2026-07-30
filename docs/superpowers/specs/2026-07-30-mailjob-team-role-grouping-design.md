# Mailjob-Empfänger: Teams und ihre Rollen im Dropdown gruppieren

## Kontext

Das Empfänger-Dropdown im Mailjob-Editor (`mail-job-editor.component.html`) zeigt aktuell fünf flache `mat-optgroup`-Abschnitte: Gruppen, Elternteams, Vorstand, Team-Rollen, Vorstandsrollen. Zwischen einem Team und seinen Rollen gibt es keine erkennbare Beziehung im Dropdown, obwohl diese Beziehung im Datenmodell bereits existiert: Jede `parent-team-role`-FieldInstance trägt in `value.teamInstanceId` die ID des zugehörigen `parent-team`-Teams (siehe `elterneinteilung.component.ts`, `getRolesForTeam()`, dasselbe Muster). Board-Rollen (`board-role`) haben kein `teamInstanceId`, weil es nur ein einziges Vorstand-Team gibt.

## Ziel

Teams und ihre Rollen im Dropdown visuell zusammengehörig darstellen: Checkbox für das Team, direkt darunter eingerückt die Checkboxen seiner Rollen.

## Design

**Neue Optgroup-Struktur (3 statt 5 Optgroups):**

1. **Gruppen** — unverändert, flache Liste wie bisher.
2. **Elternteams** — pro Team in der Reihenfolge der geladenen `parentTeams`: zuerst die Team-Checkbox, danach eingerückt die Checkboxen der Rollen mit `value.teamInstanceId === team.id` (aus `teamRoles`). Teams ohne zugehörige Rollen zeigen nur ihre eigene Checkbox, keine leere Einrückung.
3. **Vorstand** — die Checkbox für das (einzige) Board-Team aus `boardTeams`, danach eingerückt alle Checkboxen aus `boardRoles` (kein Filtern nötig, da es genau ein Board-Team gibt).

**Auswahl-Logik:** Team- und Rollen-Checkboxen bleiben technisch vollständig unabhängige Auswahlkriterien, exakt wie heute — Team-Auswahl kodiert `TEAM:<id>`, Rollen-Auswahl `ROLE:<id>`, beide landen unverändert als eigenständige `RecipientSelection`-Einträge im gespeicherten Job. Die Einrückung ist rein visuell; es gibt keine Kaskadierung (Team anklicken wählt nicht automatisch seine Rollen mit aus oder umgekehrt).

**Datenaufbereitung:** Die Komponente baut zusätzlich zu den bestehenden Pool-Arrays (`parentTeams`, `teamRoles`, `boardTeams`, `boardRoles`) eine abgeleitete Struktur für das Template:

```ts
interface TeamWithRoles {
  team: FieldInstanceDTO;
  roles: FieldInstanceDTO[];
}
```

- `parentTeamGroups: TeamWithRoles[]` — für jedes Team aus `parentTeams`, die Rollen aus `teamRoles` mit passender `teamInstanceId`.
- `boardTeamGroups: TeamWithRoles[]` — für jedes Team aus `boardTeams` (praktisch genau ein Eintrag), alle Rollen aus `boardRoles` ungefiltert.

Diese werden neu berechnet, sobald sich die zugrunde liegenden Pools ändern (nach jedem `loadPool`-Abschluss), analog zum bestehenden `onPoolLoaded()`/`pruneStaleRecipientSelections()`-Mechanismus.

**Template-Umsetzung:** Weiterhin natives `mat-select multiple` mit `mat-optgroup`/`mat-option` (Checkboxen kommen automatisch durch `multiple`). Innerhalb der Optgroups "Elternteams" und "Vorstand" wird über `parentTeamGroups`/`boardTeamGroups` iteriert (äußere `*ngFor` über die Gruppen, innere `*ngFor` über `group.roles`). Rollen-`mat-option`s erhalten eine zusätzliche CSS-Klasse (`recipient-role-option`) für den visuellen Einzug (z.B. `padding-left`), Team-`mat-option`s bleiben ungestylt wie die übrigen Top-Level-Optionen.

**Betroffen ist ausschließlich das Elternteams- und Vorstand-Optgroup-Markup sowie die Datenaufbereitung in der Komponente** — `optionValue()`, `toSelections()`, `pruneStaleRecipientSelections()`, das Backend und das gespeicherte `RecipientSelection`-Format bleiben unverändert, da sich an der Menge und Kodierung der auswählbaren Werte nichts ändert, nur an ihrer visuellen Anordnung im Dropdown.

## Betroffene Dateien

- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts` — neue `TeamWithRoles`-Struktur, `parentTeamGroups`/`boardTeamGroups` Getter oder Felder, Neuberechnung bei Pool-Änderungen.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html` — Elternteams- und Vorstand-Optgroups auf verschachtelte `*ngFor`-Struktur umstellen.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.scss` — Einzugs-Styling für Rollen-Optionen.

## Nicht Teil dieser Änderung

- Keine Änderung der Auswahl-Semantik (keine Kaskadierung Team → Rollen).
- Keine Änderung an `RecipientSelection`, Backend-Validierung oder Migration.
- Keine Änderung an der "Gruppen"-Optgroup.
- Kein neues Kind/Typ für "Team mit Rollen" im Backend — die Gruppierung ist rein eine Frontend-Darstellungsfrage.

## Testing

- Bestehende Frontend-Tests für `MailJobEditorComponent` müssen weiterhin grün sein.
- Neuer Test: Für ein Team mit zugehörigen Rollen (`teamInstanceId` passt) erscheinen die Rollen-Optionen im `parentTeamGroups`-Ergebnis nur unter ihrem Team, nicht unter einem anderen.
- Manueller Smoke-Test: Mailjob-Editor öffnen, Empfänger-Dropdown öffnen, prüfen dass Elternteams mit ihren Rollen eingerückt gruppiert erscheinen, Vorstand ebenso, Auswahl von Team und Rolle unabhängig funktioniert, Speichern liefert weiterhin korrekte `recipientSelections`.
