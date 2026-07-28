import { familyMonthlyMinutes } from './required-hours-preview.util';

describe('familyMonthlyMinutes', () => {
  it('multiplies default with no tiers', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(960);
  });

  it('applies nested tiers (8h / ab2=6h / ab3=0h)', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }, { fromChild: 3, minutesPerMonth: 0 }] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 3)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 4)).toBe(840);
  });
});
