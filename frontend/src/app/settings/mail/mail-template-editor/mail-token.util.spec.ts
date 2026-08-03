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

  it('reduces a real Quill-embed-serialized pill (nested contentNode + FEFF guards) to a clean raw token', () => {
    const editor =
      '<p>Hallo ﻿' +
      '<span class="mail-token" data-token="{{person.firstName}}" data-label="Vorname">' +
      '﻿<span contenteditable="false">Vorname</span>﻿</span>' +
      '﻿</p>';
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
    ['firstName','lastName','email','phone','dateOfBirth','gender','notes']
      .forEach((f) => expect(SAMPLE_VALUES[f]).toBeTruthy());
  });
});
