import { MailBlockConfigDialogComponent } from './mail-block-config-dialog.component';
import { MatDialogRef } from '@angular/material/dialog';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig } from '../../../../shared/models/mail-block.model';

const GROUPS: FieldInstanceDTO[] = [
  { id: 'g1', definitionId: 'd1', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
];

class FakeDialogRef {
  closedWith: unknown;
  close(result?: unknown) {
    this.closedWith = result;
  }
}

describe('MailBlockConfigDialogComponent', () => {
  const config: CookingDutyBlockConfig = { type: 'cookingDuty', groupId: 'g1', periodUnit: 'week', periodAmount: 2 };
  let dialogRef: FakeDialogRef;
  let component: MailBlockConfigDialogComponent;

  beforeEach(() => {
    dialogRef = new FakeDialogRef();
    component = new MailBlockConfigDialogComponent(
      dialogRef as unknown as MatDialogRef<MailBlockConfigDialogComponent>,
      { blockType: 'cookingDuty', config, groups: GROUPS },
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
});
