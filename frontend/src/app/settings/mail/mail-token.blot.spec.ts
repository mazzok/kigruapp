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
    // Quill's `blots/embed` base wraps embed content and appends zero-width
    // guard text nodes (U+FEFF) around it for cursor navigation, so strip
    // them before comparing the visible label text.
    const guardChar = String.fromCharCode(0xfeff);
    expect(span.textContent?.split(guardChar).join('')).toBe('Vorname');
  });

  it('registers idempotently (second call does not throw)', () => {
    expect(() => registerMailTokenBlot()).not.toThrow();
  });
});
