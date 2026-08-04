import { of, throwError } from 'rxjs';
import { MailBlockConfigDialogComponent } from './mail-block-config-dialog.component';
import { MatDialogRef } from '@angular/material/dialog';
import { DomSanitizer } from '@angular/platform-browser';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { MailTemplateService } from '../../../../shared/services/mail-template.service';

const GROUPS: FieldInstanceDTO[] = [
  { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
];

const fakeSanitizer = { bypassSecurityTrustHtml: (v: string) => v } as unknown as DomSanitizer;

class FakeDialogRef {
  closedWith: unknown;
  close(result?: unknown) {
    this.closedWith = result;
  }
}

class FakeMailTemplateService {
  previewCalls: MailBlockConfig[] = [];
  previewResult: { html: string } = { html: '<table></table>' };
  previewShouldError = false;

  previewBlock(config: MailBlockConfig) {
    this.previewCalls.push(config);
    if (this.previewShouldError) {
      return throwError(() => new Error('preview failed'));
    }
    return of(this.previewResult);
  }
}

describe('MailBlockConfigDialogComponent', () => {
  const config: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };
  let dialogRef: FakeDialogRef;
  let mailTemplateService: FakeMailTemplateService;
  let component: MailBlockConfigDialogComponent;

  beforeEach(() => {
    dialogRef = new FakeDialogRef();
    mailTemplateService = new FakeMailTemplateService();
    component = new MailBlockConfigDialogComponent(
      dialogRef as unknown as MatDialogRef<MailBlockConfigDialogComponent>,
      { blockType: 'cookingDuty', config, groups: GROUPS },
      mailTemplateService as unknown as MailTemplateService,
      fakeSanitizer,
    );
  });

  it('builds a form pre-filled from the given config', () => {
    expect(component.form.value).toEqual({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 });
  });

  it('is invalid when no group is chosen', () => {
    component.form.patchValue({ groupId: '' });
    expect(component.form.invalid).toBe(true);
  });

  it('save() closes the dialog with the form value when valid', () => {
    component.form.patchValue({ periodAmount: 4 });
    component.save();
    expect(dialogRef.closedWith).toEqual({ type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 4 });
  });

  it('save() does nothing when the form is invalid', () => {
    component.form.patchValue({ groupId: '' });
    component.save();
    expect(dialogRef.closedWith).toBeUndefined();
  });

  it('cancel() closes the dialog without a result', () => {
    component.cancel();
    expect(dialogRef.closedWith).toBeUndefined();
  });

  it('does not call previewBlock when switching to the Konfiguration tab (index 0)', () => {
    component.onTabChange(0);
    expect(mailTemplateService.previewCalls.length).toBe(0);
  });

  it('calls previewBlock once when switching to the Vorschau tab with a valid form', () => {
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(1);
    expect(mailTemplateService.previewCalls[0]).toEqual(config);
    expect(component.previewHtml).toBe('<table></table>');
    expect(component.previewLoading).toBe(false);
    expect(component.previewError).toBe(false);
  });

  it('does not call previewBlock again on a repeated switch without a config change', () => {
    component.onTabChange(1);
    component.onTabChange(0);
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(1);
  });

  it('calls previewBlock again after the config changes and the tab is revisited', () => {
    component.onTabChange(1);
    component.form.patchValue({ periodAmount: 4 });
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(2);
  });

  it('does not call previewBlock when the form is invalid', () => {
    component.form.patchValue({ groupId: '' });
    component.onTabChange(1);
    expect(mailTemplateService.previewCalls.length).toBe(0);
    expect(component.previewHtml).toBe('');
  });

  it('sets previewError and clears loading when the request fails', () => {
    mailTemplateService.previewShouldError = true;
    component.onTabChange(1);
    expect(component.previewError).toBe(true);
    expect(component.previewLoading).toBe(false);
  });

  it('clears a stale previewError when revisiting a config that previously loaded successfully', () => {
    // Config A (the initial config) loads successfully.
    component.onTabChange(1);
    expect(component.previewHtml).toBe('<table></table>');
    expect(component.previewError).toBe(false);

    // Switch to config B and make the load fail.
    component.form.patchValue({ periodAmount: 4 });
    mailTemplateService.previewShouldError = true;
    component.onTabChange(1);
    expect(component.previewError).toBe(true);

    // Restore config A (already cached) and revisit the Vorschau tab.
    mailTemplateService.previewShouldError = false;
    component.form.patchValue({ periodAmount: 2 });
    component.onTabChange(1);

    expect(component.previewError).toBe(false);
    expect(component.previewHtml).toBe('<table></table>');
    // Only the one failed call for config B should have happened; config A is
    // served from the cache and does not trigger a third HTTP call.
    expect(mailTemplateService.previewCalls.length).toBe(2);
  });
});
