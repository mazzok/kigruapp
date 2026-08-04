# Mail-Template-Bausteine: Vorschau im Konfig-Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Vorschau" tab to the mail-template Baustein config dialog that renders the block's actual output (via the same `MailBlockRenderer` registry used at send time), loaded on tab switch and cached until the config changes.

**Architecture:** A new generic backend endpoint (`POST /api/v1/mail-templates/blocks/preview`) accepts the raw block config JSON, finds the matching `MailBlockRenderer`, and returns its rendered HTML — zero duplicate rendering logic. The dialog gains a second Material tab that calls this endpoint only when switched to and only if the config differs from what was last previewed.

**Tech Stack:** Quarkus (CDI `@All`, Jackson `JsonNode`), Angular standalone components, Angular Material Tabs.

## Global Constraints

- Preview must render through the exact same `MailBlockRenderer` implementations used at send time — no second render path.
- The preview endpoint inherits admin-only access automatically (it lives under `/api/v1/mail-templates`, which `SecurityFilter` does not whitelist — default-deny applies).
- No live/auto-updating preview on every form change — load only on tab switch, and only re-load if the config changed since the last load.
- Don't fire the preview request when the form is invalid (no group chosen) — show a hint instead.
- German UI copy ("Konfiguration", "Vorschau", "Lädt...", "Vorschau nicht verfügbar.", "Bitte zuerst eine Gruppe wählen.").

---

## File Structure

**Backend — modified:**
- `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java` — new `POST /blocks/preview` endpoint + `BlockPreviewResponse` record.
- `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java` — 3 new tests.

**Frontend — modified:**
- `frontend/src/app/shared/services/mail-template.service.ts` — new `previewBlock()` method.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts` (+ `.html`) — tab structure, preview loading/caching logic.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts` — constructor gets 2 new dependencies; new tests.

---

### Task 1: Backend preview endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`

**Interfaces:**
- Consumes: `MailBlockRenderer` (`supports(String)`, `render(JsonNode)`), CDI `@All List<MailBlockRenderer>` (same mechanism `MailTemplateRenderer` already uses) — both pre-existing from the earlier Bausteine feature.
- Produces: `POST /api/v1/mail-templates/blocks/preview` — request body is the raw block config JSON (must contain a `"type"` field); response body `{"html": "..."}` — consumed by Task 2's `MailTemplateService.previewBlock()`.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`, inside the `MailTemplateResourceTest` class (after the existing `createRejectsBlankName` test) — also add `import static org.hamcrest.Matchers.containsString;` to the existing import block at the top of the file:

```java
    @Test
    void previewBlockRendersHintWhenGroupMissing() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"cookingDuty\",\"groupId\":\"000000000000000000000001\",\"periodUnit\":\"week\",\"periodAmount\":2}")
                .when().post("/api/v1/mail-templates/blocks/preview")
                .then().statusCode(200)
                .body("html", containsString("Gruppe nicht mehr vorhanden."));
    }

    @Test
    void previewBlockRejectsMissingType() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"groupId\":\"000000000000000000000001\"}")
                .when().post("/api/v1/mail-templates/blocks/preview")
                .then().statusCode(400);
    }

    @Test
    void previewBlockReturns404WhenNoRendererSupportsType() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"unknownBlockType\"}")
                .when().post("/api/v1/mail-templates/blocks/preview")
                .then().statusCode(404);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=MailTemplateResourceTest test`
Expected: FAIL — `404 Not Found` for the new path (the endpoint doesn't exist yet, so all three requests 404 or otherwise don't match the expected status/body).

- [ ] **Step 3: Write the minimal implementation**

Modify `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`.

Add these imports (alongside the existing ones):

```java
import at.kigruapp.service.MailBlockRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.All;
import jakarta.inject.Inject;
```

Add this field near the top of the class (after the `SCALAR_PERSON_FIELD_ALLOWLIST` constant):

```java
    @Inject
    @All
    List<MailBlockRenderer> blockRenderers;
```

Add this record (alongside the existing `PlaceholderTile` record):

```java
    public record BlockPreviewResponse(String html) {}
```

Add this method (e.g. after `placeholders()`):

```java
    @POST
    @Path("/blocks/preview")
    public BlockPreviewResponse previewBlock(JsonNode config) {
        String blockType = config != null ? config.path("type").asText(null) : null;
        if (blockType == null || blockType.isBlank()) {
            throw new BadRequestException("type is required");
        }
        for (MailBlockRenderer renderer : blockRenderers) {
            if (renderer.supports(blockType)) {
                return new BlockPreviewResponse(renderer.render(config));
            }
        }
        throw new NotFoundException("no renderer for block type: " + blockType);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=MailTemplateResourceTest test`
Expected: PASS (12 tests: 9 pre-existing + 3 new).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: same pre-existing baseline of unrelated failures as before this change (no new failures) — compare the failing test class/method names against the baseline, don't just compare counts.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java
git commit -m "feat(be): add block preview endpoint reusing the MailBlockRenderer registry"
```

---

### Task 2: Frontend "Vorschau" tab in the block config dialog

**Files:**
- Modify: `frontend/src/app/shared/services/mail-template.service.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.html`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts`

