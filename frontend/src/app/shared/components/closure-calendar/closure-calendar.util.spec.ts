import { buildMonths, dayBackground, isWeekend, CalendarDay } from './closure-calendar.util';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-01T00:00:00Z',
};
const fortbildung: ClosureDefinition = {
  id: 'def-fortbildung', label: 'Fortbildung', color: '#e0a020', active: true, createdAt: '2026-07-02T00:00:00Z',
};

function period(id: string, from: string, to: string, definitionId: string): ClosurePeriod {
  return { id, from, to, definitionId };
}

function findDay(months: ReturnType<typeof buildMonths>, iso: string): CalendarDay {
  const day = months.flatMap(m => m.days).find(d => d.date === iso);
  if (!day) throw new Error(`Tag ${iso} nicht im Raster`);
  return day;
}

describe('closure-calendar.util', () => {
  describe('isWeekend', () => {
    it('erkennt Samstag und Sonntag', () => {
      expect(isWeekend('2026-09-12')).toBe(true);
      expect(isWeekend('2026-09-13')).toBe(true);
    });

    it('erkennt Werktage', () => {
      expect(isWeekend('2026-09-07')).toBe(false);
      expect(isWeekend('2026-09-11')).toBe(false);
    });
  });

  describe('buildMonths', () => {
    it('rendert genau das uebergebene Fenster', () => {
      const months = buildMonths('2026-09-07', '2026-09-11', [], [], []);

      expect(months.length).toBe(1);
      expect(months[0].days.length).toBe(5);
      expect(months[0].days[0].date).toBe('2026-09-07');
      expect(months[0].days[4].date).toBe('2026-09-11');
    });

    it('teilt ueber mehrere Monate auf', () => {
      const months = buildMonths('2026-09-01', '2027-02-28', [], [], []);

      expect(months.length).toBe(6);
      expect(months[0].label).toBe('September 2026');
      expect(months[5].label).toBe('Februar 2027');
    });

    it('setzt leadingBlanks auf den Wochentag des ersten gerenderten Tages', () => {
      // 2026-09-07 ist ein Montag -> keine Luecke.
      expect(buildMonths('2026-09-07', '2026-09-11', [], [], [])[0].leadingBlanks).toBe(0);
      // 2026-09-09 ist ein Mittwoch -> zwei Luecken.
      expect(buildMonths('2026-09-09', '2026-09-11', [], [], [])[0].leadingBlanks).toBe(2);
    });

    it('markiert Wochenenden als nicht auswaehlbar', () => {
      const months = buildMonths('2026-09-07', '2026-09-13', [], [], []);

      expect(findDay(months, '2026-09-11').selectable).toBe(true);
      expect(findDay(months, '2026-09-12').selectable).toBe(false);
      expect(findDay(months, '2026-09-13').selectable).toBe(false);
    });

    it('erlaubt Wochenenden als auswaehlbar, wenn restrictWeekends=false', () => {
      const months = buildMonths('2026-09-07', '2026-09-13', [], [], [], false);

      expect(findDay(months, '2026-09-12').selectable).toBe(true);
      expect(findDay(months, '2026-09-13').selectable).toBe(true);
    });

    it('blockiert Feiertage weiterhin, auch wenn restrictWeekends=false', () => {
      const holiday: Holiday = { date: '2026-09-08', name: 'Test-Feiertag' };
      const months = buildMonths('2026-09-07', '2026-09-09', [], [], [holiday], false);

      expect(findDay(months, '2026-09-08').selectable).toBe(false);
    });

    it('markiert Feiertage als nicht auswaehlbar und traegt den Namen ein', () => {
      const holidays: Holiday[] = [{ date: '2026-10-26', name: 'Nationalfeiertag' }];
      const months = buildMonths('2026-10-26', '2026-10-27', [], [], holidays);

      expect(findDay(months, '2026-10-26').selectable).toBe(false);
      expect(findDay(months, '2026-10-26').holidayName).toBe('Nationalfeiertag');
      expect(findDay(months, '2026-10-27').selectable).toBe(true);
      expect(findDay(months, '2026-10-27').holidayName).toBeNull();
    });

    it('faerbt Tage innerhalb eines Zeitraums', () => {
      const periods = [period('p1', '2026-09-07', '2026-09-09', 'def-ferien')];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien], []);

      expect(findDay(months, '2026-09-08').colors).toEqual(['#d94f4f']);
      expect(findDay(months, '2026-09-08').labels).toEqual(['Ferien']);
      expect(findDay(months, '2026-09-10').colors).toEqual([]);
    });

    it('sammelt bei Mehrfachzuordnung alle Farben und Label', () => {
      const periods = [
        period('p1', '2026-09-07', '2026-09-09', 'def-ferien'),
        period('p2', '2026-09-08', '2026-09-08', 'def-fortbildung'),
      ];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien, fortbildung], []);

      expect(findDay(months, '2026-09-08').colors).toEqual(['#d94f4f', '#e0a020']);
      expect(findDay(months, '2026-09-08').labels).toEqual(['Ferien', 'Fortbildung']);
    });

    it('faerbt auch Wochenenden innerhalb eines Zeitraums', () => {
      // Ein Zeitraum darf Wochenenden ueberspannen; sie werden mitgefaerbt,
      // bleiben aber nicht auswaehlbar.
      const periods = [period('p1', '2026-09-07', '2026-09-18', 'def-ferien')];
      const months = buildMonths('2026-09-07', '2026-09-18', periods, [ferien], []);

      expect(findDay(months, '2026-09-12').colors).toEqual(['#d94f4f']);
      expect(findDay(months, '2026-09-12').selectable).toBe(false);
    });

    it('ignoriert Zeitraeume mit unbekannter Definition', () => {
      const periods = [period('p1', '2026-09-07', '2026-09-09', 'geloescht')];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien], []);

      expect(findDay(months, '2026-09-08').colors).toEqual([]);
    });

    it('liefert ein leeres Raster bei ungueltigem Fenster', () => {
      expect(buildMonths('2026-09-11', '2026-09-07', [], [], [])).toEqual([]);
      expect(buildMonths('', '', [], [], [])).toEqual([]);
    });
  });

  describe('dayBackground', () => {
    function day(colors: string[]): CalendarDay {
      return { date: '2026-09-08', dayOfMonth: 8, selectable: true, holidayName: null, colors, labels: [] };
    }

    it('liefert nichts ohne Zuordnung', () => {
      expect(dayBackground(day([]))).toBe('');
    });

    it('liefert die Farbe bei einer Zuordnung', () => {
      expect(dayBackground(day(['#d94f4f']))).toBe('#d94f4f');
    });

    it('teilt den Tag bei zwei Zuordnungen in gleiche Haelften', () => {
      expect(dayBackground(day(['#d94f4f', '#4f86d9'])))
        .toBe('linear-gradient(90deg, #d94f4f 0% 50%, #4f86d9 50% 100%)');
    });

    it('teilt den Tag bei drei Zuordnungen in gleiche Drittel', () => {
      const result = dayBackground(day(['#a', '#b', '#c']));
      expect(result).toContain('#a 0% 33.333%');
      expect(result).toContain('#b 33.333% 66.667%');
      expect(result).toContain('#c 66.667% 100%');
    });
  });
});
