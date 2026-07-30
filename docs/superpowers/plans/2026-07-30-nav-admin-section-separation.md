# Nav Admin-Bereich-Trennung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In der linken Navigationsleiste (`AppComponent`) alle Admin-Punkte hinter einer klickbaren, standardmäßig ausgeklappten Trennlinie mit Text "Administration" und Chevron-Icon gruppieren, klar getrennt von den für alle Eltern sichtbaren Punkten (Kochen, Unsere Stunden).

**Architecture:** Reine Frontend-Änderung an einer bestehenden Komponente (`AppComponent`). Ein neues Boolean-Feld `adminSectionExpanded` (Default `true`, keine Persistierung) steuert per `@if` das Rendering des Admin-Blocks. Ein neuer klickbarer Header-Block in der Sidenav übernimmt die Rolle der bisherigen unbeschrifteten Admin-Sichtbarkeitsgrenze.

**Tech Stack:** Angular (standalone components), Angular Material (`mat-nav-list`, `mat-icon`), Jasmine/Karma für Tests.

## Global Constraints

- Keine neuen Berechtigungen oder Backend-Änderungen (spec: "Nicht Teil dieser Änderung").
- Keine Änderung an Routen oder Zielseiten der Admin-Punkte.
- Keine Persistierung des Klapp-Zustands (kein localStorage).
- Eine einzige Admin-Gruppe; die bestehende `mat-divider` zwischen operativen Punkten (Familien … Stundenübersicht) und Settings-Punkten (Organisation … Mail) bleibt als optische Untergliederung erhalten.
- Für Nicht-Admins ändert sich nichts sichtbar.
- Default-Zustand beim Laden: ausgeklappt.

---

### Task 1: Admin-Sektion mit klickbarem Header und Toggle-Zustand

**Files:**
- Modify: `frontend/src/app/app.component.ts`
- Modify: `frontend/src/app/app.component.html:15-61`
- Modify: `frontend/src/app/app.component.scss`
- Test: `frontend/src/app/app.component.spec.ts`

**Interfaces:**
- Consumes: `CurrentUserService.isAdmin` (bestehender Getter, `frontend/src/app/core/services/current-user.service.ts:47-49`).
- Produces: `AppComponent.adminSectionExpanded: boolean` (Default `true`) und `AppComponent.toggleAdminSection(): void`, die spätere Tasks/Tests referenzieren können.

- [ ] **Step 1: Failing Test für Default-Zustand und Toggle schreiben**

In `frontend/src/app/app.component.spec.ts` folgenden Test-Block ergänzen (nach dem bestehenden `it('should create the app', ...)`-Block, innerhalb desselben `describe`):

```typescript
  it('should default the admin section to expanded', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.adminSectionExpanded).toBeTrue();
  });

  it('should toggle the admin section state', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;

    app.toggleAdminSection();
    expect(app.adminSectionExpanded).toBeFalse();

    app.toggleAdminSection();
    expect(app.adminSectionExpanded).toBeTrue();
  });
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/app.component.spec.ts'`
Expected: FAIL — `adminSectionExpanded` und `toggleAdminSection` existieren nicht auf `AppComponent`.

- [ ] **Step 3: `adminSectionExpanded` und `toggleAdminSection()` implementieren**

In `frontend/src/app/app.component.ts` die Klasse `AppComponent` um das Feld und die Methode ergänzen:

```typescript
export class AppComponent implements OnInit {
  adminSectionExpanded = true;

  constructor(
    public auth: AuthService,
    public currentUser: CurrentUserService,
  ) {}

  toggleAdminSection(): void {
    this.adminSectionExpanded = !this.adminSectionExpanded;
  }

  ngOnInit(): void {
    // Always attempt to load — works in dev mode (no OIDC) and after production login
    this.currentUser.loadCurrentUser().subscribe({ error: () => {} });
    // After Keycloak redirect login the token arrives asynchronously — reload user then too
    this.auth.tokenReceived$.subscribe(() => {
      this.currentUser.loadCurrentUser().subscribe();
    });
  }
}
```

(Nur die neuen Zeilen `adminSectionExpanded = true;` und die `toggleAdminSection()`-Methode werden zur bestehenden Klasse hinzugefügt — `ngOnInit` bleibt unverändert.)

Zusätzlich `MatIconModule` ist bereits importiert (wird für den Chevron gebraucht) — keine weiteren Imports nötig.

- [ ] **Step 4: Test laufen lassen und Erfolg verifizieren**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/app.component.spec.ts'`
Expected: PASS — alle drei Tests grün.

- [ ] **Step 5: Template umbauen — Admin-Block hinter klickbarem Header**

`frontend/src/app/app.component.html` Zeilen 15-61 ersetzen durch:

