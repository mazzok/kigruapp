# Mail-Bausteine Rebase + Kochdienst-Baustein-Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebase `feature/mail-template-bausteine` onto `main`, then restrict the Kochdienst-Baustein (block) so it only appears in the editor for Kochdienst-Übersichtsjobs (`kind === 'COOKING_OVERVIEW'`), not in normal mail templates or Kochdienst-Erinnerungen.

**Architecture:** During the rebase, all block-palette wiring that currently hangs off the old monolithic `mail-template-editor.component.ts` is dropped (that file was split by main into a thin CRUD shell + reusable `mail-template-form.component.ts`). The block palette, Quill block interactions, and click-to-edit dialog wiring are then rebuilt fresh against `mail-template-form.component.ts`, which already receives `@Input() kind: MailTemplateKind` and is embedded both by the general editor (`kind='GENERAL'`) and by `CookingOverviewJobsComponent` (`kind='COOKING_OVERVIEW'`, already wired). Visibility is driven by a new `visibleForKinds` field on `MailBlockDefinition`, filtered by `kind` inside the form component — no backend changes.

**Tech Stack:** Angular (standalone components, Reactive Forms), Quill/ngx-quill, Jasmine/Karma, Java/Quarkus backend (untouched by this plan).

## Global Constraints

- No backend changes: `MailBlockRenderer`/`MailTemplateRenderer` rendering stays kind-independent (per approved design spec, section 3).
- The block palette must be visually and functionally absent (not just disabled) for `kind !== 'COOKING_OVERVIEW'`.
- Existing Kochdienst-Baustein behavior (drag/click insert, click-to-edit dialog, preview tab, marker round-trip) must be preserved exactly, just relocated to `mail-template-form.component.ts`.
- Work happens in the worktree `D:\GIT\kigruapp-mail-bausteine` (branch `feature/mail-template-bausteine`). All file paths below are relative to that worktree root unless stated otherwise.

---

### Task 1: Rebase onto main

**Files:**
- Conflict-affected (resolve per policy below): `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`, `.html`, `.scss`, `.spec.ts`
- Possibly touched, low conflict risk: `frontend/src/app/shared/services/mail-template.service.ts`
- Everything else (`mail-block.model.ts`, `mail-block.util.ts`(+spec), `mail-block.blot.ts`, `mail-block-config-dialog/**`, backend `MailBlockRenderer`/registry/migration/preview endpoint) should apply cleanly — they are worktree-only new files with no main-side equivalent.

**Interfaces:**
- Produces: a worktree HEAD on top of main's `800bc4e`, with `MailTemplateKind`, `MailTemplate.kind`, the split `mail-template-editor.component.ts` + `mail-template-form.component.ts`, `CookingOverviewJobsComponent`, and `CookingOverviewJobResource` all present and untouched, plus every worktree-only Bausteine file present and compiling.

- [ ] **Step 1: Confirm clean working tree and start the rebase**

```bash
git -C D:/GIT/kigruapp-mail-bausteine status --porcelain
git -C D:/GIT/kigruapp-mail-bausteine fetch D:/GIT/kigruapp main:main-sync
git -C D:/GIT/kigruapp-mail-bausteine rebase main-sync
```

(If the worktree's `main` remote-tracking ref is already up to date with `D:/GIT/kigruapp`'s `main`, `git rebase main` directly is fine instead of the `fetch`/`main-sync` dance — check with `git -C D:/GIT/kigruapp-mail-bausteine log --oneline -1 main` vs `git -C D:/GIT/kigruapp log --oneline -1 main` first.)

- [ ] **Step 2: Resolve conflicts using this policy, commit by commit**

Three commits are known to conflict, all only in `mail-template-editor.component.{ts,html,scss,spec.ts}`: the commits titled "block palette with drag/drop insert...", "click-to-edit wiring for mail-template bausteine", and "guard corrupt block markers/config so the editor cannot brick". For **each** conflict in exactly these four files, discard the incoming (worktree) hunk and keep main's version — the equivalent functionality is rebuilt fresh in Tasks 3-6 against `mail-template-form.component.ts`:

