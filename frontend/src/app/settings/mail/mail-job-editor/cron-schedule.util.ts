/**
 * Value-based schedule model that the UI edits instead of a raw cron string.
 * buildQuartzCron() renders it to a Quartz expression (sec min hour dom month dow);
 * parseQuartzCron() recovers the model from an expression the builder produced,
 * so editing an existing job repopulates the controls.
 */
export type CronFrequency = 'MINUTELY' | 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY';

export interface ScheduleModel {
  frequency: CronFrequency;
  minute: number; // 0-59
  hour: number; // 0-23
  dayOfMonth: number; // 1-31
  weekdays: Weekday[]; // for WEEKLY
}

export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

export const WEEKDAYS: { value: Weekday; label: string }[] = [
  { value: 'MON', label: 'Mo' },
  { value: 'TUE', label: 'Di' },
  { value: 'WED', label: 'Mi' },
  { value: 'THU', label: 'Do' },
  { value: 'FRI', label: 'Fr' },
  { value: 'SAT', label: 'Sa' },
  { value: 'SUN', label: 'So' },
];

export const DEFAULT_SCHEDULE: ScheduleModel = {
  frequency: 'DAILY',
  minute: 0,
  hour: 8,
  dayOfMonth: 1,
  weekdays: ['MON'],
};

function pad(n: number): string {
  return n < 10 ? '0' + n : String(n);
}

/** Build a Quartz cron expression (6 fields) from the value model. */
export function buildQuartzCron(m: ScheduleModel): string {
  const min = clamp(m.minute, 0, 59);
  const hr = clamp(m.hour, 0, 23);
  const dom = clamp(m.dayOfMonth, 1, 31);
  switch (m.frequency) {
    case 'MINUTELY':
      return '0 * * * * ?';
    case 'HOURLY':
      return `0 ${min} * * * ?`;
    case 'DAILY':
      return `0 ${min} ${hr} * * ?`;
    case 'WEEKLY': {
      const days = m.weekdays.length ? m.weekdays : ['MON'];
      return `0 ${min} ${hr} ? * ${days.join(',')}`;
    }
    case 'MONTHLY':
      return `0 ${min} ${hr} ${dom} * ?`;
  }
}

/** Best-effort parse of a builder-produced Quartz expression back into the model. */
export function parseQuartzCron(cron: string | null | undefined): ScheduleModel | null {
  if (!cron) return null;
  const parts = cron.trim().split(/\s+/);
  if (parts.length < 6) return null;
  const [, min, hour, dom, , dow] = parts;

  const model: ScheduleModel = { ...DEFAULT_SCHEDULE, weekdays: [...DEFAULT_SCHEDULE.weekdays] };

  if (min === '*') {
    model.frequency = 'MINUTELY';
    return model;
  }
  const minute = toInt(min);
  if (minute === null) return null;
  model.minute = minute;

  if (hour === '*') {
    model.frequency = 'HOURLY';
    return model;
  }
  const hr = toInt(hour);
  if (hr === null) return null;
  model.hour = hr;

  if (dom !== '*' && dom !== '?') {
    const d = toInt(dom);
    if (d === null) return null;
    model.frequency = 'MONTHLY';
    model.dayOfMonth = d;
    return model;
  }

  if (dow !== '*' && dow !== '?') {
    const days = dow.split(',').map((d) => d.toUpperCase()) as Weekday[];
    const valid = days.filter((d) => WEEKDAYS.some((w) => w.value === d));
    model.frequency = 'WEEKLY';
    model.weekdays = valid.length ? valid : ['MON'];
    return model;
  }

  model.frequency = 'DAILY';
  return model;
}

/** Human-readable German summary of what the schedule does. */
export function describeSchedule(m: ScheduleModel): string {
  const time = `${pad(clamp(m.hour, 0, 23))}:${pad(clamp(m.minute, 0, 59))}`;
  switch (m.frequency) {
    case 'MINUTELY':
      return 'Läuft jede Minute';
    case 'HOURLY':
      return `Läuft stündlich zur Minute ${pad(clamp(m.minute, 0, 59))}`;
    case 'DAILY':
      return `Läuft täglich um ${time}`;
    case 'WEEKLY': {
      const days = (m.weekdays.length ? m.weekdays : ['MON'])
        .map((d) => WEEKDAYS.find((w) => w.value === d)?.label ?? d)
        .join(', ');
      return `Läuft wöchentlich (${days}) um ${time}`;
    }
    case 'MONTHLY':
      return `Läuft monatlich am ${clamp(m.dayOfMonth, 1, 31)}. um ${time}`;
  }
}

function clamp(n: number, lo: number, hi: number): number {
  if (Number.isNaN(n)) return lo;
  return Math.min(hi, Math.max(lo, Math.trunc(n)));
}

function toInt(s: string): number | null {
  if (!/^\d+$/.test(s)) return null;
  return parseInt(s, 10);
}
