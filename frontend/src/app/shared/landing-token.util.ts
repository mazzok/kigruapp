import { LandingContext, LandingPlaceholder } from './models/landing-page.model';

/** Ein Token beliebiger Familie, z.B. {{person.firstName}} oder {{stunden.bilanz}}. */
export const TOKEN_RE = /\{\{[a-z]+\.[A-Za-z]+\}\}/g;

/** Zeichen, das für einen fehlenden oder leeren Wert steht. */
const MISSING = '–';

/**
 * Editor-Darstellung eines Platzhalters. Klasse und data-Attribut sind
 * bewusst dieselben wie beim Mail-Editor: beide nutzen denselben Quill-Blot.
 */
export function pillSpan(token: string, label: string): string {
  return `<span class="mail-token" data-token="${token}">${label}</span>`;
}

/** Gespeichertes HTML (rohe Tokens) -> Editor-HTML (Pillen). */
export function tokensToPills(html: string, placeholders: LandingPlaceholder[]): string {
  const labels = new Map<string, string>();
  placeholders.forEach((p) => labels.set(p.token, p.label));
  return html.replace(TOKEN_RE, (token) => pillSpan(token, labels.get(token) ?? token));
}

/**
 * Editor-HTML -> gespeichertes HTML mit rohen Tokens. DOM-basiert, weil Quill
 * die Pille in verschachtelte Spans mit FEFF-Schutzzeichen einbettet, die ein
 * regulärer Ausdruck nicht zuverlässig zurückbaut.
 */
export function pillsToTokens(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('[data-token]').forEach((el) => {
    el.replaceWith(doc.createTextNode(el.getAttribute('data-token') ?? ''));
  });
  return doc.body.innerHTML.replace(/﻿/g, '');
}

/**
 * Gespeichertes HTML -> Anzeige-HTML. Fehlende und leere Werte werden zu einem
 * Gedankenstrich: die Startseite soll auch dann lesbar bleiben, wenn ein
 * Datensatz fehlt oder der Kontext gar nicht geladen werden konnte.
 */
export function renderWithContext(html: string, context: LandingContext): string {
  return html.replace(TOKEN_RE, (token) => {
    const value = context[token];
    return value ? value : MISSING;
  });
}