```bash
git -C D:/GIT/kigruapp-mail-bausteine checkout --ours -- frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts
git -C D:/GIT/kigruapp-mail-bausteine checkout --ours -- frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html
git -C D:/GIT/kigruapp-mail-bausteine checkout --ours -- frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss
git -C D:/GIT/kigruapp-mail-bausteine checkout --ours -- frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
```

(During `git rebase`, `--ours` refers to `main-sync`, the branch being rebased onto — this is the intended target, not a mistake. Only apply this to the four files named above; any other file conflicting in the same commit — e.g. `mail-block.util.ts` — must be resolved normally by reading and merging the actual conflict markers, since it has no main-side equivalent to defer to.)

If the resulting commit becomes empty (no changes left after discarding the editor hunks), continue with:

```bash
git -C D:/GIT/kigruapp-mail-bausteine rebase --continue
```

and if git reports there is nothing to commit, run `git -C D:/GIT/kigruapp-mail-bausteine rebase --skip` instead for that commit.

For any other conflicting file not covered above (e.g. `mail-template.service.ts`, if main's `placeholders(kind)` signature change and the worktree's `previewBlock` addition land near each other), resolve manually by keeping main's changed signatures/logic and re-adding the worktree's additive changes (new methods, new imports) around them — read the conflict markers, there is no blanket rule for these.

- [ ] **Step 3: Finish the rebase and verify it landed cleanly**

```bash
git -C D:/GIT/kigruapp-mail-bausteine status --porcelain
git -C D:/GIT/kigruapp-mail-bausteine log --oneline main-sync..HEAD
```

Expected: clean working tree, and the log shows the worktree's feature commits (block model/util, blot, palette-in-old-editor now empty/skipped or reduced, config dialog, renderer registry, migration, preview endpoint, docs) replayed on top of main's `800bc4e`.

- [ ] **Step 4: Verify the build compiles**

```bash
cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng build --configuration production
```

