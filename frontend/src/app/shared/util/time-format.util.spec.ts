import { parseHhmm, formatMinutes, toIsoDate, parseIsoDate, formatIsoDateDe } from './time-format.util';

describe('time-format.util', () => {
  it('parses HH:MM to total minutes', () => {
    expect(parseHhmm('01:30')).toBe(90);
    expect(parseHhmm('0:45')).toBe(45);
    expect(parseHhmm('10:00')).toBe(600);
  });

  it('rejects invalid HH:MM', () => {
    expect(parseHhmm('1:60')).toBeNull();
    expect(parseHhmm('abc')).toBeNull();
    expect(parseHhmm('')).toBeNull();
    expect(parseHhmm('00:00')).toBeNull(); // Dauer 0 ist ungültig
  });

  it('formats minutes back to HH:MM', () => {
    expect(formatMinutes(90)).toBe('01:30');
    expect(formatMinutes(600)).toBe('10:00');
  });

  it('converts Date to YYYY-MM-DD (local, no TZ shift)', () => {
    expect(toIsoDate(new Date(2026, 9, 5))).toBe('2026-10-05');
  });

  it('parses YYYY-MM-DD to a local Date', () => {
    const d = parseIsoDate('2026-10-05')!;
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(9);
    expect(d.getDate()).toBe(5);
  });

  it('formats YYYY-MM-DD as DD.MM.YYYY', () => {
    expect(formatIsoDateDe('2026-10-05')).toBe('05.10.2026');
  });
});
