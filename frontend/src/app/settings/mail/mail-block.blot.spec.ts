import Quill from 'quill';
import { registerMailBlockBlot } from './mail-template-editor/mail-block.blot';

describe('MailBlockBlot', () => {
  beforeAll(() => registerMailBlockBlot());

  function newEditor(): Quill {
    const host = document.createElement('div');
    document.body.appendChild(host);
    return new Quill(host);
  }

  it('inserts a block card carrying type, config and summary', () => {
    const quill = newEditor();
    quill.insertEmbed(0, 'mail-block', {
      blockType: 'cookingDuty',
      config: { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 },
      summary: 'Kochdienst: Gruppe Sonne, nächste 2 Wochen',
    });

    const card = quill.root.querySelector('div.mail-block') as HTMLElement;
    expect(card).toBeTruthy();
    expect(card.getAttribute('data-block-type')).toBe('cookingDuty');
    expect(JSON.parse(card.getAttribute('data-config') ?? '{}')).toEqual({
      type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2,
    });
    expect(card.querySelector('.mail-block-summary')?.textContent).toBe('Kochdienst: Gruppe Sonne, nächste 2 Wochen');
    expect(card.querySelector('.mail-block-edit-btn')).toBeTruthy();
  });

  it('registers idempotently (second call does not throw)', () => {
    expect(() => registerMailBlockBlot()).not.toThrow();
  });
});
