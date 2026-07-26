import Quill from 'quill';
import { AlignStyle } from 'quill/formats/align';
import { SizeStyle } from 'quill/formats/size';

/**
 * Quill formats CSS classes (ql-align-*, ql-size-*, ...) by default, which
 * depend on Quill's own stylesheet — stripped by email clients, so the
 * formatting silently disappears in delivered mail (G-003). This swaps the
 * class-based align/size attributors for their inline-style equivalents.
 *
 * `background`/`color` already emit inline `style=` by default in Quill, so
 * they need no change here.
 *
 * `indent` and bullet `list` have no inline-style variant in Quill core (both
 * are class/attribute-only) — they are intentionally left out of the toolbar
 * below rather than offered and silently broken in delivered mail.
 */
let configured = false;

export function configureQuillForEmailSafeOutput(): void {
  if (configured) {
    return;
  }
  configured = true;
  Quill.register('formats/align', AlignStyle, true);
  Quill.register('formats/size', SizeStyle, true);
}

/** Toolbar limited to formats that serialize to inline styles or semantic tags (G-003). */
export const EMAIL_SAFE_QUILL_TOOLBAR = [
  ['bold', 'italic', 'underline'],
  [{ color: [] }, { background: [] }],
  [{ size: ['small', false, 'large', 'huge'] }],
  [{ align: [] }],
  ['link'],
];
