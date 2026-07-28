# Mail-Vorlagen-Editor Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the mail-template editor usable — fix the broken Quill rendering, restack the layout, show placeholders as removable pills that insert by click *and* drag, and add a live sample-data preview.

**Architecture:** Frontend-only change to the `mail-template-editor` Angular component. The stored `bodyHtml` keeps the exact backend contract — raw `{{person.<fieldName>}}` tokens inside HTML. Two pure string transforms convert between the stored token form and the editor's pill-span form; a custom Quill embed blot renders the pills. Nothing in the backend, sanitizer, job editor, or services changes.

**Tech Stack:** Angular 18.2 (standalone components, Reactive Forms), Angular Material, ngx-quill 26 / Quill 2, Karma/Jasmine.

## Global Constraints

- **Angular 18.2 / ngx-quill ^26.0.10 / quill ^2.0.3** — do not upgrade any of these.
- **Backend contract is frozen:** persisted `bodyHtml` must contain raw `{{person.<fieldName>}}` tokens and **no** `mail-token` `<span>`s. The OWASP sanitizer + `MailTemplateRenderer` run server-side and must not be touched.
- **Allowlisted placeholder fields (R3):** `firstName, lastName, email, phone, dateOfBirth, gender, entryDate, exitDate, notes`. No others.
- **Test convention:** direct component instantiation with fake services, **no** `TestBed`, no `HttpClient`. Matches `mail-template-editor.component.spec.ts` as it exists today.
- **Toolbar stays as-is** (`EMAIL_SAFE_QUILL_TOOLBAR`): no `list`/`indent` formats (no inline-style equivalent in Quill core).
- **Test runner:** use `npx ng test --watch=false --include='<glob>'` (the bare `ng` command is intercepted by a shell hook — invoke via `npx ng`, and on Windows run through the PowerShell tool).
- **Commits:** this repo's owner requires explicit approval before any `git commit`. The commit steps below are part of the TDD rhythm, but during execution ask for the go-ahead before actually committing (or batch the commits for one approval).

---

## File Structure

- **Create** `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts` — pure functions: `tokensToPills`, `pillsToTokens`, `pillSpan`, `renderPreview`, and the `SAMPLE_VALUES` map. No Angular, no Quill, no DOM.
- **Create** `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts` — unit tests for the above.
- **Create** `frontend/src/app/settings/mail/mail-template-editor/mail-token.blot.ts` — the `MailTokenBlot` Quill embed blot + a `registerMailTokenBlot()` function.
- **Create** `frontend/src/app/settings/mail/mail-token.blot.spec.ts` — instantiates a real Quill and asserts the blot renders/serializes.
- **Modify** `frontend/angular.json` — add the Quill snow stylesheet to the build `styles` array.
- **Modify** `frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts` — call `registerMailTokenBlot()` from `configureQuillForEmailSafeOutput()`.
- **Modify** `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts` — wire transforms into load/save, pill insertion (click + drag), live preview, dropdown selection.
- **Modify** `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` — update insertion tests, add transform-integration/preview/drag tests.
- **Modify** `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html` — stacked layout (dropdown, chip bar, editor, preview).
- **Modify** `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss` — layout + pill + preview styling.

---

## Task 1: Fix Quill stylesheet (the broken-render bug)

**Files:**
- Modify: `frontend/angular.json:34-38` (the build `styles` array)

**Interfaces:**
- Consumes: nothing.
- Produces: a correctly-styled Quill editor at runtime (visual only; no code symbol).

- [ ] **Step 1: Add the Quill theme stylesheet**

In `frontend/angular.json`, the `architect.build.options.styles` array currently reads:

```json
"styles": [
  "@angular/material/prebuilt-themes/indigo-pink.css",
  "node_modules/angular-calendar/css/angular-calendar.css",
  "src/styles.scss"
],
```

Add the Quill snow theme so the editor and toolbar are styled:

