import {
  TOKEN_RE,
  pillSpan,
  tokensToPills,
  pillsToTokens,
  renderWithContext,
} from './landing-token.util';
import { LandingPlaceholder } from './models/landing-page.model';

const PLACEHOLDERS: LandingPlaceholder[] = [
  { token: '{{person.firstName}}', label: 'Vorname', group: 'person' },
  { token: '{{stunden.bilanz}}', label: 'Stunden-Bilanz', group: 'stunden' },
];

describe('landing-token.util', () => {
  beforeEach(() => (TOKEN_RE.lastIndex = 0));

  it('erkennt Tokens aller Familien', () => {
    const found = '<p>{{person.firstName}} {{stunden.bilanz}} {{kochdienst.naechsterTermin}}</p>'
      .match(TOKEN_RE);
    expect(found).toEqual([
      '{{person.firstName}}',
      '{{stunden.bilanz}}',
      '{{kochdienst.naechsterTermin}}',
    ]);
  });

  it('erzeugt eine Pille mit Token und Beschriftung', () => {
    expect(pillSpan('{{person.firstName}}', 'Vorname'))
      .toBe('<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>');
  });

  it('wandelt Tokens in Pillen mit deutscher Beschriftung', () => {
    const result = tokensToPills('<p>Hallo {{person.firstName}}</p>', PLACEHOLDERS);
    expect(result).toContain('data-token="{{person.firstName}}"');
    expect(result).toContain('>Vorname<');
  });

  it('nutzt den Token als Beschriftung, wenn keine Kachel dazu bekannt ist', () => {
    const result = tokensToPills('<p>{{kochdienst.naechsterTermin}}</p>', PLACEHOLDERS);
    expect(result).toContain('>{{kochdienst.naechsterTermin}}<');
  });

  it('wandelt Pillen zurück in Tokens', () => {
    const html = '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>';
    expect(pillsToTokens(html)).toBe('<p>Hallo {{person.firstName}}</p>');
  });

  it('ist über den Roundtrip Token → Pille → Token verlustfrei', () => {
    const original = '<p>Hallo {{person.firstName}}, Bilanz {{stunden.bilanz}}</p>';
    expect(pillsToTokens(tokensToPills(original, PLACEHOLDERS))).toBe(original);
  });

  it('ersetzt Tokens durch Kontextwerte', () => {
    const html = '<p>Hallo {{person.firstName}}</p>';
    expect(renderWithContext(html, { '{{person.firstName}}': 'Anna' }))
      .toBe('<p>Hallo Anna</p>');
  });

  it('ersetzt unbekannte Tokens durch einen Gedankenstrich', () => {
    expect(renderWithContext('<p>{{person.firstName}}</p>', {}))
      .toBe('<p>–</p>');
  });

  it('ersetzt leere Kontextwerte ebenfalls durch einen Gedankenstrich', () => {
    expect(renderWithContext('<p>{{kochdienst.naechsterTermin}}</p>', { '{{kochdienst.naechsterTermin}}': '' }))
      .toBe('<p>–</p>');
  });
});
