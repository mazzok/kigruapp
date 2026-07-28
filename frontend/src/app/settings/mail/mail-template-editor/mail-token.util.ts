import { PlaceholderTile } from '../../../shared/models/mail-template.model';

/** Matches a stored placeholder token, capturing the field name. */
export const TOKEN_RE = /\{\{person\.([A-Za-z]+)\}\}/g;

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

/**
 * Editor HTML (pill embeds — Quill wraps them in a nested contentNode span and
 * FEFF guard chars) -> stored HTML with raw {{tokens}}. DOM-based on purpose:
 * a regex cannot reliably reduce the nested embed markup. Runs in the browser
 * (component + karma), which is the only place it is called.
 */
export function pillsToTokens(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('[data-token]').forEach((el) => {
    el.replaceWith(doc.createTextNode(el.getAttribute('data-token') ?? ''));
  });
  return doc.body.innerHTML.replace(/﻿/g, '');
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