```json
"styles": [
  "@angular/material/prebuilt-themes/indigo-pink.css",
  "node_modules/angular-calendar/css/angular-calendar.css",
  "node_modules/quill/dist/quill.snow.css",
  "src/styles.scss"
],
```

- [ ] **Step 2: Verify the build succeeds**

Run: `npx ng build`
Expected: build completes with no new errors (pre-existing bundle-budget/optional-chain warnings are fine).

- [ ] **Step 3: Manual visual check**

Run the app (`npx ng serve`), open Mail-Einstellungen → Vorlagen. Expected: the editor now shows a normal toolbar and a bordered writing area — no giant triangles/QR artifacts. (This confirms the root cause; the remaining tasks build on it.)

- [ ] **Step 4: Commit**

```bash
git add frontend/angular.json
git commit -m "fix: load quill snow stylesheet so mail template editor renders"
```

---

## Task 2: Pure token↔pill + preview transforms

**Files:**
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts`

**Interfaces:**
- Consumes: `PlaceholderTile` from `../../../shared/models/mail-template.model` (`{ token, fieldName, label: Record<string,string> }`).
- Produces:
  - `pillSpan(token: string, label: string): string` — builds one pill `<span>`.
  - `tokensToPills(html: string, placeholders: PlaceholderTile[]): string` — stored HTML → editor HTML.
  - `pillsToTokens(html: string): string` — editor HTML → stored HTML.
  - `renderPreview(storedHtml: string, samples: Record<string,string>): string` — token HTML → sample-substituted HTML.
  - `SAMPLE_VALUES: Record<string, string>`.
  - `TOKEN_RE` regex (exported for reuse/tests) matching `{{person.<fieldName>}}`.

- [ ] **Step 1: Write the failing tests**

Create `mail-token.util.spec.ts`:

```typescript
import {
  pillSpan, tokensToPills, pillsToTokens, renderPreview, SAMPLE_VALUES,
} from './mail-token.util';
import { PlaceholderTile } from '../../../shared/models/mail-template.model';

const PLACEHOLDERS: PlaceholderTile[] = [
  { token: '{{person.firstName}}', fieldName: 'firstName', label: { de: 'Vorname', en: 'First name' } },
  { token: '{{person.lastName}}', fieldName: 'lastName', label: { de: 'Nachname', en: 'Last name' } },
];

