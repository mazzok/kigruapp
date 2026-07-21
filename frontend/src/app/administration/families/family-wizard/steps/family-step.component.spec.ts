import { FamilyStepComponent } from './family-step.component';

describe('FamilyStepComponent', () => {
  let component: FamilyStepComponent;

  beforeEach(() => {
    component = new FamilyStepComponent();
  });

  it('is invalid when the name is empty', () => {
    expect(component.isValid).toBe(false);
  });

  it('is valid once a name is entered', () => {
    component.form.patchValue({ newFamilyName: 'Familie Müller' });
    expect(component.isValid).toBe(true);
  });

  it('trims the family name', () => {
    component.form.patchValue({ newFamilyName: '  Familie Müller  ' });
    expect(component.newFamilyName).toBe('Familie Müller');
  });

  it('returns null address when all address fields are empty', () => {
    expect(component.address).toBeNull();
  });

  it('returns a trimmed address when any address field is set', () => {
    component.form.patchValue({ street: ' Hauptstr. 1 ', zip: '1010', city: ' Wien ' });
    expect(component.address).toEqual({ street: 'Hauptstr. 1', zip: '1010', city: 'Wien' });
  });

  it('prefills name and address from an existing family via editFamily', () => {
    component.editFamily = {
      id: 'f1',
      name: 'Familie Bestand',
      address: { street: 'Ring 3', zip: '4020', city: 'Linz' },
    };

    expect(component.newFamilyName).toBe('Familie Bestand');
    expect(component.address).toEqual({ street: 'Ring 3', zip: '4020', city: 'Linz' });
  });
});
