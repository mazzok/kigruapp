import { closedDatesFrom } from './cooking-closure.util';

describe('Kochdienst und Schließtage', () => {
  describe('closedDatesFrom', () => {
    it('expandiert Zeitraeume auf einzelne Tage', () => {
      const closed = closedDatesFrom(
        [{ id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def' }], []);

      expect([...closed].sort()).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
    });

    it('nimmt Feiertage mit auf', () => {
      const closed = closedDatesFrom([], [{ date: '2026-10-26', name: 'Nationalfeiertag' }]);

      expect(closed.has('2026-10-26')).toBe(true);
    });

    it('bleibt bei leeren Eingaben leer', () => {
      expect(closedDatesFrom([], []).size).toBe(0);
    });
  });
});