**Interfaces:**
- Consumes: `POST /api/v1/mail-templates/blocks/preview` (Task 1) via `ApiService.post`; existing `MailBlockConfig` type (`shared/models/mail-block.model.ts`).
- Produces: `MailTemplateService.previewBlock(config: MailBlockConfig): Observable<{ html: string }>` — usable by any future caller that needs a block preview, not just this dialog.

- [ ] **Step 1: Write the failing tests**

Replace the full contents of `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts` with:

```ts
import { of, throwError } from 'rxjs';
import { MailBlockConfigDialogComponent } from './mail-block-config-dialog.component';
import { MatDialogRef } from '@angular/material/dialog';
import { DomSanitizer } from '@angular/platform-browser';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { MailTemplateService } from '../../../../shared/services/mail-template.service';

const GROUPS: FieldInstanceDTO[] = [
  { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
];

const fakeSanitizer = { bypassSecurityTrustHtml: (v: string) => v } as unknown as DomSanitizer;

class FakeDialogRef {
  closedWith: unknown;
  close(result?: unknown) {
    this.closedWith = result;
  }
}

class FakeMailTemplateService {
  previewCalls: MailBlockConfig[] = [];
  previewResult: { html: string } = { html: '<table></table>' };
  previewShouldError = false;

  previewBlock(config: MailBlockConfig) {
    this.previewCalls.push(config);
    if (this.previewShouldError) {
      return throwError(() => new Error('preview failed'));
    }
    return of(this.previewResult);
  }
}

describe('MailBlockConfigDialogComponent', () => {
  const config: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };
  let dialogRef: FakeDialogRef;
  let mailTemplateService: FakeMailTemplateService;
  let component: MailBlockConfigDialogComponent;

  beforeEach(() => {
    dialogRef = new FakeDialogRef();
    mailTemplateService = new FakeMailTemplateService();
    component = new MailBlockConfigDialogComponent(
      dialogRef as unknown as MatDialogRef<MailBlockConfigDialogComponent>,
      { blockType: 'cookingDuty', config, groups: GROUPS },
      mailTemplateService as unknown as MailTemplateService,
      fakeSanitizer,
    );
  });

  it('builds a form pre-filled from the given config', () => {
    expect(component.form.value).toEqual({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 });
  });

  it('is invalid when no group is chosen', () => {
    component.form.patchValue({ groupId: '' });
    expect(component.form.invalid).toBe(true);
  });

  it('save() closes the dialog with the form value when valid', () => {
    component.form.patchValue({ periodAmount: 4 });
    component.save();
    expect(dialogRef.closedWith).toEqual({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 4 });
  });

  it('save() does nothing when the form is invalid', () => {
    component.form.patchValue({ groupId: '' });
    component.save();
    expect(dialogRef.closedWith).toBeUndefined();
  });

  it('cancel() closes the dialog without a result', () => {
    component.cancel();
    expect(dialogRef.closedWith).toBeUndefined();
  });

  it('does not call previewBlock when switching to the Konfiguration tab (index 0)', () => {
    component.onTabChange(0);
    expect(mailTemplateService.previewCalls.length).toBe(0);
  });

  it('calls previewBlock once when switching to the Vorschau tab with a valid form', () => {
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(1);
    expect(mailTemplateService.previewCalls[0]).toEqual(config);
    expect(component.previewHtml).toBe('<table></table>');
    expect(component.previewLoading).toBe(false);
    expect(component.previewError).toBe(false);
  });

  it('does not call previewBlock again on a repeated switch without a config change', () => {
    component.onTabChange(1);
    component.onTabChange(0);
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(1);
  });

  it('calls previewBlock again after the config changes and the tab is revisited', () => {
    component.onTabChange(1);
    component.form.patchValue({ periodAmount: 4 });
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(2);
  });

  it('does not call previewBlock when the form is invalid', () => {
    component.form.patchValue({ groupId: '' });
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(0);
    expect(component.previewHtml).toBe('');
  });

  it('sets previewError and clears loading when the request fails', () => {
    mailTemplateService.previewShouldError = true;
    component.onTabChange(1);
    expect(component.previewError).toBe(true);
    expect(component.previewLoading).toBe(false);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block-config-dialog.component.spec.ts'`
Expected: FAIL — constructor arity mismatch (`Expected 2 arguments, but got 4`) and missing members `onTabChange`/`previewHtml`/`previewLoading`/`previewError`.

- [ ] **Step 3: Write the minimal implementation**

Modify `frontend/src/app/shared/services/mail-template.service.ts` — add the import and method:

```ts
import { MailBlockConfig } from '../models/mail-block.model';
```

```ts
  previewBlock(config: MailBlockConfig): Observable<{ html: string }> {
    return this.api.post<{ html: string }>('/mail-templates/blocks/preview', config);
  }
```

Replace the full contents of `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts` with:

