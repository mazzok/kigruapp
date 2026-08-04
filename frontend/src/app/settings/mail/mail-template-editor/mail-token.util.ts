import { PlaceholderTile } from '../../../shared/models/mail-template.model';

/** Matches a stored placeholder token of either namespace, capturing the whole token. */
export const TOKEN_RE = /\{\{(?:person|duty)\.[A-Za-z0-9_]+\}\}/g;

/** Builds the editor representation of a placeholder: a non-editable pill. */
export function pillSpan(token: string, label: string): string {
  return `<span class="mail-token" data-token="${token}">${label}</span>`;
}

/** Stored HTML (raw {{tokens}}) -> editor HTML (pill spans). */
export function tokensToPills(html: string, placeholders: PlaceholderTile[]): string {
  const labels = new Map<string, string>();
  placeholders.forEach((p) => labels.set(p.token, p.label['de'] || p.fieldName));
  return html.replace(TOKEN_RE, (token) => pillSpan(token, labels.get(token) ?? token));
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

/** Fixed sample values used only for the client-side preview. Key is the full token. */
export const SAMPLE_VALUES: Record<string, string> = {
  '{{person.firstName}}': 'Anna',
  '{{person.lastName}}': 'Muster',
  '{{person.email}}': 'anna.muster@example.org',
  '{{person.phone}}': '+43 660 1234567',
  '{{person.dateOfBirth}}': '15.03.2015',
  '{{person.gender}}': 'weiblich',
  '{{person.notes}}': 'Allergien beachten',
  '{{duty.date}}': '10.08.2026',
  '{{duty.groups}}': 'Baeren, Fuechse',
  '{{duty.description}}': 'Gemuesesuppe',
  '{{duty.daysBefore}}': '2',
  '{{duty.personName}}': 'Anna Muster',
};

/** Stored HTML -> preview HTML with sample data (unknown tokens blanked). */
export function renderPreview(storedHtml: string, samples: Record<string, string>): string {
  return storedHtml.replace(TOKEN_RE, (token) => samples[token] ?? '');
}