Expected: build succeeds. If it fails due to leftover references to removed old-editor block members (e.g. `blockDefinitions`, `insertBlock`, `onBlockDragStart`) anywhere outside `mail-template-editor.component.ts` (which Task 1's conflict policy already cleaned), fix those specific references now — they'd only exist if some other file imported them, which is not expected per the earlier codebase investigation.

- [ ] **Step 5: Run the existing frontend test suite and confirm the known baseline**

```bash
cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false
```

Expected: all suites pass except any pre-existing failures already known from `[[project_broken_baseline]]` (main has 1 pre-existing failing frontend test, unrelated to this feature) — confirm any failure matches that known baseline, not a new regression from the rebase.

- [ ] **Step 6: Run the backend test suite**

```bash
cd D:/GIT/kigruapp-mail-bausteine/backend && mvn -q test
```

Expected: all tests pass except the 13 pre-existing known-failing backend tests from `[[project_broken_baseline]]`.

- [ ] **Step 7: If Steps 4-6 required source fixes, commit them**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add -A
git -C D:/GIT/kigruapp-mail-bausteine commit -m "fix: post-rebase compile/test fixups"
```

(Skip this step if nothing needed fixing.)

---

### Task 2: `visibleForKinds` on block definitions

**Files:**
- Modify: `frontend/src/app/shared/models/mail-block.model.ts`
- Create: `frontend/src/app/shared/models/mail-block.model.spec.ts`

**Interfaces:**
- Produces: `MailBlockDefinition.visibleForKinds: MailTemplateKind[]`; `blockDefinitionsForKind(kind: MailTemplateKind): MailBlockDefinition[]` — filters `MAIL_BLOCK_DEFINITIONS` to entries whose `visibleForKinds` includes `kind`. Consumed by Task 3.

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/app/shared/models/mail-block.model.spec.ts
import { blockDefinitionsForKind, MAIL_BLOCK_DEFINITIONS } from './mail-block.model';

describe('blockDefinitionsForKind', () => {
  it('gibt den Kochdienst-Baustein nur fuer COOKING_OVERVIEW zurueck', () => {
    expect(blockDefinitionsForKind('COOKING_OVERVIEW')).toEqual(MAIL_BLOCK_DEFINITIONS);
  });

  it('gibt keine Bausteine fuer GENERAL zurueck', () => {
    expect(blockDefinitionsForKind('GENERAL')).toEqual([]);
  });

  it('gibt keine Bausteine fuer COOKING_REMINDER zurueck', () => {
    expect(blockDefinitionsForKind('COOKING_REMINDER')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-block.model.spec.ts'`
Expected: FAIL with `blockDefinitionsForKind is not a function` (or TS compile error, since the export doesn't exist yet).

- [ ] **Step 3: Implement**

```typescript
// frontend/src/app/shared/models/mail-block.model.ts
import { MailTemplateKind } from './mail-template.model';

export interface MailBlockDefinition {
  type: string;
  label: string;
  icon: string;
  visibleForKinds: MailTemplateKind[];
}

export interface CookingDutyBlockConfig {
  type: 'cookingDuty';
  groupId: string;
  periodUnit: 'week' | 'month';
  periodAmount: number;
}

export type MailBlockConfig = CookingDutyBlockConfig;

export const MAIL_BLOCK_DEFINITIONS: MailBlockDefinition[] = [
  { type: 'cookingDuty', label: 'Kochdienst-Tabelle', icon: 'restaurant', visibleForKinds: ['COOKING_OVERVIEW'] },
];

export const DEFAULT_BLOCK_CONFIG: Record<string, MailBlockConfig> = {
  cookingDuty: { type: 'cookingDuty', groupId: '', periodUnit: 'week', periodAmount: 2 },
};

/** Dropdown range for the "Anzahl" select in the block config dialog. */
export const PERIOD_AMOUNT_OPTIONS: number[] = Array.from({ length: 12 }, (_, i) => i + 1);

/** Bausteine, die im Editor fuer die angegebene Vorlagen-Art angeboten werden. */
export function blockDefinitionsForKind(kind: MailTemplateKind): MailBlockDefinition[] {
  return MAIL_BLOCK_DEFINITIONS.filter((def) => def.visibleForKinds.includes(kind));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-block.model.spec.ts'`
Expected: PASS (3 specs).

- [ ] **Step 5: Commit**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/shared/models/mail-block.model.ts frontend/src/app/shared/models/mail-block.model.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine commit -m "feat(fe): scope block definitions to template kinds via visibleForKinds"
```

---

### Task 3: Block palette in `mail-template-form.component.ts`, filtered by kind

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.html`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`
- Reference (read-only, already correct): `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.ts` (`blockSpan`), `frontend/src/app/shared/models/mail-block.model.ts` (`DEFAULT_BLOCK_CONFIG`, `blockDefinitionsForKind`)

**Interfaces:**
- Consumes: `blockDefinitionsForKind(kind: MailTemplateKind): MailBlockDefinition[]` from Task 2; `blockSpan(blockType: string, config: MailBlockConfig, summary: string): string` from `mail-block.util.ts`.
- Produces: `MailTemplateFormComponent.blockDefinitions: MailBlockDefinition[]` (getter, filtered by `this.kind`); `insertBlock(def: MailBlockDefinition): void`; `onBlockDragStart(event: DragEvent, def: MailBlockDefinition): void`; extends `onEditorDrop`/`dropIndex` to also accept block drags. Consumed by Task 4 (click-to-edit) and by the component's own template.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`:

```typescript
  it('zeigt den Kochdienst-Baustein nur bei kind=COOKING_OVERVIEW', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();

    expect(component.blockDefinitions.map((d) => d.type)).toEqual(['cookingDuty']);
  });

  it('zeigt keinen Baustein bei kind=GENERAL', () => {
    component.kind = 'GENERAL';
    fixture.detectChanges();

    expect(component.blockDefinitions).toEqual([]);
  });

  it('zeigt keinen Baustein bei kind=COOKING_REMINDER', () => {
    component.kind = 'COOKING_REMINDER';
    fixture.detectChanges();

    expect(component.blockDefinitions).toEqual([]);
  });

  it('fuegt beim Klick auf einen Baustein-Chip einen Block in den Editor ein (COOKING_OVERVIEW)', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();

    component.insertBlock(component.blockDefinitions[0]);

    expect(component.form.value.bodyHtml).toContain('data-block-type="cookingDuty"');
  });
```

Add the palette markup to `mail-template-form.component.html`, right after the existing placeholder chip-bar (`</div>` closing the `*ngFor="let group of groups"` block) and before `<label class="field-label">Inhalt</label>`:

```html
<div class="chip-bar block-bar" *ngIf="blockDefinitions.length">
  <span class="chip-hint">Bausteine einfügen (klicken oder in den Text ziehen):</span>
  <button class="chip block-chip" type="button"
          *ngFor="let def of blockDefinitions"
          draggable="true"
          (dragstart)="onBlockDragStart($event, def)"
          (click)="insertBlock(def)">
    <mat-icon>{{ def.icon }}</mat-icon> {{ def.label }}
  </button>
</div>
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: FAIL — `blockDefinitions`/`insertBlock`/`onBlockDragStart` do not exist on the component yet (compile error), and the template references undefined members.

- [ ] **Step 3: Implement in `mail-template-form.component.ts`**

Add these imports:

```typescript
import { MatIconModule } from '@angular/material/icon'; // already imported — keep as-is
import { blockSpan } from './mail-block.util';
import {
  blockDefinitionsForKind, DEFAULT_BLOCK_CONFIG, MailBlockDefinition, MailBlockConfig,
} from '../../../shared/models/mail-block.model';
```

Add the constant next to `DRAG_MIME`:

```typescript
const BLOCK_DRAG_MIME = 'application/x-mail-block';
```

Add inside the class body (near `placeholders`/`groups` fields):

```typescript
  get blockDefinitions(): MailBlockDefinition[] {
    return blockDefinitionsForKind(this.kind);
  }
```

Add these methods (mirroring the removed old-editor logic, unchanged in behavior):

```typescript
  private insertBlockAt(index: number, blockType: string): void {
    const config = DEFAULT_BLOCK_CONFIG[blockType];
    if (!config) {
      return;
    }
    const summary = this.summaryFor(blockType, config);
    this.quillInstance.insertEmbed(index, 'mail-block', { blockType, config, summary });
    this.quillInstance.setSelection(index + 1, 0);
    this.syncBodyFromQuill();
  }

  /** Click-insert at the cursor (or append if there is no live editor yet). */
  insertBlock(def: MailBlockDefinition): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertBlockAt(index, def.type);
    } else {
      const config = DEFAULT_BLOCK_CONFIG[def.type];
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + blockSpan(def.type, config, this.summaryFor(def.type, config)) });
    }
  }

  onBlockDragStart(event: DragEvent, def: MailBlockDefinition): void {
    event.dataTransfer?.setData(BLOCK_DRAG_MIME, def.type);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  /**
   * Resolves the human-readable card text for a block's current config. Only
   * `cookingDuty` exists today. `this.groups` is empty until Task 4 wires
   * group loading, so the group name falls back to "Gruppe wählen" until then
   * — that's expected and covered by Task 4's own tests, not a bug to fix here.
   */
  private summaryFor(blockType: string, config: MailBlockConfig): string {
    if (blockType === 'cookingDuty') {
      const cfg = config as CookingDutyBlockConfig;
      const group = this.groups.find((g) => g.id === cfg.groupId);
      return cookingDutyBlockSummary(cfg, group ? instanceLabel(group) : null);
    }
    return 'Baustein';
  }
```

Import `cookingDutyBlockSummary`, `instanceLabel`, and `CookingDutyBlockConfig` alongside the other `mail-block.util`/`mail-block.model` imports above. Add an empty `groups: FieldInstanceDTO[] = [];` field to the class now (Task 4 populates it) — import `FieldInstanceDTO` from `../../../shared/models/field-instance.model`.

Extend `onEditorDrop` to handle block drags before the existing placeholder-drag handling:

```typescript
  onEditorDrop(event: DragEvent): void {
    const blockType = event.dataTransfer?.getData(BLOCK_DRAG_MIME);
    if (blockType && this.quillInstance) {
      event.preventDefault();
      this.insertBlockAt(this.dropIndex(event), blockType);
      return;
    }
    const token = event.dataTransfer?.getData(DRAG_MIME);
    if (!token || !this.quillInstance) {
      return;
    }
    event.preventDefault();
    const tile = this.placeholders.find((p) => p.token === token);
    if (!tile) {
      return;
    }
    this.insertPillAt(this.dropIndex(event), tile);
  }
```

(`dropIndex` already exists in this file unchanged — no edit needed there.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: PASS, including the 4 new specs.

- [ ] **Step 5: Commit**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.html frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine commit -m "feat(fe): block palette in mail-template-form, scoped to COOKING_OVERVIEW"
```

---

### Task 4: Click-to-edit dialog wiring + loading existing block markers

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`

**Interfaces:**
- Consumes: `MailBlockConfigDialogComponent`, `MailBlockConfigDialogData` from `./mail-block-config-dialog/mail-block-config-dialog.component`; `markersToEmbeds(html, resolveSummary)`, `embedsToMarkers(html)`, `cookingDutyBlockSummary`, `instanceLabel` from `./mail-block.util`; `OrganisationService.getByTag(tag)`, `FieldInstanceService.listByDefinitionId(id)` (both already used elsewhere in this pattern, e.g. `CookingOverviewJobsComponent.loadPool`).
- Produces: real group-aware `summaryFor` (replacing Task 3's placeholder); groups loaded into `this.groups: FieldInstanceDTO[]` when `kind === 'COOKING_OVERVIEW'`; clicking a block's edit button opens `MailBlockConfigDialogComponent` and applies the result in place; `applyValue` renders stored `{{block.type:config}}` markers as block cards.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`. This requires extending the test module's providers with fakes for `OrganisationService`/`FieldInstanceService` and `MatDialog`. Add near the top of the spec file, alongside the existing fakes:

```typescript
import { MatDialog } from '@angular/material/dialog';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';

class FakeOrganisationService {
  getByTag() {
    return of({ definitions: [{ id: 'def1', fieldName: 'group', outdatedAt: null }] });
  }
}

class FakeFieldInstanceService {
  listByDefinitionId() {
    return of([{ id: 'g1', fieldName: 'group', value: { label: 'Rote Gruppe' } }]);
  }
}
```

Add the providers to `TestBed.configureTestingModule` (alongside the existing `MailTemplateService` provider):

```typescript
      providers: [
        { provide: MailTemplateService, useValue: service },
        { provide: OrganisationService, useValue: new FakeOrganisationService() },
        { provide: FieldInstanceService, useValue: new FakeFieldInstanceService() },
      ],
```

Add the new spec cases:

```typescript
  it('laedt Gruppen fuer COOKING_OVERVIEW', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();

    expect(component.groups.map((g) => g.id)).toEqual(['g1']);
  });

  it('rendert einen gespeicherten Block-Marker als Baustein-Karte', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();
    const marker = '{{block.cookingDuty:eyJ0eXBlIjoiY29va2luZ0R1dHkiLCJncm91cElkIjoiZzEiLCJwZXJpb2RVbml0Ijoid2VlayIsInBlcmlvZEFtb3VudCI6Mn0}}';

    component.value = { name: 'x', bodyHtml: marker };

    expect(component.form.value.bodyHtml).toContain('data-block-type="cookingDuty"');
  });

  it('oeffnet den Konfigurations-Dialog beim Klick auf den Bearbeiten-Button eines Blocks', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();
    const dialog = TestBed.inject(MatDialog);
    spyOn(dialog, 'open').and.callThrough();
    const node = document.createElement('div');
    node.setAttribute('data-block-type', 'cookingDuty');
    node.setAttribute('data-config', JSON.stringify({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 }));

    (component as any).editBlock(node);

    expect(dialog.open).toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: FAIL — `groups` stays empty (no loading wired), `editBlock` doesn't exist, `applyValue` doesn't call `markersToEmbeds`.

- [ ] **Step 3: Implement in `mail-template-form.component.ts`**

Add imports (`FieldInstanceDTO`, `cookingDutyBlockSummary`, `instanceLabel`, `CookingDutyBlockConfig` are already imported from Task 3 — don't duplicate them):

```typescript
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { markersToEmbeds, embedsToMarkers } from './mail-block.util';
import { MailBlockConfigDialogComponent, MailBlockConfigDialogData } from './mail-block-config-dialog/mail-block-config-dialog.component';
```

Add `MatDialogModule` to the `@Component({ imports: [...] })` array.

The `groups: FieldInstanceDTO[] = [];` field already exists from Task 3 — inject the two services + `MatDialog` in the constructor so `loadGroups` (added below) can populate it:

```typescript
  constructor(
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
    private organisationService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }
```

In `ngOnInit`, after the existing `this.mailTemplateService.placeholders(...)` subscription, load groups only when relevant:

```typescript
    if (this.kind === 'COOKING_OVERVIEW') {
      this.loadGroups();
    }
```

Add the loader method:

```typescript
  private loadGroups(): void {
    this.organisationService.getByTag('groups').subscribe({
      next: (org) => {
        const groupDef = org?.definitions?.find((d) => d.fieldName === 'group' && !d.outdatedAt);
        if (!groupDef?.id) {
          this.groups = [];
          return;
        }
        this.fieldInstanceService.listByDefinitionId(groupDef.id).subscribe((instances) => (this.groups = instances));
      },
      error: () => (this.groups = []),
    });
  }
```

`summaryFor` from Task 3 already reads `this.groups` — no change needed there; it will start returning real group names as soon as `loadGroups` (above) populates `this.groups`.

Wire the click-to-edit handler. In `onEditorCreated`, register the root click listener:

```typescript
  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
    editor.root.addEventListener('click', this.onEditorRootClick);
  }

  private onEditorRootClick = (event: MouseEvent): void => {
    const target = event.target as HTMLElement;
    const btn = target.closest('.mail-block-edit-btn') as HTMLElement | null;
    if (!btn) {
      return;
    }
    const node = btn.closest('[data-block-type]') as HTMLElement | null;
    if (!node) {
      return;
    }
    this.editBlock(node);
  };

  private editBlock(node: HTMLElement): void {
    const blockType = node.getAttribute('data-block-type') ?? '';
    let config: MailBlockConfig;
    try {
      config = JSON.parse(node.getAttribute('data-config') ?? '{}') as MailBlockConfig;
    } catch {
      config = {} as MailBlockConfig;
    }
    const ref = this.dialog.open(MailBlockConfigDialogComponent, {
      width: '420px',
      data: { blockType, config, groups: this.groups } as MailBlockConfigDialogData,
    });
    ref.afterClosed().subscribe((result: MailBlockConfig | undefined) => {
      if (!result) {
        return;
      }
      node.setAttribute('data-config', JSON.stringify(result));
      const summaryEl = node.querySelector('.mail-block-summary');
      if (summaryEl) {
        summaryEl.textContent = this.summaryFor(blockType, result);
      }
      this.syncBodyFromQuill();
    });
  }