```ts
import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { MailTemplateService } from '../../../../shared/services/mail-template.service';
import { CookingDutyBlockConfigComponent } from './cooking-duty-block-config.component';

export interface MailBlockConfigDialogData {
  blockType: string;
  config: MailBlockConfig;
  groups: FieldInstanceDTO[];
}

const PREVIEW_TAB_INDEX = 1;

@Component({
  selector: 'app-mail-block-config-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatButtonModule, MatTabsModule,
    CookingDutyBlockConfigComponent,
  ],
  templateUrl: './mail-block-config-dialog.component.html',
})
export class MailBlockConfigDialogComponent {
  form: FormGroup;

  previewHtml: SafeHtml = '';
  previewLoading = false;
  previewError = false;

  /** JSON snapshot of the config the preview was last loaded for; null until the first successful/attempted load. */
  private lastPreviewedConfigJson: string | null = null;

  constructor(
    private dialogRef: MatDialogRef<MailBlockConfigDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: MailBlockConfigDialogData,
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
  ) {
    this.form = this.buildForm(data.blockType, data.config);
  }

  private buildForm(blockType: string, config: MailBlockConfig): FormGroup {
    if (blockType === 'cookingDuty') {
      const cfg = config as CookingDutyBlockConfig;
      return new FormGroup({
        type: new FormControl<'cookingDuty'>('cookingDuty', { nonNullable: true }),
        groupId: new FormControl(cfg.groupId, Validators.required),
        periodUnit: new FormControl<'week' | 'month'>(cfg.periodUnit, { nonNullable: true, validators: Validators.required }),
        periodAmount: new FormControl(cfg.periodAmount, { nonNullable: true, validators: Validators.required }),
      });
    }
    return new FormGroup({});
  }

  /** Loads the preview only when switching to the Vorschau tab, and only if the config changed since the last load. */
  onTabChange(index: number): void {
    if (index !== PREVIEW_TAB_INDEX) {
      return;
    }
    if (this.form.invalid) {
      this.previewHtml = '';
      this.previewError = false;
      return;
    }
    const currentConfigJson = JSON.stringify(this.form.value);
    if (currentConfigJson === this.lastPreviewedConfigJson) {
      return;
    }
    this.previewLoading = true;
    this.previewError = false;
    this.mailTemplateService.previewBlock(this.form.value as MailBlockConfig).subscribe({
      next: (result) => {
        this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(result.html);
        this.previewLoading = false;
        this.lastPreviewedConfigJson = currentConfigJson;
      },
      error: () => {
        this.previewError = true;
        this.previewLoading = false;
      },
    });
  }

  save(): void {
    if (this.form.invalid) {
      return;
    }
    this.dialogRef.close(this.form.value as MailBlockConfig);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

Replace the full contents of `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.html` with:

```html
<h2 mat-dialog-title>Baustein konfigurieren</h2>
<mat-dialog-content>
  <mat-tab-group (selectedTabChange)="onTabChange($event.index)">
    <mat-tab label="Konfiguration">
      <app-cooking-duty-block-config *ngIf="data.blockType === 'cookingDuty'"
        [form]="form" [groups]="data.groups"></app-cooking-duty-block-config>
    </mat-tab>
    <mat-tab label="Vorschau">
      <div class="preview-tab">
        <p *ngIf="form.invalid">Bitte zuerst eine Gruppe wählen.</p>
        <p *ngIf="!form.invalid && previewLoading">Lädt...</p>
        <p *ngIf="!form.invalid && previewError">Vorschau nicht verfügbar.</p>
        <div *ngIf="!form.invalid && !previewLoading && !previewError" [innerHTML]="previewHtml"></div>
      </div>
    </mat-tab>
  </mat-tab-group>
</mat-dialog-content>
<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="cancel()">Abbrechen</button>
  <button mat-flat-button color="primary" type="button" [disabled]="form.invalid" (click)="save()">Übernehmen</button>
</mat-dialog-actions>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block-config-dialog.component.spec.ts'`
Expected: PASS (13 specs: 5 pre-existing + 8 new).

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx ng test --watch=false`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/services/mail-template.service.ts frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.html frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts
git commit -m "feat(fe): Vorschau tab in the block config dialog, loaded on tab switch"
```

---

## Manual Smoke Test (after both tasks)

1. Open **Einstellungen → Mail → Vorlagen**, drag the "Kochdienst-Tabelle" chip into a template, click its edit icon.
2. On the "Konfiguration" tab, pick a real group with real upcoming Kochdienst-Einträge and a period that covers them.
3. Switch to "Vorschau" — confirm the actual table (Datum/Person/Beschreibung) appears, matching what `cooking.component.ts`'s calendar shows for that group/period.
4. Switch back to "Konfiguration", change the group to one with no entries in the period, switch to "Vorschau" again — confirm it reloads and shows "Keine Kochdienst-Einträge im gewählten Zeitraum."
5. Switch tabs back and forth without changing anything — confirm (via browser dev tools network tab) no extra requests fire.
6. Clear the group selection, switch to "Vorschau" — confirm it shows "Bitte zuerst eine Gruppe wählen." instead of firing a request.
