import { discountFactors } from './kosten-discount-preview.util';

describe('discountFactors', () => {
  it('assigns full price to child 1 and tier percents thereafter', () => {
    const rows = discountFactors([{ fromChild: 2, percent: 50 }, { fromChild: 3, percent: 100 }], 4);
    expect(rows).toEqual([
      { child: 1, percent: 0 },
      { child: 2, percent: 50 },
      { child: 3, percent: 100 },
      { child: 4, percent: 100 },
    ]);
  });

  it('is full price everywhere with no tiers', () => {
    expect(discountFactors([], 2)).toEqual([{ child: 1, percent: 0 }, { child: 2, percent: 0 }]);
  });
});