describe('mail-token.util', () => {
  it('pillSpan builds a span carrying the raw token and readable label', () => {
    const html = pillSpan('{{person.firstName}}', 'Vorname');
    expect(html).toContain('class="mail-token"');
    expect(html).toContain('data-token="{{person.firstName}}"');
    expect(html).toContain('>Vorname<');
  });

  it('tokensToPills replaces every token with a labelled pill', () => {
    const out = tokensToPills('<p>Hallo {{person.firstName}} {{person.lastName}}</p>', PLACEHOLDERS);
    expect(out).not.toContain('{{person.firstName}}<'); // token no longer bare text
    expect(out).toContain('data-token="{{person.firstName}}"');
    expect(out).toContain('>Vorname<');
    expect(out).toContain('>Nachname<');
  });

  it('tokensToPills falls back to the fieldName when no label is known', () => {
    const out = tokensToPills('<p>{{person.phone}}</p>', PLACEHOLDERS);
    expect(out).toContain('data-token="{{person.phone}}"');
    expect(out).toContain('>phone<');
  });

  it('pillsToTokens turns pills back into raw tokens', () => {
    const editor = '<p>Hallo ' + pillSpan('{{person.firstName}}', 'Vorname') + '</p>';
    expect(pillsToTokens(editor)).toBe('<p>Hallo {{person.firstName}}</p>');
  });

  it('round-trips token → pill → token unchanged', () => {
    const stored = '<p>Hallo {{person.firstName}}, willkommen {{person.lastName}}!</p>';
    expect(pillsToTokens(tokensToPills(stored, PLACEHOLDERS))).toBe(stored);
  });

  it('pillsToTokens leaves plain HTML without pills untouched', () => {
    expect(pillsToTokens('<p>x</p>')).toBe('<p>x</p>');
  });

  it('renderPreview substitutes sample values for tokens', () => {
    const out = renderPreview('<p>Hallo {{person.firstName}} {{person.lastName}}</p>', SAMPLE_VALUES);
    expect(out).toBe('<p>Hallo Anna Muster</p>');
  });

  it('renderPreview blanks unknown tokens', () => {
    expect(renderPreview('<p>{{person.unknownField}}</p>', SAMPLE_VALUES)).toBe('<p></p>');
  });

  it('SAMPLE_VALUES covers every allowlisted field', () => {
    ['firstName','lastName','email','phone','dateOfBirth','gender','entryDate','exitDate','notes']
      .forEach((f) => expect(SAMPLE_VALUES[f]).toBeTruthy());
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx ng test --watch=false --include='**/mail-token.util.spec.ts'`
Expected: FAIL — cannot resolve module `./mail-token.util`.

- [ ] **Step 3: Write the implementation**

Create `mail-token.util.ts`:

```typescript
import { PlaceholderTile } from '../../../shared/models/mail-template.model';

/** Matches a stored placeholder token, capturing the field name. */
export const TOKEN_RE = /\{\{person\.([A-Za-z]+)\}\}/g;

/** Matches one rendered pill span, capturing its raw token from data-token. */
const PILL_RE = /<span[^>]*\bdata-token="([^"]*)"[^>]*>.*?<\/span>/g;

/** Builds the editor representation of a placeholder: a non-editable pill. */
export function pillSpan(token: string, label: string): string {
  return `<span class="mail-token" data-token="${token}">${label}</span>`;
}

/** Stored HTML (raw {{tokens}}) -> editor HTML (pill spans). */
export function tokensToPills(html: string, placeholders: PlaceholderTile[]): string {
  const labels = new Map<string, string>();
  placeholders.forEach((p) => labels.set(p.fieldName, p.label['de'] || p.fieldName));
  return html.replace(TOKEN_RE, (token, fieldName) =>
    pillSpan(token, labels.get(fieldName) ?? fieldName),
  );
}

/** Editor HTML (pill spans) -> stored HTML (raw {{tokens}}). */
export function pillsToTokens(html: string): string {
  return html.replace(PILL_RE, (_span, token) => token);
}

/** Fixed sample values used only for the client-side preview. */
export const SAMPLE_VALUES: Record<string, string> = {
  firstName: 'Anna',
  lastName: 'Muster',
  email: 'anna.muster@example.org',
  phone: '+43 660 1234567',
  dateOfBirth: '15.03.2015',
  gender: 'weiblich',
  entryDate: '01.09.2023',
  exitDate: '31.08.2025',
  notes: 'Allergien beachten',
};

/** Stored HTML -> preview HTML with sample data (unknown tokens blanked). */
export function renderPreview(storedHtml: string, samples: Record<string, string>): string {
  return storedHtml.replace(TOKEN_RE, (_token, fieldName) => samples[fieldName] ?? '');
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx ng test --watch=false --include='**/mail-token.util.spec.ts'`
Expected: PASS (9/9).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts
git commit -m "feat: add mail-token pill/token transforms and preview substitution"
```

---

## Task 3: MailTokenBlot custom Quill embed

**Files:**
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-token.blot.ts`
- Test: `frontend/src/app/settings/mail/mail-token.blot.spec.ts`

**Interfaces:**
- Consumes: `Quill` from `quill`.
- Produces:
  - `registerMailTokenBlot(): void` — idempotently registers the `mail-token` blot.
  - The blot: `blotName = 'mail-token'`, value shape `{ token: string; label: string }`, DOM `<span class="mail-token" data-token="…">label</span>`.

- [ ] **Step 1: Write the failing test**

Create `mail-token.blot.spec.ts`. Karma runs in a real browser, so a real Quill can be instantiated on a detached element:

```typescript
import Quill from 'quill';
import { registerMailTokenBlot } from './mail-template-editor/mail-token.blot';

describe('MailTokenBlot', () => {
  beforeAll(() => registerMailTokenBlot());

  function newEditor(): Quill {
    const host = document.createElement('div');
    document.body.appendChild(host);
    return new Quill(host);
  }

  it('inserts a pill embed carrying token + label', () => {
    const quill = newEditor();
    quill.insertEmbed(0, 'mail-token', { token: '{{person.firstName}}', label: 'Vorname' });
    const span = quill.root.querySelector('span.mail-token') as HTMLElement;
    expect(span).toBeTruthy();
    expect(span.getAttribute('data-token')).toBe('{{person.firstName}}');
    expect(span.textContent).toBe('Vorname');
  });

  it('registers idempotently (second call does not throw)', () => {
    expect(() => registerMailTokenBlot()).not.toThrow();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx ng test --watch=false --include='**/mail-token.blot.spec.ts'`
Expected: FAIL — cannot resolve `./mail-template-editor/mail-token.blot`.

- [ ] **Step 3: Write the implementation**

Create `mail-token.blot.ts`:

```typescript
import Quill from 'quill';

const Embed: any = Quill.import('blots/embed');

/** Inline, non-editable pill that renders a placeholder in the editor. */
class MailTokenBlot extends Embed {
  static blotName = 'mail-token';
  static tagName = 'span';
  static className = 'mail-token';

  static create(value: { token: string; label: string }): HTMLElement {
    const node: HTMLElement = super.create();
    node.setAttribute('data-token', value.token);
    node.setAttribute('data-label', value.label);
    node.textContent = value.label;
    return node;
  }

  static value(node: HTMLElement): { token: string; label: string } {
    return {
      token: node.getAttribute('data-token') ?? '',
      label: node.getAttribute('data-label') ?? node.textContent ?? '',
    };
  }
}

let registered = false;

export function registerMailTokenBlot(): void {
  if (registered) {
    return;
  }
  registered = true;
  Quill.register(MailTokenBlot);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx ng test --watch=false --include='**/mail-token.blot.spec.ts'`
Expected: PASS (2/2).

> If `Quill.register(MailTokenBlot)` warns about overwriting, pass `true`: `Quill.register(MailTokenBlot, true)`. If the blot needs an explicit key, use `Quill.register('formats/mail-token', MailTokenBlot, true)`. Adjust only if the test fails; the plain form is the default.

- [ ] **Step 5: Register the blot from the editor config**

Modify `quill-email-safe.config.ts` — import and call `registerMailTokenBlot()` inside `configureQuillForEmailSafeOutput()` so the blot exists before any editor renders:

```typescript
import { registerMailTokenBlot } from './mail-token.blot';
// ...
export function configureQuillForEmailSafeOutput(): void {
  if (configured) {
    return;
  }
  configured = true;
  Quill.register('formats/align', AlignStyle, true);
  Quill.register('formats/size', SizeStyle, true);
  registerMailTokenBlot();
}
```

- [ ] **Step 6: Run existing config-dependent tests to confirm no regression**

Run: `npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS (the existing align/size attributor tests still pass; the two insertion tests may still be green here — they get rewritten in Task 4).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-token.blot.ts frontend/src/app/settings/mail/mail-token.blot.spec.ts frontend/src/app/settings/mail/mail-template-editor/quill-email-safe.config.ts
git commit -m "feat: add MailTokenBlot pill embed and register it in the email-safe quill config"
```

---

## Task 4: Component logic — transforms, pill insertion (click + drag), preview, dropdown

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts`

**Interfaces:**
- Consumes: `tokensToPills`, `pillsToTokens`, `pillSpan`, `renderPreview`, `SAMPLE_VALUES` (Task 2); the registered blot (Task 3); `DomSanitizer`/`SafeHtml` from `@angular/platform-browser`.
- Produces (public members the template in Task 5 binds to):
  - `templates: MailTemplate[]`, `selectedId: string | null`, `placeholders: PlaceholderTile[]`, `form` (`name`, `bodyHtml`), `quillModules`, `previewHtml: SafeHtml`.
  - `onEditorCreated(quill: any): void`, `insertPlaceholder(tile: PlaceholderTile): void`, `onChipDragStart(event: DragEvent, tile: PlaceholderTile): void`, `onEditorDragOver(event: DragEvent): void`, `onEditorDrop(event: DragEvent): void`, `onSelectTemplate(id: string): void`, `newTemplate(): void`, `save(): void`, `delete(t: MailTemplate): void`.

- [ ] **Step 1: Update the spec — rewrite insertion tests and add transform/preview/drag tests**

Replace the two old insertion tests (`clicking a tile inserts the token…`) in `mail-template-editor.component.spec.ts` and add new ones. The component now takes a `DomSanitizer` as a second constructor arg, so update `beforeEach` too.

Update `beforeEach` and add a fake sanitizer at the top of the file:

```typescript
import { DomSanitizer } from '@angular/platform-browser';
// ...
const fakeSanitizer = { bypassSecurityTrustHtml: (v: string) => v } as unknown as DomSanitizer;

beforeEach(() => {
  service = new FakeMailTemplateService();
  component = new MailTemplateEditorComponent(service as unknown as MailTemplateService, fakeSanitizer);
  component.ngOnInit();
});
```

Delete the two tests titled `clicking a tile inserts the token into the body (no editor instance yet — appends)` and `clicking a tile inserts the token via the Quill instance when one is present`. Add:

```typescript
it('clicking a tile inserts a pill embed via the Quill instance', () => {
  const fakeQuill = {
    getSelection: () => ({ index: 3 }),
    getLength: () => 10,
    insertEmbed: jasmine.createSpy('insertEmbed'),
    setSelection: jasmine.createSpy('setSelection'),
    root: { innerHTML: '<p>Hal<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>lo</p>' },
  };
  component.onEditorCreated(fakeQuill);

  component.insertPlaceholder(service.placeholderTiles[0]);

  expect(fakeQuill.insertEmbed).toHaveBeenCalledWith(
    3, 'mail-token', { token: '{{person.firstName}}', label: 'Vorname' });
  expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
});

it('clicking a tile with no editor appends a pill span to the body', () => {
  component.form.patchValue({ bodyHtml: '<p>Hallo</p>' });

  component.insertPlaceholder(service.placeholderTiles[0]);

  expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  expect(component.form.value.bodyHtml).toContain('>Vorname<');
});

it('selecting a template loads it with tokens converted to pills', () => {
  service.templates[0].bodyHtml = '<p>Hallo {{person.firstName}}</p>';

  component.onSelectTemplate('t1');

  expect(component.selectedId).toBe('t1');
  expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  expect(component.form.value.bodyHtml).not.toContain('{{person.firstName}}</p>');
});

it('saving converts pills back to raw tokens (no mail-token spans persisted)', () => {
  component.newTemplate();
  component.form.patchValue({
    name: 'Neu',
    bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
  });

  component.save();

  expect(service.createCalls.length).toBe(1);
  expect(service.createCalls[0].bodyHtml).toBe('<p>Hallo {{person.firstName}}</p>');
  expect(service.createCalls[0].bodyHtml).not.toContain('mail-token');
});

it('preview substitutes sample data for tokens on body change', () => {
  component.form.patchValue({
    bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
  });

  expect(component.previewHtml as unknown as string).toBe('<p>Hallo Anna</p>');
});

it('dragging a chip sets the token payload on the drag event', () => {
  const data: Record<string, string> = {};
  const event = { dataTransfer: { setData: (k: string, v: string) => (data[k] = v), effectAllowed: '' } } as unknown as DragEvent;

  component.onChipDragStart(event, service.placeholderTiles[0]);

  expect(data['application/x-mail-token']).toBe('{{person.firstName}}');
});

it('dropping on the editor inserts a pill (falls back to end when caret is unresolved)', () => {
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
    dataTransfer: { getData: (k: string) => (k === 'application/x-mail-token' ? '{{person.firstName}}' : '') },
  } as unknown as DragEvent;

  component.onEditorDrop(event);

  expect(fakeQuill.insertEmbed).toHaveBeenCalled();
  const args = (fakeQuill.insertEmbed as jasmine.Spy).calls.mostRecent().args;
  expect(args[1]).toBe('mail-token');
  expect(args[2]).toEqual({ token: '{{person.firstName}}', label: 'Vorname' });
});
```

- [ ] **Step 2: Run the spec to verify the new tests fail**

Run: `npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: FAIL — `insertEmbed`/`onSelectTemplate`/`onChipDragStart`/`onEditorDrop`/`previewHtml` not defined, and the constructor takes only one arg.

- [ ] **Step 3: Rewrite the component**

Replace the body of `mail-template-editor.component.ts` with:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';
import { tokensToPills, pillsToTokens, pillSpan, renderPreview, SAMPLE_VALUES } from './mail-token.util';

const DRAG_MIME = 'application/x-mail-token';

@Component({
  selector: 'app-mail-template-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule,
    QuillModule,
  ],
  templateUrl: './mail-template-editor.component.html',
  styleUrl: './mail-template-editor.component.scss',
})
export class MailTemplateEditorComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  templates: MailTemplate[] = [];
  selectedId: string | null = null;
  placeholders: PlaceholderTile[] = [];
  previewHtml: SafeHtml;

  quillInstance: any = null;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(private mailTemplateService: MailTemplateService, private sanitizer: DomSanitizer) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.placeholders().subscribe((tiles) => (this.placeholders = tiles));
    this.form.controls.bodyHtml.valueChanges.subscribe((v) => this.updatePreview(v ?? ''));
  }

  load(): void {
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
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

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onEditorDrop(event: DragEvent): void {
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

  onSelectTemplate(id: string): void {
    const template = this.templates.find((t) => t.id === id);
    if (!template) {
      return;
    }
    this.selectedId = template.id;
    this.form.patchValue({
      name: template.name,
      bodyHtml: tokensToPills(template.bodyHtml, this.placeholders),
    });
  }

  newTemplate(): void {
    this.selectedId = null;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  save(): void {
    const request = {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(this.form.value.bodyHtml ?? ''),
    };
    const save$ = this.selectedId
      ? this.mailTemplateService.update(this.selectedId, request)
      : this.mailTemplateService.create(request);
    save$.subscribe(() => {
      this.newTemplate();
      this.load();
    });
  }

  delete(template: MailTemplate): void {
    this.mailTemplateService.delete(template.id).subscribe(() => {
      if (this.selectedId === template.id) {
        this.newTemplate();
      }
      this.load();
    });
  }
}
```

> Note: `selectForEdit` is replaced by `onSelectTemplate(id)`. The old spec test `saving an existing (selected) template calls update` uses `component.selectForEdit(service.templates[0])` — change that call to `component.onSelectTemplate('t1')`.

- [ ] **Step 4: Fix the one remaining old test reference**

In `mail-template-editor.component.spec.ts`, the test `saving an existing (selected) template calls update` calls `component.selectForEdit(...)`. Change it to:

```typescript
component.onSelectTemplate('t1');
component.form.patchValue({ name: 'Geändert' });
```

- [ ] **Step 5: Run the spec to verify it passes**

Run: `npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS (all tests, including the new insertion/select/save/preview/drag ones).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts
git commit -m "feat: pill insertion (click+drag), token transforms on load/save, live preview"
```

---

## Task 5: Stacked layout, dropdown, draggable chips, preview box (template + styles)

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss`

**Interfaces:**
- Consumes: the component API from Task 4 (`onSelectTemplate`, `insertPlaceholder`, `onChipDragStart`, `onEditorDragOver`, `onEditorDrop`, `previewHtml`, `selectedId`, `templates`, `placeholders`, `form`, `quillModules`, `onEditorCreated`).
- Produces: the rendered UI (verified by build + the existing component specs, which are DOM-free).

- [ ] **Step 1: Replace the template**

Replace `mail-template-editor.component.html` with the stacked layout (Variante C):

```html
<div class="mail-template-editor">
  <div class="toprow">
    <mat-form-field appearance="outline" class="tpl-select">
      <mat-label>Vorlage</mat-label>
      <mat-select [value]="selectedId" (selectionChange)="onSelectTemplate($event.value)">
        <mat-option *ngFor="let t of templates" [value]="t.id">{{ t.name }}</mat-option>
      </mat-select>
    </mat-form-field>
    <button mat-stroked-button type="button" (click)="newTemplate()">Neue Vorlage</button>
    <button mat-stroked-button type="button" color="warn"
            *ngIf="selectedId"
            (click)="delete(templates[0])"
            [disabled]="!selectedId">Löschen</button>
  </div>

  <form [formGroup]="form" class="template-form" (ngSubmit)="save()">
    <mat-form-field appearance="outline">
      <mat-label>Name</mat-label>
      <input matInput formControlName="name" />
    </mat-form-field>

    <div class="chip-bar">
      <span class="chip-hint">Platzhalter einfügen (klicken oder in den Text ziehen):</span>
      <button class="chip" type="button"
              *ngFor="let tile of placeholders"
              draggable="true"
              (dragstart)="onChipDragStart($event, tile)"
              (click)="insertPlaceholder(tile)">
        {{ tile.label['de'] || tile.fieldName }}
      </button>
    </div>

    <label class="field-label">Inhalt</label>
    <div class="editor-wrap" (dragover)="onEditorDragOver($event)" (drop)="onEditorDrop($event)">
      <quill-editor formControlName="bodyHtml" [modules]="quillModules"
                    (onEditorCreated)="onEditorCreated($event)"></quill-editor>
    </div>

    <div class="preview">
      <label class="field-label">Vorschau <span class="muted">(mit Beispiel-Daten)</span></label>
      <div class="preview-box" [innerHTML]="previewHtml"></div>
    </div>

    <button mat-raised-button color="primary" type="submit" [disabled]="form.invalid">Speichern</button>
  </form>
</div>
```

> The delete button targets the currently selected template. Since `delete(template)` needs the object, resolve it in the click by finding it: change `(click)="delete(templates[0])"` to `(click)="onDeleteSelected()"` **only if** you add a helper; otherwise bind the found template inline: `(click)="onSelectedTemplate() && delete(onSelectedTemplate()!)"`. Simplest: add a getter in Task 4's component (do this now if you took the getter route):
> ```typescript
> get selectedTemplate(): MailTemplate | undefined {
>   return this.templates.find((t) => t.id === this.selectedId);
> }
> ```
> then bind `*ngIf="selectedTemplate"` and `(click)="delete(selectedTemplate!)"`. Use this getter form — it is unambiguous.

Apply the getter: add `selectedTemplate` to the component (`mail-template-editor.component.ts`) and use this delete button markup instead:

```html
<button mat-stroked-button type="button" color="warn"
        *ngIf="selectedTemplate"
        (click)="delete(selectedTemplate!)">Löschen</button>
```

- [ ] **Step 2: Add the `selectedTemplate` getter to the component**

In `mail-template-editor.component.ts`, add:

```typescript
get selectedTemplate(): MailTemplate | undefined {
  return this.templates.find((t) => t.id === this.selectedId);
}
```

- [ ] **Step 3: Replace the styles**

Replace `mail-template-editor.component.scss`:

```scss
.mail-template-editor {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-width: 820px;
}

.toprow {
  display: flex;
  align-items: center;
  gap: 0.75rem;

  .tpl-select {
    min-width: 240px;
  }
}

.template-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.field-label {
  font-weight: 600;
  .muted { font-weight: 400; color: rgba(0, 0, 0, 0.54); }
}

.chip-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;

  .chip-hint {
    font-size: 0.8rem;
    color: rgba(0, 0, 0, 0.6);
    margin-right: 0.25rem;
  }

  .chip {
    cursor: grab;
    border: 1px solid #c9cff2;
    background: #eef0fb;
    color: #3f51b5;
    border-radius: 14px;
    padding: 0.2rem 0.7rem;
    font-size: 0.8rem;
    font-weight: 600;
  }
  .chip:active { cursor: grabbing; }
}

.editor-wrap ::ng-deep .ql-editor {
  min-height: 300px;
}

/* Pill rendering inside the editor. */
::ng-deep .mail-token {
  background: #eef0fb;
  color: #3f51b5;
  border: 1px solid #c9cff2;
  border-radius: 12px;
  padding: 0.05rem 0.5rem;
  font-size: 0.85em;
  font-weight: 600;
  white-space: nowrap;
}

.preview-box {
  border: 1px solid #e2e2e6;
  border-radius: 8px;
  padding: 0.75rem;
  min-height: 60px;
  background: #fafafb;
}
```

- [ ] **Step 4: Run the component spec (regression — logic unchanged)**

Run: `npx ng test --watch=false --include='**/mail-template-editor.component.spec.ts'`
Expected: PASS (template/style changes don't affect the DOM-free specs).

- [ ] **Step 5: Verify the build**

Run: `npx ng build`
Expected: build succeeds (the new `MatSelectModule` import and template bindings compile).

- [ ] **Step 6: Manual acceptance check**

`npx ng serve` → Mail-Einstellungen → Vorlagen. Verify: dropdown lists templates; "Neue Vorlage" clears the form; chips insert pills on click; dragging a chip into the text drops a pill at the cursor; typing updates the preview with sample data ("Anna", "Muster", …); saving persists and reloads.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.scss frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts
git commit -m "feat: stacked mail template editor layout with dropdown, draggable chips, live preview"
```

---

## Final regression sweep

- [ ] **Run the full frontend suite**

Run: `npx ng test --watch=false`
Expected: no new failures vs. the known baseline (1 pre-existing `AppComponent should create the app` failure from missing `HttpClient` provider — unrelated). All mail-template-editor, mail-token util, and blot specs green.

- [ ] **Run the build**

Run: `npx ng build`
Expected: succeeds.

---

## Self-Review notes (author check against the spec)

- **Bugfix (Quill CSS):** Task 1. ✓
- **Layout C (stacked, dropdown, full-width editor):** Task 5. ✓
- **Pill representation:** Task 3 (blot) + Task 5 (styling). ✓
- **Insert by click AND drag:** Task 4 (`insertPlaceholder`, `onChipDragStart`/`onEditorDrop`) + Task 5 (chip `draggable`, editor drop handlers). ✓
- **Backend contract preserved (raw tokens, no pill spans persisted):** Task 2 (`pillsToTokens`) + Task 4 (`save`) + explicit test "saving converts pills back to raw tokens". ✓
- **Live preview with sample data:** Task 2 (`renderPreview`, `SAMPLE_VALUES`) + Task 4 (`updatePreview`, `previewHtml`) + Task 5 (preview box). ✓
- **Scope: no backend, no job editor, no services touched.** ✓ (only the files listed in File Structure)
- **Toolbar unchanged (no list/indent):** untouched `EMAIL_SAFE_QUILL_TOOLBAR`; existing toolbar test retained. ✓
- **Drag risk:** `dropIndex` is best-effort with an end-of-document fallback (spec's stated risk); click-insert is the robust path. ✓
```
