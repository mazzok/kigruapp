import { familyMonthlyMinutes, groupCombinationMinutes } from './required-hours-preview.util';

describe('familyMonthlyMinutes', () => {
  it('multiplies default with no tiers', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(960);
  });

  it('applies nested tiers (8h / ab2=25% / ab3=100%)', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, percent: 25 }, { fromChild: 3, percent: 100 }] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 3)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 4)).toBe(840);
  });

  it('rechnet Prozent-Staffeln auf den Default an', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, percent: 25 }] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(840);    // 480 + 360
    expect(familyMonthlyMinutes(cfg, 3)).toBe(1200);   // 480 + 360 + 360
  });

  it('behandelt 100 % Rabatt als beitragsfrei', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 3, percent: 100 }] };
    expect(familyMonthlyMinutes(cfg, 3)).toBe(960);
  });
});

describe('groupCombinationMinutes', () => {
  it('vergibt Rabatte bei Gruppensätzen nach der teuersten Gruppe zuerst', () => {
    const minutes = groupCombinationMinutes([300, 480], [{ fromChild: 2, percent: 25 }], 'MOST_EXPENSIVE_FIRST');
    expect(minutes).toBe(705);   // 480 voll + 300 minus 25 %
  });

  it('vergibt Rabatte bei Gruppensätzen nach der günstigsten Gruppe zuerst', () => {
    const minutes = groupCombinationMinutes([300, 480], [{ fromChild: 2, percent: 25 }], 'LEAST_EXPENSIVE_FIRST');
    expect(minutes).toBe(660);   // 300 voll + 480 minus 25 %
  });
});
