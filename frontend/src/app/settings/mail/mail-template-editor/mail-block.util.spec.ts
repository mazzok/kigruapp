import {
  blockSpan, markersToEmbeds, embedsToMarkers, cookingDutyBlockSummary, instanceLabel,
} from './mail-block.util';
import { CookingDutyBlockConfig } from '../../../shared/models/mail-block.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';

const CONFIG: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };

describe('mail-block.util', () => {
  it('blockSpan builds a non-editable card carrying the block type, config and summary', () => {
    const html = blockSpan('cookingDuty', CONFIG, 'Kochdienst: Rote Gruppe, nächste 2 Wochen');
    expect(html).toContain('class="mail-block"');
    expect(html).toContain('data-block-type="cookingDuty"');
    expect(html).toContain('data-config="{&quot;type&quot;:&quot;cookingDuty&quot;,&quot;groupId&quot;:&quot;g1&quot;,&quot;periodUnit&quot;:&quot;week&quot;,&quot;periodAmount&quot;:2}"');
    expect(html).toContain('mail-block-summary">Kochdienst: Rote Gruppe, nächste 2 Wochen<');
    expect(html).toContain('mail-block-edit-btn');
  });

  it('markersToEmbeds replaces a stored marker with the block card, resolving the summary', () => {
    const marker = embedsToMarkers(blockSpan('cookingDuty', CONFIG, 'ignored'));
    const out = markersToEmbeds(`<p>Hallo</p>${marker}`, (type, cfg) => `resolved:${type}:${(cfg as CookingDutyBlockConfig).groupId}`);
    expect(out).toContain('data-block-type="cookingDuty"');
    expect(out).toContain('mail-block-summary">resolved:cookingDuty:g1<');
  });

  it('embedsToMarkers turns a block card back into a raw marker', () => {
    const html = blockSpan('cookingDuty', CONFIG, 'Kochdienst: Rote Gruppe, nächste 2 Wochen');
    const marker = embedsToMarkers(`<p>Hallo</p>${html}`);
    expect(marker).toMatch(/^<p>Hallo<\/p>\{\{block\.cookingDuty:[A-Za-z0-9_\-=]+\}\}$/);
  });

  it('round-trips marker -> embed -> marker unchanged in payload', () => {
    const originalMarker = embedsToMarkers(blockSpan('cookingDuty', CONFIG, 'ignored'));
    const embed = markersToEmbeds(originalMarker, () => 'ignored');
    const roundTripped = embedsToMarkers(embed);
    expect(roundTripped).toBe(originalMarker);
  });

  it('cookingDutyBlockSummary uses singular week/month wording for amount 1', () => {
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 1, periodUnit: 'week' }, 'Rote Gruppe'))
      .toBe('Kochdienst: Rote Gruppe, nächste 1 Woche');
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 1, periodUnit: 'month' }, 'Rote Gruppe'))
      .toBe('Kochdienst: Rote Gruppe, nächste 1 Monat');
  });

  it('cookingDutyBlockSummary uses plural wording for amount > 1 and falls back when no group is chosen', () => {
    expect(cookingDutyBlockSummary({ ...CONFIG, periodAmount: 3, periodUnit: 'month' }, null))
      .toBe('Kochdienst: Gruppe wählen, nächste 3 Monate');
  });

  it('blockSpan escapes & before " so a config value containing both round-trips intact', () => {
    const cfg = { ...CONFIG, groupId: 'Rot & "Blau"' } as unknown as CookingDutyBlockConfig;
    const html = blockSpan('cookingDuty', cfg, 'ignored');
    const match = html.match(/data-config="([^"]*)"/);
    expect(match).toBeTruthy();
    const attrValue = match![1];
    // Simulate the browser un-escaping the HTML attribute (order: &amp; -> &, &quot; -> ")
    // the way a real DOMParser would when reading data-config back.
    const div = document.createElement('div');
    div.innerHTML = `<div data-config="${attrValue}"></div>`;
    const roundTripped = (div.firstElementChild as HTMLElement).getAttribute('data-config');
    expect(JSON.parse(roundTripped!)).toEqual(cfg);
  });

  it('markersToEmbeds leaves a marker with corrupted base64/JSON untouched instead of throwing', () => {
    const corrupted = '<p>Hallo</p>{{block.cookingDuty:not-valid-base64===}}';
    expect(() => markersToEmbeds(corrupted, () => 'ignored')).not.toThrow();
    const out = markersToEmbeds(corrupted, () => 'ignored');
    expect(out).toBe(corrupted);
  });

  it('instanceLabel prefers value.label, then the field label, then the field name', () => {
    const withValueLabel: FieldInstanceDTO = { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false };
    const withoutValueLabel: FieldInstanceDTO = { ...withValueLabel, value: {} };
    const withNothing: FieldInstanceDTO = { ...withValueLabel, value: {}, label: {} };
    expect(instanceLabel(withValueLabel)).toBe('Rote Gruppe');
    expect(instanceLabel(withoutValueLabel)).toBe('Gruppen');
    expect(instanceLabel(withNothing)).toBe('group');
  });
});
