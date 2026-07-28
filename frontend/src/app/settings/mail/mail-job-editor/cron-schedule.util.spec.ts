import {
  buildQuartzCron,
  parseQuartzCron,
  describeSchedule,
  DEFAULT_SCHEDULE,
  ScheduleModel,
} from './cron-schedule.util';

function model(over: Partial<ScheduleModel>): ScheduleModel {
  return { ...DEFAULT_SCHEDULE, weekdays: [...DEFAULT_SCHEDULE.weekdays], ...over };
}

describe('cron-schedule.util', () => {
  describe('buildQuartzCron', () => {
    it('every minute', () => {
      expect(buildQuartzCron(model({ frequency: 'MINUTELY' }))).toBe('0 * * * * ?');
    });

    it('hourly at a given minute', () => {
      expect(buildQuartzCron(model({ frequency: 'HOURLY', minute: 15 }))).toBe('0 15 * * * ?');
    });

    it('daily at hour:minute', () => {
      expect(buildQuartzCron(model({ frequency: 'DAILY', hour: 8, minute: 30 }))).toBe('0 30 8 * * ?');
    });

    it('weekly on selected weekdays', () => {
      expect(buildQuartzCron(model({ frequency: 'WEEKLY', hour: 7, minute: 0, weekdays: ['MON', 'WED'] })))
        .toBe('0 0 7 ? * MON,WED');
    });

    it('weekly defaults to Monday when no weekday chosen', () => {
      expect(buildQuartzCron(model({ frequency: 'WEEKLY', hour: 7, minute: 0, weekdays: [] })))
        .toBe('0 0 7 ? * MON');
    });

    it('monthly on a given day', () => {
      expect(buildQuartzCron(model({ frequency: 'MONTHLY', dayOfMonth: 1, hour: 9, minute: 0 })))
        .toBe('0 0 9 1 * ?');
    });

    it('clamps out-of-range values', () => {
      expect(buildQuartzCron(model({ frequency: 'DAILY', hour: 99, minute: -5 }))).toBe('0 0 23 * * ?');
    });
  });

  describe('parseQuartzCron round-trips builder output', () => {
    const cases: ScheduleModel[] = [
      model({ frequency: 'MINUTELY' }),
      model({ frequency: 'HOURLY', minute: 15 }),
      model({ frequency: 'DAILY', hour: 8, minute: 30 }),
      model({ frequency: 'WEEKLY', hour: 7, minute: 0, weekdays: ['MON', 'WED'] }),
      model({ frequency: 'MONTHLY', dayOfMonth: 12, hour: 9, minute: 45 }),
    ];

    cases.forEach((m) => {
      it(`re-parses ${m.frequency}`, () => {
        const cron = buildQuartzCron(m);
        const parsed = parseQuartzCron(cron);
        expect(parsed).not.toBeNull();
        expect(buildQuartzCron(parsed!)).toBe(cron);
      });
    });

    it('returns null for garbage / too-few fields', () => {
      expect(parseQuartzCron('*****')).toBeNull();
      expect(parseQuartzCron('0 8 * * *')).toBeNull(); // unix 5-field
      expect(parseQuartzCron('')).toBeNull();
      expect(parseQuartzCron(null)).toBeNull();
    });
  });

  describe('describeSchedule', () => {
    it('reads out a daily schedule', () => {
      expect(describeSchedule(model({ frequency: 'DAILY', hour: 8, minute: 5 }))).toBe('Läuft täglich um 08:05');
    });

    it('reads out weekly weekdays', () => {
      expect(describeSchedule(model({ frequency: 'WEEKLY', hour: 7, minute: 0, weekdays: ['MON', 'FRI'] })))
        .toBe('Läuft wöchentlich (Mo, Fr) um 07:00');
    });
  });
});