```

Finally, make `applyValue` render stored block markers as embeds, mirroring what it already does for placeholder tokens:

```typescript
  private applyValue(v: { name: string; bodyHtml: string }): void {
    const withPills = this.placeholdersLoaded
      ? tokensToPills(v.bodyHtml, this.placeholders)
      : v.bodyHtml;
    const bodyHtml = markersToEmbeds(withPills, (type, cfg) => this.summaryFor(type, cfg));
    this.form.patchValue({ name: v.name, bodyHtml }, { emitEvent: false });
    this.updatePreview(this.form.value.bodyHtml ?? '');
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: PASS, including the 3 new specs. Also re-run the full spec file to confirm the earlier `laedt die Platzhalter fuer die uebergebene Art` etc. tests (which don't set `kind='COOKING_OVERVIEW'` and thus don't trigger `loadGroups`) still pass unchanged.

- [ ] **Step 5: Commit**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine commit -m "feat(fe): click-to-edit wiring and marker-to-embed rendering for mail-bausteine"
```

---

### Task 5: Save round-trip — embeds back to stored markers

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`

**Interfaces:**
- Consumes: `embedsToMarkers(html: string): string` from `./mail-block.util` (already imported in Task 4).
- Produces: `currentValue()` returns `bodyHtml` with block cards converted back to `{{block.type:config}}` markers, so `MailTemplateService.create`/`update` and `CookingOverviewJobsComponent`'s save both persist the marker form, not the live editor DOM.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`:

```typescript
  it('wandelt eingefuegte Bloecke beim Auslesen in gespeicherte Marker um', () => {
    component.kind = 'COOKING_OVERVIEW';
    fixture.detectChanges();

    component.insertBlock(component.blockDefinitions[0]);
    const value = component.currentValue();

    expect(value.bodyHtml).toMatch(/\{\{block\.cookingDuty:[A-Za-z0-9_-]+\}\}/);
    expect(value.bodyHtml).not.toContain('data-block-type');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: FAIL — `value.bodyHtml` still contains the raw `data-block-type` card markup because `currentValue()` doesn't call `embedsToMarkers` yet.

- [ ] **Step 3: Implement**

```typescript
  currentValue(): { name: string; bodyHtml: string } {
    return {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(embedsToMarkers(this.form.value.bodyHtml ?? '')),
    };
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-form.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine commit -m "fix(fe): convert block embeds back to stored markers on save"
```

---

### Task 6: Regression check — GENERAL editor and COOKING_REMINDER stay block-free end to end

**Files:**
- Test only: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` (GENERAL editor shell, untouched by Tasks 2-5 — this task only adds a guard test)

**Interfaces:**
- Consumes: `MailTemplateEditorComponent` (unchanged from main post-rebase), `MailTemplateFormComponent.blockDefinitions` (Task 3).
- Produces: nothing new — a regression test only.

- [ ] **Step 1: Write the test**

Append to `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` (adjust the `TestBed` query to whatever the file already uses to render the embedded `app-mail-template-form`, matching its existing style):

```typescript
  it('reicht kind=GENERAL an die eingebettete Vorlagen-Maske durch, ohne Baustein-Palette', () => {
    fixture.detectChanges();
    component.newTemplate();
    fixture.detectChanges();

    const formEl = fixture.nativeElement.querySelector('app-mail-template-form');
    expect(formEl).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.block-bar')).toBeNull();
  });
```

- [ ] **Step 2: Run test to verify it passes as-is**

Run: `cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS immediately — this is a regression guard confirming Task 3's `*ngIf="blockDefinitions.length"` already keeps `.block-bar` out of the DOM for `kind='GENERAL'`. If it fails, that means Task 3's filtering isn't actually reaching the GENERAL editor path — stop and re-check Task 3's `mail-template-form.component.html` guard before proceeding.

- [ ] **Step 3: Commit**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
git -C D:/GIT/kigruapp-mail-bausteine commit -m "test(fe): regression guard — GENERAL editor has no block palette"
```

---

### Task 7: Full regression pass + design doc addenda

**Files:**
- Modify: `docs/superpowers/specs/2026-08-04-mail-template-bausteine-design.md`
- Modify: `docs/superpowers/specs/2026-08-04-mail-template-bausteine-vorschau-design.md`

**Interfaces:** none — documentation and final verification only.

- [ ] **Step 1: Append the addendum to both design docs**

Add this section to the end of `docs/superpowers/specs/2026-08-04-mail-template-bausteine-design.md`:

```markdown
## Nachtrag 2026-08-05: Kind-Beschränkung

Seit der Rebase auf main (nach der Aufteilung in `MailTemplateKind.COOKING_REMINDER`/
`COOKING_OVERVIEW`) ist der Kochdienst-Baustein ausschließlich im Editor für
Kochdienst-Übersichtsjobs (`kind === 'COOKING_OVERVIEW'`) verfügbar, nicht mehr in
allgemeinen Mail-Vorlagen oder bei Kochdienst-Erinnerungen. Details siehe
[2026-08-05-mail-bausteine-rebase-kochdienst-scoping-design.md](2026-08-05-mail-bausteine-rebase-kochdienst-scoping-design.md).
```

Add the same section (verbatim) to the end of `docs/superpowers/specs/2026-08-04-mail-template-bausteine-vorschau-design.md`.

- [ ] **Step 2: Run the full frontend and backend test suites once more**

```bash
cd D:/GIT/kigruapp-mail-bausteine/frontend && npx ng test --watch=false
cd D:/GIT/kigruapp-mail-bausteine/backend && mvn -q test
```

Expected: same pass/fail profile as Task 1 Steps 5-6 (only the pre-existing known baseline failures from `[[project_broken_baseline]]`), plus all new specs from Tasks 2-6 passing.

- [ ] **Step 3: Commit the doc addenda**

```bash
git -C D:/GIT/kigruapp-mail-bausteine add docs/superpowers/specs/2026-08-04-mail-template-bausteine-design.md docs/superpowers/specs/2026-08-04-mail-template-bausteine-vorschau-design.md
git -C D:/GIT/kigruapp-mail-bausteine commit -m "docs: note Kochdienst-Baustein kind-scoping in bausteine design specs"
```
