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
export function blockSpan(blockType: string, config: MailBlockConfig, summary: string): string {
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
