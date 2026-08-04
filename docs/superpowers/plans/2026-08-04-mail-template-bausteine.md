# Mail-Template-Bausteine (Kochdienst-Baustein) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a generic, drag-and-droppable "Baustein" (block) concept to the mail-template editor, with a first concrete block type that renders a Kochdienst (cooking-duty) table for one group over a relative time period, resolved at send time.

**Architecture:** In the editor, a block is a non-editable Quill block-embed card carrying `data-block-type`/`data-config`. On save it is serialized to a plain-text marker `{{block.<type>:<base64url-config>}}` (mirrors the existing `{{person.xxx}}` placeholder pattern, so it passes through the OWASP sanitizer untouched). `MailTemplateRenderer` decodes these markers at send time and delegates to a CDI registry of `MailBlockRenderer` implementations; `CookingDutyMailBlockRenderer` is the first one, reusing a newly extracted `CookingDutyQueryService`.

**Tech Stack:** Angular standalone components, ngx-quill / Quill custom blots, Angular Material (select, dialog), Quarkus (CDI, MongoDB Panache, RestAssured tests), JUnit 5.

## Global Constraints

- German UI copy throughout (matches the rest of the mail-settings area).
- No new npm/Maven dependencies — no Jsoup, reuse existing `Pattern`/`Matcher` regex style already used in `MailTemplateRenderer`.
- The stored `bodyHtml` representation of a block MUST be plain text (`{{block.<type>:<config>}}`), never an HTML tag/attribute — the OWASP sanitizer in `MailTemplateResource.sanitizeBody` strips any attribute other than `style`/`href`.
- Exactly one group per Kochdienst-Baustein; period is relative to the actual send time (`LocalDate.now(ZoneId.of("Europe/Vienna"))`, consistent with `MailJobScheduler`'s G-006 timezone rule).
- Empty result set → hint text, never an empty table. Missing/deleted group → hint text, never an exception (a broken block must never fail an entire mail send).
- Table columns: Datum, Person, Beschreibung (no food-properties column).

---

## File Structure

**Frontend — new:**
- `frontend/src/app/shared/models/mail-block.model.ts` — block type registry, config types, constants.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.ts` — marker ⇄ embed conversion, summary text, base64url helpers.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block.blot.ts` — Quill block-embed blot.
- `frontend/src/app/settings/mail/mail-block.blot.spec.ts` — mirrors the existing `mail-token.blot.spec.ts` location.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts` (+ `.html`) — generic dialog host.
- `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/cooking-duty-block-config.component.ts` (+ `.html`) — Kochdienst-specific form fields.

**Frontend — modified:**
- `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts` (+ `.html`, `.scss`) — groups loading, block palette, drag/drop insert, click-to-edit, marker/embed conversion on load & save.
- `frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts` — register the new blot.
- `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` — constructor gets 3 new dependencies; new tests.

**Backend — new:**
- `backend/src/main/java/at/kigruapp/service/CookingDutyQueryService.java` — extracted FieldInstance walk + filter, shared by the resource and the new renderer.
- `backend/src/main/java/at/kigruapp/service/MailBlockRenderer.java` — one-method-pair interface.
- `backend/src/main/java/at/kigruapp/service/CookingDutyMailBlockRenderer.java` — first implementation.
- `backend/src/test/java/at/kigruapp/service/CookingDutyQueryServiceTest.java`
- `backend/src/test/java/at/kigruapp/resource/CookingDutyResourceTest.java` (didn't exist — added as a regression safety net for the refactor)
- `backend/src/test/java/at/kigruapp/service/CookingDutyMailBlockRendererTest.java`

**Backend — modified:**
- `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java` — delegates to `CookingDutyQueryService`.
- `backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java` — adds block-marker scanning + rendering.
- `backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java` — new block-rendering tests.

---

### Task 1: Frontend block model + marker/config util (pure, no Angular/Quill)

**Files:**
- Create: `frontend/src/app/shared/models/mail-block.model.ts`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.ts`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.spec.ts`

**Interfaces:**
- Produces: `MailBlockDefinition { type: string; label: string; icon: string }`, `CookingDutyBlockConfig { type: 'cookingDuty'; groupId: string; periodUnit: 'week' | 'month'; periodAmount: number }`, `MailBlockConfig` (= `CookingDutyBlockConfig` today), `MAIL_BLOCK_DEFINITIONS: MailBlockDefinition[]`, `DEFAULT_BLOCK_CONFIG: Record<string, MailBlockConfig>`, `PERIOD_AMOUNT_OPTIONS: number[]` — all consumed by Task 3, 4, 5.
- Produces: `blockSpan(blockType, config, summary): string`, `markersToEmbeds(html, resolveSummary): string`, `embedsToMarkers(html): string`, `cookingDutyBlockSummary(config, groupLabel): string`, `instanceLabel(instance: FieldInstanceDTO): string` — consumed by Task 3, 4, 5.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.spec.ts`:

```ts
import {
  blockSpan, markersToEmbeds, embedsToMarkers, cookingDutyBlockSummary, instanceLabel,
} from './mail-block.util';
import { CookingDutyBlockConfig } from '../../../shared/models/mail-block.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';

const CONFIG: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };

describe('mail-block.util', () => {
  it('blockSpan builds a non-editable card carrying the block type, config and summary', () => {
    const html = blockSpan('cookingDuty', CONFIG, 'Kochdienst: Rote Gruppe, nächste 2 Wochen');
    expect(html).toContain('class="mail-block"');
    expect(html).toContain('data-block-type="cookingDuty"');
    expect(html).toContain('data-config="{&quot;type&quot;:&quot;cookingDuty&quot;,&quot;groupId&quot;:&quot;g1&quot;,&quot;periodUnit&quot;:&quot;week&quot;,&quot;periodAmount&quot;:2}"');
    expect(html).toContain('mail-block-summary">Kochdienst: Rote Gruppe, nächste 2 Wochen<');
    expect(html).toContain('mail-block-edit-btn');
  });

  it('markersToEmbeds replaces a stored marker with the block card, resolving the summary', () => {
    const marker = embedsToMarkers(blockSpan('cookingDuty', CONFIG, 'ignored'));
    const out = markersToEmbeds(`<p>Hallo</p>${marker}`, (type, cfg) => `resolved:${type}:${(cfg as CookingDutyBlockConfig).groupId}`);
    expect(out).toContain('data-block-type="cookingDuty"');
    expect(out).toContain('mail-block-summary">resolved:cookingDuty:g1<');
  });

  it('embedsToMarkers turns a block card back into a raw marker', () => {
    const html = blockSpan('cookingDuty', CONFIG, 'Kochdienst: Rote Gruppe, nächste 2 Wochen');
    const marker = embedsToMarkers(`<p>Hallo</p>${html}`);
    expect(marker).toMatch(/^<p>Hallo<\/p>\{\{block\.cookingDuty:[A-Za-z0-9_\-=]+\}\}$/);
  });

  it('round-trips marker -> embed -> marker unchanged in payload', () => {
    const originalMarker = embedsToMarkers(blockSpan('cookingDuty', CONFIG, 'ignored'));
    const embed = markersToEmbeds(originalMarker, () => 'ignored');
    const roundTripped = embedsToMarkers(embed);
    expect(roundTripped).toBe(originalMarker);
  });

  it('cookingDutyBlockSummary uses singular week/month wording for amount 1', () => {
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 1, periodUnit: 'week' }, 'Rote Gruppe'))
      .toBe('Kochdienst: Rote Gruppe, nächste 1 Woche');
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 1, periodUnit: 'month' }, 'Rote Gruppe'))
      .toBe('Kochdienst: Rote Gruppe, nächste 1 Monat');
  });

  it('cookingDutyBlockSummary uses plural wording for amount > 1 and falls back when no group is chosen', () => {
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 3, periodUnit: 'month' }, null))
      .toBe('Kochdienst: Gruppe wählen, nächste 3 Monate');
  });

  it('instanceLabel prefers value.label, then the field label, then the field name', () => {
    const withValueLabel: FieldInstanceDTO = { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false };
    const withoutValueLabel: FieldInstanceDTO = { ...withValueLabel, value: {} };
    const withNothing: FieldInstanceDTO = { ...withValueLabel, value: {}, label: {} };
    expect(instanceLabel(withValueLabel)).toBe('Rote Gruppe');
    expect(instanceLabel(withoutValueLabel)).toBe('Gruppen');
    expect(instanceLabel(withNothing)).toBe('group');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block.util.spec.ts'`
Expected: FAIL — `Cannot find module './mail-block.util'` (and `../../../shared/models/mail-block.model`).

- [ ] **Step 3: Create the model file**

Create `frontend/src/app/shared/models/mail-block.model.ts`:

```ts
export interface MailBlockDefinition {
  type: string;
  label: string;
  icon: string;
}

export interface CookingDutyBlockConfig {
  type: 'cookingDuty';
  groupId: string;
  periodUnit: 'week' | 'month';
  periodAmount: number;
}

export type MailBlockConfig = CookingDutyBlockConfig;

export const MAIL_BLOCK_DEFINITIONS: MailBlockDefinition[] = [
  { type: 'cookingDuty', label: 'Kochdienst-Tabelle', icon: 'restaurant' },
];

export const DEFAULT_BLOCK_CONFIG: Record<string, MailBlockConfig> = {
  cookingDuty: { type: 'cookingDuty', groupId: '', periodUnit: 'week', periodAmount: 2 },
};

/** Dropdown range for the "Anzahl" select in the block config dialog. */
export const PERIOD_AMOUNT_OPTIONS: number[] = Array.from({ length: 12 }, (_, i) => i + 1);
```

- [ ] **Step 4: Write the minimal implementation**

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block.util.ts`:

```ts
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../shared/models/mail-block.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';

/** Matches a stored block marker: type + base64url-encoded (padded) config JSON. */
export const BLOCK_MARKER_RE = /\{\{block\.([a-zA-Z0-9_]+):([A-Za-z0-9_\-=]+)\}\}/g;

function toBase64Url(json: string): string {
  const bytes = new TextEncoder().encode(json);
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_');
}

function fromBase64Url(encoded: string): string {
  const base64 = encoded.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** Builds the editor representation of a block: a non-editable card with a summary + edit button. */
export function blockSpan(blockType: string, config: Record<string, unknown>, summary: string): string {
  const configJson = JSON.stringify(config).replace(/"/g, '&quot;');
  return `<div class="mail-block" data-block-type="${blockType}" data-config="${configJson}" contenteditable="false">`
    + `<span class="mail-block-summary">${escapeHtml(summary)}</span>`
    + `<button type="button" class="mail-block-edit-btn" aria-label="Baustein bearbeiten">✎</button>`
    + `</div>`;
}

/** Stored HTML (raw {{block.type:config}} markers) -> editor HTML (block cards). */
export function markersToEmbeds(
  html: string,
  resolveSummary: (blockType: string, config: MailBlockConfig) => string,
): string {
  return html.replace(BLOCK_MARKER_RE, (_all, blockType: string, encoded: string) => {
    const config = JSON.parse(fromBase64Url(encoded)) as MailBlockConfig;
    return blockSpan(blockType, config, resolveSummary(blockType, config));
  });
}

/**
 * Editor HTML (block card divs) -> stored HTML with raw {{block.type:config}}
 * markers. DOM-based on purpose, same reasoning as pillsToTokens: reduces the
 * editor's live embed markup without regex guesswork.
 */
export function embedsToMarkers(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('[data-block-type]').forEach((el) => {
    const blockType = el.getAttribute('data-block-type') ?? '';
    const configJson = el.getAttribute('data-config') ?? '{}';
    const marker = `{{block.${blockType}:${toBase64Url(configJson)}}}`;
    el.replaceWith(doc.createTextNode(marker));
  });
  return doc.body.innerHTML.replace(/﻿/g, '');
}

const UNIT_LABELS: Record<'week' | 'month', [string, string]> = {
  week: ['Woche', 'Wochen'],
  month: ['Monat', 'Monate'],
};

/** Human-readable summary shown on the block card in the editor. */
export function cookingDutyBlockSummary(config: CookingDutyBlockConfig, groupLabel: string | null): string {
  const [singular, plural] = UNIT_LABELS[config.periodUnit];
  const unit = config.periodAmount === 1 ? singular : plural;
  const group = groupLabel ?? 'Gruppe wählen';
  return `Kochdienst: ${group}, nächste ${config.periodAmount} ${unit}`;
}

/** Reads a field instance's display label the same way the rest of the mail settings area does. */
export function instanceLabel(instance: FieldInstanceDTO): string {
  const label = (instance.value as { label?: string } | null)?.label;
  return label || instance.label?.['de'] || instance.fieldName;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block.util.spec.ts'`
Expected: PASS (7 specs).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/mail-block.model.ts frontend/src/app/settings/mail/mail-template-editor/mail-block.util.ts frontend/src/app/settings/mail/mail-template-editor/mail-block.util.spec.ts
git commit -m "feat(fe): mail-block model and marker/config util for template bausteine"
```

---

### Task 2: Quill block-embed blot + registration

**Files:**
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block.blot.ts`
- Test: `frontend/src/app/settings/mail/mail-block.blot.spec.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts`

**Interfaces:**
- Consumes: nothing from Task 1 directly (blot works on raw DOM attributes).
- Produces: `registerMailBlockBlot(): void`, Quill embed name `'mail-block'` accepting `{ blockType: string; config: Record<string, unknown>; summary: string }` — consumed by Task 3/5 via `quillInstance.insertEmbed(index, 'mail-block', value)`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/mail/mail-block.blot.spec.ts` (same directory as the existing `mail-token.blot.spec.ts`):

```ts
import Quill from 'quill';
import { registerMailBlockBlot } from './mail-template-editor/mail-block.blot';

describe('MailBlockBlot', () => {
  beforeAll(() => registerMailBlockBlot());

  function newEditor(): Quill {
    const host = document.createElement('div');
    document.body.appendChild(host);
    return new Quill(host);
  }

  it('inserts a block card carrying type, config and summary', () => {
    const quill = newEditor();
    quill.insertEmbed(0, 'mail-block', {
      blockType: 'cookingDuty',
      config: { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 },
      summary: 'Kochdienst: Gruppe Sonne, nächste 2 Wochen',
    });

    const card = quill.root.querySelector('div.mail-block') as HTMLElement;
    expect(card).toBeTruthy();
    expect(card.getAttribute('data-block-type')).toBe('cookingDuty');
    expect(JSON.parse(card.getAttribute('data-config') ?? '{}')).toEqual({
      type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2,
    });
    expect(card.querySelector('.mail-block-summary')?.textContent).toBe('Kochdienst: Gruppe Sonne, nächste 2 Wochen');
    expect(card.querySelector('.mail-block-edit-btn')).toBeTruthy();
  });

  it('registers idempotently (second call does not throw)', () => {
    expect(() => registerMailBlockBlot()).not.toThrow();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block.blot.spec.ts'`
Expected: FAIL — `Cannot find module './mail-template-editor/mail-block.blot'`.

- [ ] **Step 3: Write the minimal implementation**

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block.blot.ts`:

```ts
import Quill from 'quill';

const BlockEmbed: any = Quill.import('blots/block/embed');

export interface MailBlockValue {
  blockType: string;
  config: Record<string, unknown>;
  summary: string;
}

/** Non-editable card that renders a configurable block (e.g. a Kochdienst table) in the editor. */
class MailBlockBlot extends BlockEmbed {
  static blotName = 'mail-block';
  static tagName = 'div';
  static className = 'mail-block';

  static create(value: MailBlockValue): HTMLElement {
    const node: HTMLElement = super.create();
    node.setAttribute('data-block-type', value.blockType);
    node.setAttribute('data-config', JSON.stringify(value.config));
    node.setAttribute('contenteditable', 'false');

    const summary = document.createElement('span');
    summary.className = 'mail-block-summary';
    summary.textContent = value.summary;

    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'mail-block-edit-btn';
    editBtn.setAttribute('aria-label', 'Baustein bearbeiten');
    editBtn.textContent = '✎';

    node.appendChild(summary);
    node.appendChild(editBtn);
    return node;
  }

  static value(node: HTMLElement): MailBlockValue {
    return {
      blockType: node.getAttribute('data-block-type') ?? '',
      config: JSON.parse(node.getAttribute('data-config') ?? '{}'),
      summary: node.querySelector('.mail-block-summary')?.textContent ?? '',
    };
  }
}

let registered = false;

export function registerMailBlockBlot(): void {
  if (registered) {
    return;
  }
  registered = true;
  Quill.register(MailBlockBlot);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block.blot.spec.ts'`
Expected: PASS (2 specs).

- [ ] **Step 5: Register the blot in the email-safe Quill config**

Modify `frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts`:

```ts
import { registerMailTokenBlot } from './mail-token.blot';
import { registerMailBlockBlot } from './mail-block.blot';
```

and inside `configureQuillForEmailSafeOutput()`, after `registerMailTokenBlot();`:

```ts
  registerMailTokenBlot();
  registerMailBlockBlot();
```

- [ ] **Step 6: Run the full frontend suite to confirm no regression**

Run: `cd frontend && npx ng test --watch=false`
Expected: PASS, same count as before + 2 new specs.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-block.blot.ts frontend/src/app/settings/mail/mail-block.blot.spec.ts frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts
git commit -m "feat(fe): mail-block Quill embed blot for template bausteine"
```

---

### Task 3: Groups loading, block palette, drag/drop insert, marker⇄embed round trip

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts`

**Interfaces:**
- Consumes: `MAIL_BLOCK_DEFINITIONS`, `DEFAULT_BLOCK_CONFIG`, `MailBlockConfig`, `MailBlockDefinition` (Task 1 model); `blockSpan`, `markersToEmbeds`, `embedsToMarkers`, `cookingDutyBlockSummary`, `instanceLabel` (Task 1 util); `'mail-block'` embed (Task 2); `OrganisationService.getByTag`, `FieldInstanceService.listByDefinitionId`, `FieldInstanceDTO` (existing services, same pattern as `mail-job-editor.component.ts`).
- Produces: `component.groups: FieldInstanceDTO[]`, `component.blockDefinitions: MailBlockDefinition[]`, `component.onBlockDragStart(event, def)`, `component.insertBlock(def)`, `private summaryFor(blockType, config): string` — the last is reused by Task 5's click-to-edit handler.

- [ ] **Step 1: Write the failing tests**

Add to `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` (imports and fakes first — replace the top of the file):

```ts
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import Quill from 'quill';
import { AlignStyle } from 'quill/formats/align';
import { SizeStyle } from 'quill/formats/size';
import { DomSanitizer } from '@angular/platform-browser';
import { MailTemplateEditorComponent } from './mail-template-editor.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile, SaveMailTemplateRequest } from '../../../shared/models/mail-template.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { OrganisationDTO } from '../../../shared/models/organisation.model';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { MatDialog } from '@angular/material/dialog';

const fakeSanitizer = { bypassSecurityTrustHtml: (v: string) => v } as unknown as DomSanitizer;

class FakeNotificationService {
  successCalls: string[] = [];
  errorCalls: string[] = [];
  success(message: string) {
    this.successCalls.push(message);
  }
  error(message: string) {
    this.errorCalls.push(message);
  }
  extractError(err: unknown) {
    return err instanceof HttpErrorResponse ? String(err.error) : 'error';
  }
}

class FakeMailTemplateService {
  templates: MailTemplate[] = [
    { id: 't1', name: 'Willkommen', bodyHtml: '<p>Hallo</p>', createdAt: '2026-01-01', updatedAt: '2026-01-01' },
  ];
  placeholderTiles: PlaceholderTile[] = [
    { token: '{{person.firstName}}', fieldName: 'firstName', label: { de: 'Vorname', en: 'First name' } },
  ];
  createCalls: SaveMailTemplateRequest[] = [];
  updateCalls: { id: string; request: SaveMailTemplateRequest }[] = [];
  deleteCalls: string[] = [];

  list() {
    return of(this.templates);
  }
  placeholders() {
    return of(this.placeholderTiles);
  }
  create(request: SaveMailTemplateRequest) {
    this.createCalls.push(request);
    return of({ id: 't2', ...request, createdAt: '2026-01-02', updatedAt: '2026-01-02' } as MailTemplate);
  }
  update(id: string, request: SaveMailTemplateRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, createdAt: '2026-01-01', updatedAt: '2026-01-02' } as MailTemplate);
  }
  delete(id: string) {
    this.deleteCalls.push(id);
    return of(undefined);
  }
}

class FakeOrganisationService {
  orgs: Record<string, OrganisationDTO> = {
    groups: {
      id: 'org-groups', tag: 'groups', entries: [],
      definitions: [{ id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false }],
    },
  };
  getByTag(tag: string) {
    return of(this.orgs[tag]);
  }
}

class FakeFieldInstanceService {
  byDefinition: Record<string, FieldInstanceDTO[]> = {
    'def-group': [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
    ],
  };
  listByDefinitionId(definitionId: string) {
    return of(this.byDefinition[definitionId] ?? []);
  }
}

class FakeMatDialog {
  openCalls: unknown[] = [];
  afterCloseResult: unknown = undefined;
  open(component: unknown, config: unknown) {
    this.openCalls.push({ component, config });
    return { afterClosed: () => of(this.afterCloseResult) };
  }
}

describe('MailTemplateEditorComponent', () => {
  let component: MailTemplateEditorComponent;
  let service: FakeMailTemplateService;
  let notify: FakeNotificationService;
  let organisationService: FakeOrganisationService;
  let fieldInstanceService: FakeFieldInstanceService;
  let dialog: FakeMatDialog;

  beforeEach(() => {
    service = new FakeMailTemplateService();
    notify = new FakeNotificationService();
    organisationService = new FakeOrganisationService();
    fieldInstanceService = new FakeFieldInstanceService();
    dialog = new FakeMatDialog();
    component = new MailTemplateEditorComponent(
      service as unknown as MailTemplateService,
      fakeSanitizer,
      notify as unknown as NotificationService,
      organisationService as unknown as OrganisationService,
      fieldInstanceService as unknown as FieldInstanceService,
      dialog as unknown as MatDialog,
    );
    component.ngOnInit();
  });

  // ... keep every existing `it(...)` block from the current file unchanged below this line ...
```

Then append these new specs at the end of the `describe` block (before its closing `});`):

```ts
  it('loads groups from the organisation "groups" pool', () => {
    expect(component.groups.length).toBe(1);
    expect(component.groups[0].id).toBe('g1');
  });

  it('exposes the block palette from the registry', () => {
    expect(component.blockDefinitions.map((d) => d.type)).toEqual(['cookingDuty']);
  });

  it('dragging a block chip sets the block-type payload on the drag event', () => {
    const data: Record<string, string> = {};
    const event = { dataTransfer: { setData: (k: string, v: string) => (data[k] = v), effectAllowed: '' } } as unknown as DragEvent;

    component.onBlockDragStart(event, component.blockDefinitions[0]);

    expect(data['application/x-mail-block']).toBe('cookingDuty');
  });

  it('clicking a block chip with no editor appends a block card to the body, resolving the group label', () => {
    component.form.patchValue({ bodyHtml: '<p>Hallo</p>' });

    component.insertBlock(component.blockDefinitions[0]);

    expect(component.form.value.bodyHtml).toContain('data-block-type="cookingDuty"');
    expect(component.form.value.bodyHtml).toContain('Kochdienst: Gruppe wählen, nächste 2 Wochen');
  });

  it('dropping a block chip on the editor inserts a mail-block embed', () => {
    const fakeQuill = {
      getLength: () => 5,
      insertEmbed: jasmine.createSpy('insertEmbed'),
      setSelection: jasmine.createSpy('setSelection'),
      root: { innerHTML: '' },
    };
    component.onEditorCreated(fakeQuill);
    const event = {
      preventDefault: () => {},
      clientX: -1, clientY: -1,
      dataTransfer: { getData: (k: string) => (k === 'application/x-mail-block' ? 'cookingDuty' : '') },
    } as unknown as DragEvent;

    component.onEditorDrop(event);

    expect(fakeQuill.insertEmbed).toHaveBeenCalled();
    const args = (fakeQuill.insertEmbed as jasmine.Spy).calls.mostRecent().args;
    expect(args[1]).toBe('mail-block');
    expect(args[2].blockType).toBe('cookingDuty');
    expect(args[2].config).toEqual({ type: 'cookingDuty', groupId: '', periodUnit: 'week', periodAmount: 2 });
  });

  it('selecting a template with a stored block marker shows it as a card with the resolved group label', () => {
    service.templates[0].bodyHtml =
      '<p>Hallo</p>{{block.cookingDuty:eyJ0eXBlIjoiY29va2luZ0R1dHkiLCJncm91cElkIjoiZzEiLCJwZXJpb2RVbml0Ijoid2VlayIsInBlcmlvZEFtb3VudCI6Mn0=}}';

    component.onSelectTemplate('t1');

    expect(component.form.value.bodyHtml).toContain('data-block-type="cookingDuty"');
    expect(component.form.value.bodyHtml).toContain('Kochdienst: Rote Gruppe, nächste 2 Wochen');
  });

  it('saving converts a block card back to a raw marker (no mail-block div persisted)', () => {
    component.newTemplate();
    component.form.patchValue({
      name: 'Neu',
      bodyHtml: '<p>Hallo</p><div class="mail-block" data-block-type="cookingDuty" '
        + 'data-config="{&quot;type&quot;:&quot;cookingDuty&quot;,&quot;groupId&quot;:&quot;g1&quot;,&quot;periodUnit&quot;:&quot;week&quot;,&quot;periodAmount&quot;:2}" '
        + 'contenteditable="false"><span class="mail-block-summary">x</span>'
        + '<button type="button" class="mail-block-edit-btn">e</button></div>',
    });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].bodyHtml).not.toContain('mail-block');
    expect(service.createCalls[0].bodyHtml).toMatch(/\{\{block\.cookingDuty:[A-Za-z0-9_\-=]+\}\}/);
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: FAIL — constructor arity mismatch (`Expected 3 arguments, but got 6`) and missing members `groups`/`blockDefinitions`/`onBlockDragStart`/`insertBlock`.

- [ ] **Step 3: Write the minimal implementation**

Modify `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`. Replace the whole file with:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';
import { tokensToPills, pillsToTokens, pillSpan, renderPreview, SAMPLE_VALUES } from './mail-token.util';
import {
  blockSpan, markersToEmbeds, embedsToMarkers, cookingDutyBlockSummary, instanceLabel,
} from './mail-block.util';
import {
  MAIL_BLOCK_DEFINITIONS, DEFAULT_BLOCK_CONFIG, MailBlockDefinition, MailBlockConfig, CookingDutyBlockConfig,
} from '../../../shared/models/mail-block.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { MailBlockConfigDialogComponent } from './mail-block-config-dialog/mail-block-config-dialog.component';

const DRAG_MIME = 'application/x-mail-token';
const BLOCK_DRAG_MIME = 'application/x-mail-block';

@Component({
  selector: 'app-mail-template-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule,
    MatIconModule, MatTooltipModule, MatDialogModule,
    QuillModule,
  ],
  templateUrl: './mail-template-editor.component.html',
  styleUrl: './mail-template-editor.component.scss',
})
export class MailTemplateEditorComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  templates: MailTemplate[] = [];
  selectedId: string | null = null;
  /** When false the editor is hidden and a placeholder is shown instead. */
  editing = false;
  placeholders: PlaceholderTile[] = [];
  groups: FieldInstanceDTO[] = [];
  blockDefinitions: MailBlockDefinition[] = MAIL_BLOCK_DEFINITIONS;
  previewHtml: SafeHtml;

  quillInstance: any = null;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
    private notify: NotificationService,
    private organisationService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.placeholders().subscribe((tiles) => (this.placeholders = tiles));
    this.loadGroups();
    this.form.controls.bodyHtml.valueChanges.subscribe((v) => this.updatePreview(v ?? ''));
  }

  load(): void {
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
  }

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

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  private labelFor(tile: PlaceholderTile): string {
    return tile.label['de'] || tile.fieldName;
  }

  private syncBodyFromQuill(): void {
    this.form.patchValue({ bodyHtml: this.quillInstance.root?.innerHTML ?? '' });
  }

  private insertPillAt(index: number, tile: PlaceholderTile): void {
    this.quillInstance.insertEmbed(index, 'mail-token', { token: tile.token, label: this.labelFor(tile) });
    this.quillInstance.setSelection(index + 1, 0);
    this.syncBodyFromQuill();
  }

  /** Click-insert at the cursor (or append if there is no live editor yet). */
  insertPlaceholder(tile: PlaceholderTile): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertPillAt(index, tile);
    } else {
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + pillSpan(tile.token, this.labelFor(tile)) });
    }
  }

  onChipDragStart(event: DragEvent, tile: PlaceholderTile): void {
    event.dataTransfer?.setData(DRAG_MIME, tile.token);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

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

  /** Resolves the human-readable card text for a block's current config. Only `cookingDuty` exists today. */
  private summaryFor(blockType: string, config: MailBlockConfig): string {
    if (blockType === 'cookingDuty') {
      const cfg = config as CookingDutyBlockConfig;
      const group = this.groups.find((g) => g.id === cfg.groupId);
      return cookingDutyBlockSummary(cfg, group ? instanceLabel(group) : null);
    }
    return 'Baustein';
  }

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

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

  /** Best-effort caret index from the drop point; falls back to the document end. */
  private dropIndex(event: DragEvent): number {
    const end = Math.max(0, this.quillInstance.getLength() - 1);
    try {
      const doc: any = document;
      const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY);
      if (!range) {
        return end;
      }
      const blot = Quill.find(range.startContainer, true);
      if (!blot) {
        return end;
      }
      return this.quillInstance.getIndex(blot) + range.startOffset;
    } catch {
      return end;
    }
  }

  private updatePreview(editorHtml: string): void {
    const rendered = renderPreview(pillsToTokens(editorHtml), SAMPLE_VALUES);
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(rendered);
  }

  get selectedTemplate(): MailTemplate | undefined {
    return this.templates.find((t) => t.id === this.selectedId);
  }

  onSelectTemplate(id: string): void {
    const template = this.templates.find((t) => t.id === id);
    if (!template) {
      return;
    }
    this.selectedId = template.id;
    this.editing = true;
    const withPills = tokensToPills(template.bodyHtml, this.placeholders);
    const withBlocks = markersToEmbeds(withPills, (type, cfg) => this.summaryFor(type, cfg));
    this.form.patchValue({ name: template.name, bodyHtml: withBlocks });
  }

  /** Convenience for the sidebar list-item click. */
  selectForEdit(template: MailTemplate): void {
    this.onSelectTemplate(template.id);
  }

  newTemplate(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  /** Close the editor and return to the placeholder (no template selected). */
  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  save(): void {
    const request = {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(embedsToMarkers(this.form.value.bodyHtml ?? '')),
    };
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.mailTemplateService.update(this.selectedId, request)
      : this.mailTemplateService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Vorlage aktualisiert' : 'Vorlage gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(template: MailTemplate): void {
    this.mailTemplateService.delete(template.id).subscribe({
      next: () => {
        this.notify.success('Vorlage gelöscht');
        if (this.selectedId === template.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

Note: the import of `MailBlockConfigDialogComponent` is added now so Task 5 only has to *use* it (the file will exist after Task 4); Task 3 does not reference it yet, so leave it unused — TypeScript will flag an unused import. To keep Task 3 self-contained and compiling cleanly, **omit that import for now** and add it in Task 5 instead. (Do not add the `import { MailBlockConfigDialogComponent } ...` line in this task.)

Add to `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html`, right after the existing `.chip-bar` for placeholders (after its closing `</div>`, before `<label class="field-label">Inhalt</label>`):

```html
    <div class="chip-bar block-bar">
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

Add to `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss`, after the existing `.chip-bar` block:

```scss
.block-bar {
  margin-top: 0.25rem;
}

.block-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;

  mat-icon {
    font-size: 1rem;
    height: 1rem;
    width: 1rem;
  }
}

/* Block card rendering inside the editor. */
::ng-deep .mail-block {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin: 0.5rem 0;
  padding: 0.5rem 0.75rem;
  border: 1px dashed #c9cff2;
  border-radius: 10px;
  background: #f4f5fc;
  color: #3f51b5;
  font-size: 0.85rem;
  font-weight: 600;

  .mail-block-edit-btn {
    border: none;
    background: none;
    cursor: pointer;
    color: inherit;
    font-size: 1rem;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS, including the 7 new specs and every pre-existing spec.

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx ng test --watch=false`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
git commit -m "feat(fe): block palette with drag/drop insert and marker round-trip in mail template editor"
```

---

### Task 4: Block config dialog (generic host + Kochdienst form)

**Files:**
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/cooking-duty-block-config.component.ts`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/cooking-duty-block-config.component.html`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.html`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts`

**Interfaces:**
- Consumes: `PERIOD_AMOUNT_OPTIONS`, `MailBlockConfig`, `CookingDutyBlockConfig` (Task 1 model), `instanceLabel` (Task 1 util), `FieldInstanceDTO` (existing model).
- Produces: `MailBlockConfigDialogData { blockType: string; config: MailBlockConfig; groups: FieldInstanceDTO[] }`, `MailBlockConfigDialogComponent` (opened via `MatDialog.open`, closes with a `MailBlockConfig` or `undefined`) — consumed by Task 5.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.spec.ts`:

```ts
import { MailBlockConfigDialogComponent } from './mail-block-config-dialog.component';
import { MatDialogRef } from '@angular/material/dialog';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig } from '../../../../shared/models/mail-block.model';

const GROUPS: FieldInstanceDTO[] = [
  { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
];

class FakeDialogRef {
  closedWith: unknown;
  close(result?: unknown) {
    this.closedWith = result;
  }
}

describe('MailBlockConfigDialogComponent', () => {
  const config: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };
  let dialogRef: FakeDialogRef;
  let component: MailBlockConfigDialogComponent;

  beforeEach(() => {
    dialogRef = new FakeDialogRef();
    component = new MailBlockConfigDialogComponent(
      dialogRef as unknown as MatDialogRef<MailBlockConfigDialogComponent>,
      { blockType: 'cookingDuty', config, groups: GROUPS },
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
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block-config-dialog.component.spec.ts'`
Expected: FAIL — `Cannot find module './mail-block-config-dialog.component'`.

- [ ] **Step 3: Write the minimal implementation**

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/cooking-duty-block-config.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { PERIOD_AMOUNT_OPTIONS } from '../../../../shared/models/mail-block.model';
import { instanceLabel as fieldInstanceLabel } from '../mail-block.util';

@Component({
  selector: 'app-cooking-duty-block-config',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatFormFieldModule, MatSelectModule],
  templateUrl: './cooking-duty-block-config.component.html',
})
export class CookingDutyBlockConfigComponent {
  @Input({ required: true }) form!: FormGroup;
  @Input({ required: true }) groups: FieldInstanceDTO[] = [];

  readonly periodAmountOptions = PERIOD_AMOUNT_OPTIONS;

  instanceLabel(instance: FieldInstanceDTO): string {
    return fieldInstanceLabel(instance);
  }
}
```

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/cooking-duty-block-config.component.html`:

```html
<form [formGroup]="form" class="cooking-duty-block-config">
  <mat-form-field appearance="outline">
    <mat-label>Gruppe</mat-label>
    <mat-select formControlName="groupId">
      <mat-option *ngFor="let g of groups" [value]="g.id">{{ instanceLabel(g) }}</mat-option>
    </mat-select>
  </mat-form-field>

  <div class="period-row">
    <mat-form-field appearance="outline">
      <mat-label>Anzahl</mat-label>
      <mat-select formControlName="periodAmount">
        <mat-option *ngFor="let n of periodAmountOptions" [value]="n">{{ n }}</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Zeiteinheit</mat-label>
      <mat-select formControlName="periodUnit">
        <mat-option value="week">Woche(n)</mat-option>
        <mat-option value="month">Monat(e)</mat-option>
      </mat-select>
    </mat-form-field>
  </div>
</form>
```

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.ts`:

```ts
import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { CookingDutyBlockConfigComponent } from './cooking-duty-block-config.component';

export interface MailBlockConfigDialogData {
  blockType: string;
  config: MailBlockConfig;
  groups: FieldInstanceDTO[];
}

@Component({
  selector: 'app-mail-block-config-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatDialogModule, MatButtonModule, CookingDutyBlockConfigComponent],
  templateUrl: './mail-block-config-dialog.component.html',
})
export class MailBlockConfigDialogComponent {
  form: FormGroup;

  constructor(
    private dialogRef: MatDialogRef<MailBlockConfigDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: MailBlockConfigDialogData,
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

Create `frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog/mail-block-config-dialog.component.html`:

```html
<h2 mat-dialog-title>Baustein konfigurieren</h2>
<mat-dialog-content>
  <app-cooking-duty-block-config *ngIf="data.blockType === 'cookingDuty'"
    [form]="form" [groups]="data.groups"></app-cooking-duty-block-config>
</mat-dialog-content>
<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="cancel()">Abbrechen</button>
  <button mat-flat-button color="primary" type="button" [disabled]="form.invalid" (click)="save()">Übernehmen</button>
</mat-dialog-actions>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-block-config-dialog.component.spec.ts'`
Expected: PASS (5 specs).

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx ng test --watch=false`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-block-config-dialog
git commit -m "feat(fe): generic block config dialog host + Kochdienst config form"
```

---

### Task 5: Wire click-to-edit into the template editor

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts`

**Interfaces:**
- Consumes: `MailBlockConfigDialogComponent`, `MailBlockConfigDialogData` (Task 4).
- Produces: clicking a block card's edit button opens the dialog and, on save, updates the card's `data-config`/summary text and the form's `bodyHtml`.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` (append to the `describe` block):

```ts
  it('clicking a block card edit button opens the config dialog and applies the saved result', () => {
    const host = document.createElement('div');
    host.innerHTML = '<div class="mail-block" data-block-type="cookingDuty" '
      + 'data-config="{&quot;type&quot;:&quot;cookingDuty&quot;,&quot;groupId&quot;:&quot;g1&quot;,&quot;periodUnit&quot;:&quot;week&quot;,&quot;periodAmount&quot;:2}">'
      + '<span class="mail-block-summary">old</span>'
      + '<button type="button" class="mail-block-edit-btn">e</button></div>';
    document.body.appendChild(host);
    const editBtn = host.querySelector('.mail-block-edit-btn') as HTMLElement;

    const fakeQuill = { root: host, getSelection: () => null, getLength: () => 0, insertEmbed: () => {}, setSelection: () => {} };
    component.onEditorCreated(fakeQuill);

    dialog.afterCloseResult = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'month', periodAmount: 3 };
    editBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    const card = host.querySelector('.mail-block') as HTMLElement;
    expect(JSON.parse(card.getAttribute('data-config') ?? '{}')).toEqual({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'month', periodAmount: 3 });
    expect(card.querySelector('.mail-block-summary')?.textContent).toBe('Kochdienst: Rote Gruppe, nächste 3 Monate');
    expect(component.form.value.bodyHtml).toContain('nächste 3 Monate');
    host.remove();
  });

  it('clicking outside a block card edit button does nothing', () => {
    const host = document.createElement('div');
    host.innerHTML = '<p>Hallo</p>';
    document.body.appendChild(host);

    const fakeQuill = { root: host, getSelection: () => null, getLength: () => 0, insertEmbed: () => {}, setSelection: () => {} };
    component.onEditorCreated(fakeQuill);

    host.querySelector('p')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(dialog.openCalls.length).toBe(0);
    host.remove();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: FAIL — clicking the edit button does not call `dialog.open`, `data-config`/summary remain unchanged.

- [ ] **Step 3: Write the minimal implementation**

In `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`:

Add the import (now that Task 4 exists):

```ts
import { MailBlockConfigDialogComponent, MailBlockConfigDialogData } from './mail-block-config-dialog/mail-block-config-dialog.component';
```

Replace `onEditorCreated`:

```ts
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
    const config = JSON.parse(node.getAttribute('data-config') ?? '{}') as MailBlockConfig;
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

(`onEditorRootClick` is a bound arrow class property — same technique already used for `dateFilter` in `cooking-duty-dialog.component.ts` — so `this` stays correct without an explicit `.bind()`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS, including the 2 new specs.

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx ng test --watch=false`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
git commit -m "feat(fe): click-to-edit wiring for mail-template bausteine"
```

---

### Task 6: Extract `CookingDutyQueryService`, refactor `CookingDutyResource`

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/CookingDutyQueryService.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java`
- Test: `backend/src/test/java/at/kigruapp/service/CookingDutyQueryServiceTest.java`
- Test: `backend/src/test/java/at/kigruapp/resource/CookingDutyResourceTest.java` (new — no test existed for this resource before)

**Interfaces:**
- Produces: `CookingDutyQueryService.query(Predicate<String> dateFilter, Set<String> groupFilter): List<CookingDutyDTO>` — consumed by `CookingDutyResource` (this task) and `CookingDutyMailBlockRenderer` (Task 7).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/service/CookingDutyQueryServiceTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.dto.CookingDutyDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingDutyQueryServiceTest {

    @Inject
    CookingDutyQueryService queryService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;
    FieldDefinition firstNameDef;
    FieldDefinition lastNameDef;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        firstNameDef = persistDefinition("firstName");
        lastNameDef = persistDefinition("lastName");
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistScalarInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    private ObjectId persistCookingDutyInstance(String date, List<String> groups, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", groups).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private Person persistPersonWithDuty(String lastName, String firstName, ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties = List.of(
                new FieldRef(lastNameDef.id, persistScalarInstance(lastNameDef.id, lastName)),
                new FieldRef(firstNameDef.id, persistScalarInstance(firstNameDef.id, firstName)));
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
        return p;
    }

    @Test
    void returnsEntriesMatchingDateAndGroupFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g1"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> date.compareTo("2026-09-01") >= 0 && date.compareTo("2026-09-30") < 0, Set.of("g1"));

        assertEquals(1, result.size());
        assertEquals("2026-09-10", result.get(0).date);
        assertEquals("Muster Anna", result.get(0).personName);
        assertEquals("Suppe", result.get(0).description);
        assertEquals(List.of("g1"), result.get(0).groups);
    }

    @Test
    void excludesEntriesOutsideTheDateFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-10-01", List.of("g1"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> date.compareTo("2026-09-01") >= 0 && date.compareTo("2026-09-30") < 0, Set.of("g1"));

        assertTrue(result.isEmpty());
    }

    @Test
    void excludesEntriesNotInTheGroupFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g2"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of("g1"));

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyGroupFilterMeansAllGroups() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g2"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of());

        assertEquals(1, result.size());
    }

    @Test
    void resultsAreSortedByDate() {
        ObjectId later = persistCookingDutyInstance("2026-09-20", List.of("g1"), "Spät");
        persistPersonWithDuty("Muster", "Bea", later);
        ObjectId earlier = persistCookingDutyInstance("2026-09-05", List.of("g1"), "Früh");
        persistPersonWithDuty("Muster", "Anna", earlier);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of("g1"));

        assertEquals(2, result.size());
        assertEquals("2026-09-05", result.get(0).date);
        assertEquals("2026-09-20", result.get(1).date);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CookingDutyQueryServiceTest test`
Expected: FAIL — `CookingDutyQueryService` does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `backend/src/main/java/at/kigruapp/service/CookingDutyQueryService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.dto.CookingDutyDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.function.Predicate;

/**
 * Shared Kochdienst lookup: walks every Person's schedules, resolves the
 * matching FieldInstance, and applies caller-supplied date/group filters.
 * Extracted from CookingDutyResource so the mail-block renderer (a relative
 * date range instead of a month prefix) reuses the exact same source of
 * truth instead of re-implementing the FieldInstance walk.
 */
@ApplicationScoped
public class CookingDutyQueryService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> getFieldInstancesCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    public List<CookingDutyDTO> query(Predicate<String> dateFilter, Set<String> groupFilter) {
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return new ArrayList<>();
        }
        ObjectId cookingDutyDefId = cookingDutyDef.id;

        MongoCollection<Document> instColl = getFieldInstancesCollection();
        List<Person> allPersons = Person.listAll();
        List<CookingDutyDTO> result = new ArrayList<>();

        for (Person person : allPersons) {
            if (person.schedules == null) continue;

            String personName = resolvePersonName(person, instColl);

            for (FieldRef ref : person.schedules) {
                if (!ref.definitionId.equals(cookingDutyDefId)) continue;

                Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (instDoc == null) continue;

                FieldInstance inst = FieldInstance.fromDocument(instDoc);
                if (!(inst.value instanceof Document valueDoc)) continue;

                String date = valueDoc.getString("date");
                if (date == null || !dateFilter.test(date)) continue;

                List<String> groups = new ArrayList<>();
                Object groupsObj = valueDoc.get("groups");
                if (groupsObj instanceof List<?> groupList) {
                    for (Object g : groupList) {
                        groups.add(g.toString());
                    }
                }

                if (!groupFilter.isEmpty() && groups.stream().noneMatch(groupFilter::contains)) continue;

                Map<String, Boolean> foodProps = new LinkedHashMap<>();
                Object fpObj = valueDoc.get("foodProperties");
                if (fpObj instanceof Document fpDoc) {
                    for (Map.Entry<String, Object> entry : fpDoc.entrySet()) {
                        if (entry.getValue() instanceof Boolean b) {
                            foodProps.put(entry.getKey(), b);
                        }
                    }
                }

                CookingDutyDTO dto = new CookingDutyDTO();
                dto.id = inst.id.toString();
                dto.personId = person.id.toString();
                dto.familyId = person.familyId.toString();
                dto.personName = personName;
                dto.date = date;
                dto.groups = groups;
                dto.description = valueDoc.getString("description");
                dto.foodProperties = foodProps;
                result.add(dto);
            }
        }

        result.sort(Comparator.comparing(dto -> dto.date));
        return result;
    }

    private String resolvePersonName(Person person, MongoCollection<Document> instColl) {
        String firstName = "";
        String lastName = "";
        if (person.basicProperties == null) return "";

        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def == null) continue;

            Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (instDoc == null) continue;

            Object value = instDoc.get("value");
            if ("firstName".equals(def.fieldName) && value instanceof String s) {
                firstName = s;
            } else if ("lastName".equals(def.fieldName) && value instanceof String s) {
                lastName = s;
            }
        }
        return (lastName + " " + firstName).trim();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CookingDutyQueryServiceTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Add a regression test for the existing resource, then refactor it to delegate**

Create `backend/src/test/java/at/kigruapp/resource/CookingDutyResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CookingDutyResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = new FieldDefinition();
        cookingDutyDef.fieldName = "cookingDuty";
        cookingDutyDef.createdAt = java.time.Instant.now();
        cookingDutyDef.persist();
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private ObjectId persistCookingDutyInstance(String date, List<String> groups, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", groups).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private void persistPersonWithDuty(ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
    }

    @Test
    void filtersByMonth() {
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-10", List.of("g1"), "September"));
        persistPersonWithDuty(persistCookingDutyInstance("2026-10-10", List.of("g1"), "Oktober"));

        given().when().get("/api/v1/cooking-duties?month=2026-09")
            .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].description", is("September"));
    }

    @Test
    void filtersByGroups() {
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-10", List.of("g1"), "Gruppe1"));
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-11", List.of("g2"), "Gruppe2"));

        given().when().get("/api/v1/cooking-duties?groups=g1")
            .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].description", is("Gruppe1"));
    }

    @Test
    void returnsEmptyListWhenNothingMatches() {
        given().when().get("/api/v1/cooking-duties?month=2099-01")
            .then().statusCode(200)
            .body("size()", is(0));
    }
}
```

Run: `cd backend && mvn -q -Dtest=CookingDutyResourceTest test`
Expected: PASS against the *current* (pre-refactor) implementation — this locks down the behavior before touching it.

Now refactor `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java` to delegate:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.CookingDutyDTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import at.kigruapp.service.CookingDutyQueryService;

import java.util.*;
import java.util.function.Predicate;

@Path("/api/v1/cooking-duties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingDutyResource {

    @Inject
    CookingDutyQueryService queryService;

    @GET
    public List<CookingDutyDTO> list(
            @QueryParam("month") String month,
            @QueryParam("groups") String groupsParam) {

        Set<String> groupFilter = new HashSet<>();
        if (groupsParam != null && !groupsParam.isBlank()) {
            groupFilter.addAll(Arrays.asList(groupsParam.split(",")));
        }

        Predicate<String> dateFilter = date -> month == null || month.isBlank() || date.startsWith(month);

        return queryService.query(dateFilter, groupFilter);
    }
}
```

- [ ] **Step 6: Run test to verify it still passes after the refactor**

Run: `cd backend && mvn -q -Dtest=CookingDutyResourceTest,CookingDutyQueryServiceTest test`
Expected: PASS, identical behavior.

- [ ] **Step 7: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions (matches the pre-existing baseline — see project memory on pre-existing failing tests, unrelated to this change).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/CookingDutyQueryService.java backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java backend/src/test/java/at/kigruapp/service/CookingDutyQueryServiceTest.java backend/src/test/java/at/kigruapp/resource/CookingDutyResourceTest.java
git commit -m "refactor(be): extract CookingDutyQueryService from CookingDutyResource"
```

---

### Task 7: `MailBlockRenderer` interface + `CookingDutyMailBlockRenderer`

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/MailBlockRenderer.java`
- Create: `backend/src/main/java/at/kigruapp/service/CookingDutyMailBlockRenderer.java`
- Test: `backend/src/test/java/at/kigruapp/service/CookingDutyMailBlockRendererTest.java`

**Interfaces:**
- Consumes: `CookingDutyQueryService.query(...)` (Task 6), `GroupCatalogService.byId(): Map<ObjectId, GroupCatalogService.GroupInfo>` (existing).
- Produces: `MailBlockRenderer { boolean supports(String blockType); String render(JsonNode config); }`, `CookingDutyMailBlockRenderer implements MailBlockRenderer` — consumed by Task 8.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/service/CookingDutyMailBlockRendererTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingDutyMailBlockRendererTest {

    @Inject
    CookingDutyMailBlockRenderer renderer;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private final ObjectMapper mapper = new ObjectMapper();

    FieldDefinition cookingDutyDef;
    FieldDefinition groupDef;
    FieldDefinition firstNameDef;
    FieldDefinition lastNameDef;
    ObjectId groupInstanceId;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        firstNameDef = persistDefinition("firstName");
        lastNameDef = persistDefinition("lastName");
        groupDef = persistDefinition("group");

        groupInstanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", groupInstanceId)
                .append("definitionId", groupDef.id)
                .append("value", new Document("label", "Rote Gruppe").append("color", "#f00")));
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistScalarInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    private ObjectId persistCookingDutyInstance(String date, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", List.of(groupInstanceId.toHexString())).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private void persistPersonWithDuty(String lastName, String firstName, ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties = List.of(
                new FieldRef(lastNameDef.id, persistScalarInstance(lastNameDef.id, lastName)),
                new FieldRef(firstNameDef.id, persistScalarInstance(firstNameDef.id, firstName)));
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
    }

    private JsonNode config(String groupId, String periodUnit, int periodAmount) {
        return mapper.createObjectNode()
                .put("type", "cookingDuty")
                .put("groupId", groupId)
                .put("periodUnit", periodUnit)
                .put("periodAmount", periodAmount);
    }

    @Test
    void supportsOnlyCookingDuty() {
        assertTrue(renderer.supports("cookingDuty"));
        assertFalse(renderer.supports("somethingElse"));
    }

    @Test
    void rendersATableRowPerEntryWithinTheRelativePeriod() {
        String today = LocalDate.now(ZoneId.of("Europe/Vienna")).toString();
        persistPersonWithDuty("Muster", "Anna", persistCookingDutyInstance(today, "Suppe"));

        String html = renderer.render(config(groupInstanceId.toHexString(), "week", 2));

        assertTrue(html.contains("<table"));
        assertTrue(html.contains("Muster Anna"));
        assertTrue(html.contains("Suppe"));
        assertTrue(html.contains(today));
    }

    @Test
    void rendersAHintWhenNoEntriesFallInThePeriod() {
        String farFuture = LocalDate.now(ZoneId.of("Europe/Vienna")).plusYears(5).toString();
        persistPersonWithDuty("Muster", "Anna", persistCookingDutyInstance(farFuture, "Suppe"));

        String html = renderer.render(config(groupInstanceId.toHexString(), "week", 2));

        assertFalse(html.contains("<table"));
        assertTrue(html.contains("Keine Kochdienst-Einträge im gewählten Zeitraum."));
    }

    @Test
    void rendersAHintWhenTheGroupNoLongerExists() {
        String html = renderer.render(config(new ObjectId().toHexString(), "week", 2));

        assertFalse(html.contains("<table"));
        assertTrue(html.contains("Gruppe nicht mehr vorhanden."));
    }

    @Test
    void rendersAHintWhenTheGroupIdIsMalformed() {
        String html = renderer.render(config("not-an-object-id", "week", 2));

        assertTrue(html.contains("Gruppe nicht mehr vorhanden."));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CookingDutyMailBlockRendererTest test`
Expected: FAIL — `MailBlockRenderer`/`CookingDutyMailBlockRenderer` do not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `backend/src/main/java/at/kigruapp/service/MailBlockRenderer.java`:

```java
package at.kigruapp.service;

import com.fasterxml.jackson.databind.JsonNode;

/** One implementation per mail-template block type; see {@link MailTemplateRenderer}. */
public interface MailBlockRenderer {

    boolean supports(String blockType);

    String render(JsonNode config);
}
```

Create `backend/src/main/java/at/kigruapp/service/CookingDutyMailBlockRenderer.java`:

```java
package at.kigruapp.service;

import at.kigruapp.dto.CookingDutyDTO;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CookingDutyMailBlockRenderer implements MailBlockRenderer {

    private static final String TIMEZONE = "Europe/Vienna";

    @Inject
    CookingDutyQueryService queryService;

    @Inject
    GroupCatalogService groupCatalogService;

    @Override
    public boolean supports(String blockType) {
        return "cookingDuty".equals(blockType);
    }

    @Override
    public String render(JsonNode config) {
        String groupId = config.path("groupId").asText(null);
        String periodUnit = config.path("periodUnit").asText("week");
        int periodAmount = config.path("periodAmount").asInt(1);

        if (groupId == null) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }

        ObjectId groupObjectId;
        try {
            groupObjectId = new ObjectId(groupId);
        } catch (IllegalArgumentException e) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }
        if (!groupCatalogService.byId().containsKey(groupObjectId)) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }

        LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));
        LocalDate end = "month".equals(periodUnit) ? today.plusMonths(periodAmount) : today.plusWeeks(periodAmount);
        String todayStr = today.toString();
        String endStr = end.toString();

        List<CookingDutyDTO> entries = queryService.query(
                date -> date.compareTo(todayStr) >= 0 && date.compareTo(endStr) < 0,
                Set.of(groupId));

        if (entries.isEmpty()) {
            return "<p>Keine Kochdienst-Einträge im gewählten Zeitraum.</p>";
        }

        StringBuilder html = new StringBuilder();
        html.append("<table style=\"border-collapse:collapse;width:100%\">");
        html.append("<tr>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Datum</th>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Person</th>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Beschreibung</th>")
                .append("</tr>");
        for (CookingDutyDTO entry : entries) {
            html.append("<tr>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.date)).append("</td>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.personName)).append("</td>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.description != null ? entry.description : "")).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CookingDutyMailBlockRendererTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/MailBlockRenderer.java backend/src/main/java/at/kigruapp/service/CookingDutyMailBlockRenderer.java backend/src/test/java/at/kigruapp/service/CookingDutyMailBlockRendererTest.java
git commit -m "feat(be): MailBlockRenderer registry entry point + Kochdienst renderer"
```

---

### Task 8: Wire block-marker rendering into `MailTemplateRenderer`

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java`
- Modify: `backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java`

**Interfaces:**
- Consumes: `MailBlockRenderer` (Task 7), CDI `@All List<MailBlockRenderer>` (Quarkus ARC — collects every `MailBlockRenderer` bean).
- Produces: `MailTemplateRenderer.render(bodyHtml, properties)` now also replaces `{{block.<type>:<config>}}` markers, in addition to `{{person.xxx}}` tokens.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java` (append inside the class, after the existing tests):

```java
    private static class FakeBlockRenderer implements MailBlockRenderer {
        private final String type;
        private final String output;

        FakeBlockRenderer(String type, String output) {
            this.type = type;
            this.output = output;
        }

        @Override
        public boolean supports(String blockType) {
            return type.equals(blockType);
        }

        @Override
        public String render(com.fasterxml.jackson.databind.JsonNode config) {
            return output;
        }
    }

    @Test
    void replacesABlockMarkerWithTheMatchingRendererOutput() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>Vorher</p>{{block.cookingDuty:eyJncm91cElkIjoiZzEifQ==}}<p>Nachher</p>", java.util.Map.of());

        assertEquals("<p>Vorher</p><table></table><p>Nachher</p>", result);
    }

    @Test
    void blanksABlockMarkerWhenNoRendererSupportsItsType() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>{{block.unknownType:eyJ4IjoxfQ==}}</p>", java.util.Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void blanksABlockMarkerWhoseDecodedConfigIsNotValidJson() {
        // "bm90IGpzb24=" is valid base64url (matches the marker pattern) but decodes to
        // the plain text "not json" — readTree() throws, exercising the catch path.
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>{{block.cookingDuty:bm90IGpzb24=}}</p>", java.util.Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void personTokenAndBlockMarkerBothResolveInTheSameBody() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>Hallo {{person.firstName}}</p>{{block.cookingDuty:eyJncm91cElkIjoiZzEifQ==}}", java.util.Map.of("firstName", "Anna"));

        assertEquals("<p>Hallo Anna</p><table></table>", result);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=MailTemplateRendererTest test`
Expected: FAIL — no constructor `MailTemplateRenderer(List<MailBlockRenderer>)` exists; block markers pass through unresolved.

- [ ] **Step 3: Write the minimal implementation**

Replace `backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java` with:

```java
package at.kigruapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.All;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {{person.&lt;fieldName&gt;}} tokens and {{block.&lt;type&gt;:&lt;config&gt;}}
 * markers in a template body. Token substitution is pure (no I/O); block
 * rendering delegates to whichever registered MailBlockRenderer supports the
 * marker's type, which may do I/O (e.g. a Mongo lookup).
 */
@ApplicationScoped
public class MailTemplateRenderer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{person\\.([a-zA-Z0-9_]+)}}");
    private static final Pattern BLOCK_PATTERN = Pattern.compile("\\{\\{block\\.([a-zA-Z0-9_]+):([A-Za-z0-9_\\-=]+)}}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The OWASP HTML sanitizer applied on save neutralizes template-injection
     * sequences by wedging an empty comment between braces, e.g. {@code {<!-- -->{}.
     * Stripping empty comments restores {@code {{...}} so tokens match — and it
     * repairs bodies already stored in the mangled form without a migration.
     */
    private static final Pattern EMPTY_COMMENT = Pattern.compile("<!--\\s*-->");

    @Inject
    @All
    List<MailBlockRenderer> blockRenderers = List.of();

    public MailTemplateRenderer() {
    }

    /** Test-only: bypasses CDI so unit tests can supply renderers directly. */
    MailTemplateRenderer(List<MailBlockRenderer> blockRenderers) {
        this.blockRenderers = blockRenderers;
    }

    public String render(String bodyHtml, Map<String, String> properties) {
        if (bodyHtml == null) {
            return null;
        }
        String normalized = EMPTY_COMMENT.matcher(bodyHtml).replaceAll("");
        String withPlaceholders = renderPlaceholders(normalized, properties);
        return renderBlocks(withPlaceholders);
    }

    private String renderPlaceholders(String bodyHtml, Map<String, String> properties) {
        Matcher matcher = TOKEN_PATTERN.matcher(bodyHtml);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String value = properties != null ? properties.get(fieldName) : null;
            String replacement = value != null ? escapeHtml(value) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String renderBlocks(String bodyHtml) {
        Matcher matcher = BLOCK_PATTERN.matcher(bodyHtml);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = renderBlock(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String renderBlock(String blockType, String encodedConfig) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedConfig);
            JsonNode config = OBJECT_MAPPER.readTree(decoded);
            for (MailBlockRenderer renderer : blockRenderers) {
                if (renderer.supports(blockType)) {
                    return renderer.render(config);
                }
            }
            return "";
        } catch (Exception e) {
            Log.warnf(e, "Failed to render mail block of type '%s'", blockType);
            return "";
        }
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=MailTemplateRendererTest test`
Expected: PASS, all 9 tests (5 pre-existing + 4 new).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java
git commit -m "feat(be): resolve {{block.type:config}} markers via the MailBlockRenderer registry"
```

---

## Manual Smoke Test (after all 8 tasks)

Not automatable without a running Docker/Keycloak/Quarkus/Mongo stack — do this once the branch is otherwise green:

1. Open **Einstellungen → Mail → Vorlagen**, create/select a template.
2. Drag the "Kochdienst-Tabelle" chip into the editor body; confirm a card appears reading "Kochdienst: Gruppe wählen, nächste 2 Wochen".
3. Click the card's edit icon, pick a real group and a period (e.g. 1 Monat), save; confirm the card text updates.
4. Save the template; reopen it; confirm the card still shows the chosen group/period (round trip through the backend).
5. Create/point a MailJob at this template with a recipient who has a Kochdienst entry for the chosen group within the chosen period; trigger a send; confirm the delivered mail contains a real table with that entry (Datum/Person/Beschreibung) and any resolved `{{person.xxx}}` tokens.
6. Repeat with a group that has no entries in the period → confirm the hint text appears instead of an empty table.