```html
      @if (currentUser.isAdmin) {
        <button mat-list-item class="admin-section-toggle" (click)="toggleAdminSection()">
          <span class="admin-section-toggle-label">Administration</span>
          <mat-icon class="admin-section-toggle-icon">
            {{ adminSectionExpanded ? 'expand_less' : 'expand_more' }}
          </mat-icon>
        </button>
        @if (adminSectionExpanded) {
          <a mat-list-item routerLink="/administration/families" routerLinkActive="active">
            <mat-icon matListItemIcon>family_restroom</mat-icon>
            <span matListItemTitle>Familien</span>
          </a>
          <a mat-list-item routerLink="/administration/platzzuweisung" routerLinkActive="active">
            <mat-icon matListItemIcon>groups</mat-icon>
            <span matListItemTitle>Platzzuweisung</span>
          </a>
          <a mat-list-item routerLink="/administration/kosten-pro-semester" routerLinkActive="active">
            <mat-icon matListItemIcon>payments</mat-icon>
            <span matListItemTitle>Kosten pro Semester</span>
          </a>
          <a mat-list-item routerLink="/administration/bilanzen" routerLinkActive="active">
            <mat-icon matListItemIcon>account_balance</mat-icon>
            <span matListItemTitle>Bilanzen</span>
          </a>
          <a mat-list-item routerLink="/administration/elterneinteilung" routerLinkActive="active">
            <mat-icon matListItemIcon>assignment_ind</mat-icon>
            <span matListItemTitle>Elterneinteilung</span>
          </a>
          <a mat-list-item routerLink="/administration/board" routerLinkActive="active">
            <mat-icon matListItemIcon>groups</mat-icon>
            <span matListItemTitle>Vorstand</span>
          </a>
          <a mat-list-item routerLink="/administration/stundenuebersicht" routerLinkActive="active">
            <mat-icon matListItemIcon>schedule</mat-icon>
            <span matListItemTitle>Stundenübersicht</span>
          </a>
          <mat-divider></mat-divider>
          <a mat-list-item routerLink="/settings/organisation" routerLinkActive="active">
            <mat-icon matListItemIcon>business</mat-icon>
            <span matListItemTitle>Organisation</span>
          </a>
          <a mat-list-item routerLink="/settings/custom-fields" routerLinkActive="active">
            <mat-icon matListItemIcon>tune</mat-icon>
            <span matListItemTitle>Benutzerdefinierte Felder</span>
          </a>
          <a mat-list-item routerLink="/settings/permissions" routerLinkActive="active">
            <mat-icon matListItemIcon>admin_panel_settings</mat-icon>
            <span matListItemTitle>Berechtigungen</span>
          </a>
          <a mat-list-item routerLink="/settings/mail" routerLinkActive="active">
            <mat-icon matListItemIcon>mail</mat-icon>
            <span matListItemTitle>Mail</span>
          </a>
        }
      }
```

Die umschließende `@if (currentUser.isAdmin)` bleibt bestehen, damit Nicht-Admins weder den "Administration"-Header noch die Punkte sehen (spec-Anforderung: für Nicht-Admins ändert sich nichts sichtbar).

- [ ] **Step 6: Styling für den Section-Header ergänzen**

In `frontend/src/app/app.component.scss` am Ende der Datei ergänzen:

```scss
.admin-section-toggle {
  width: 100%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border: none;
  border-top: 1px solid #e0e0e0;
  background: transparent;
  color: rgba(0, 0, 0, 0.6);
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;

  &:hover {
    background-color: rgba(0, 0, 0, 0.04);
  }
}

.admin-section-toggle-label {
  flex: 1;
  text-align: left;
}

.admin-section-toggle-icon {
  color: rgba(0, 0, 0, 0.54);
}
```

- [ ] **Step 7: Production-Build laufen lassen**

Run: `cd frontend && npx ng build`
Expected: Build erfolgreich, keine Template- oder Compile-Fehler.

- [ ] **Step 8: Vollständige Testsuite für die Datei nochmals laufen lassen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/app.component.spec.ts'`
Expected: PASS — alle Tests grün (Erstellung, Default-Zustand, Toggle).

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.component.html frontend/src/app/app.component.scss frontend/src/app/app.component.spec.ts
git commit -m "feat(fe): Admin-Bereich in Navigation klar abtrennen und ausklappbar machen"
```

---

## Manueller Smoke-Test (nach Task 1, vor Abschluss des Branches)

1. Frontend lokal starten (`cd frontend && npx ng serve`), als Admin einloggen.
2. Prüfen: Kochen und Unsere Stunden stehen fix oben, direkt danach die "Administration"-Trennlinie mit Chevron, standardmäßig ausgeklappt mit allen 11 Punkten inkl. der kleinen `mat-divider` zwischen Stundenübersicht und Organisation.
3. Auf die "Administration"-Trennlinie klicken → alle Admin-Punkte verschwinden, Chevron zeigt `expand_more`. Erneut klicken → Punkte erscheinen wieder, Chevron zeigt `expand_less`.
4. Seite neu laden → Admin-Bereich ist wieder ausgeklappt (kein Persistieren).
5. Als Nicht-Admin einloggen → weder "Administration"-Header noch Admin-Punkte sichtbar; Kochen/Unsere Stunden wie gewohnt vorhanden.
