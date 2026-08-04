import { registerMailTokenBlot } from '../mail/mail-template-editor/mail-token.blot';

/**
 * Die Startseite wird im Browser gerendert, nicht in einem Mail-Client — die
 * Toolbar darf daher mehr anbieten als EMAIL_SAFE_QUILL_TOOLBAR: Überschriften,
 * Listen und Einzug funktionieren hier über Quills eigene Stylesheet-Klassen.
 *
 * Der Platzhalter-Blot ist derselbe wie beim Mail-Editor: er ist rein
 * data-getrieben und kennt keine Mail-Besonderheiten.
 */
let configured = false;

export function configureQuillForWebOutput(): void {
  if (configured) {
    return;
  }
  configured = true;
  registerMailTokenBlot();
}

export const WEB_QUILL_TOOLBAR = [
  [{ header: [1, 2, 3, false] }],
  ['bold', 'italic', 'underline', 'strike'],
  [{ color: [] }, { background: [] }],
  [{ list: 'ordered' }, { list: 'bullet' }],
  [{ indent: '-1' }, { indent: '+1' }],
  [{ align: [] }],
  ['blockquote'],
  ['link', 'image'],
  ['clean'],
];
