import { blockDefinitionsForKind, MAIL_BLOCK_DEFINITIONS } from './mail-block.model';

describe('blockDefinitionsForKind', () => {
  it('gibt den Kochdienst-Baustein nur fuer COOKING_OVERVIEW zurueck', () => {
    expect(blockDefinitionsForKind('COOKING_OVERVIEW')).toEqual(MAIL_BLOCK_DEFINITIONS);
  });

  it('gibt keine Bausteine fuer GENERAL zurueck', () => {
    expect(blockDefinitionsForKind('GENERAL')).toEqual([]);
  });

  it('gibt keine Bausteine fuer COOKING_REMINDER zurueck', () => {
    expect(blockDefinitionsForKind('COOKING_REMINDER')).toEqual([]);
  });
});
