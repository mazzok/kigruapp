import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { SectionFormComponent } from './section-form.component';
import { FieldDefinition } from '../../models/field-definition.model';

const DATE_DEFINITION: FieldDefinition = {
  id: 'def-dob',
  fieldName: 'dateOfBirth',
  label: { de: 'Geburtsdatum', en: 'Date of Birth' },
  jsonSchema: { type: 'string', format: 'date' },
  required: false,
  outdatedAt: undefined,
  keycloakMapping: null,
} as unknown as FieldDefinition;

describe('SectionFormComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SectionFormComponent],
      providers: [provideNativeDateAdapter(), provideAnimations()],
    }).compileComponents();
  });

  describe('getValues()', () => {
    it('serialisiert Date-Objekte zu YYYY-MM-DD String (Ortszeit)', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      // Datum in Ortszeit: 13. November 2016
      fixture.componentInstance.controls['def-dob'].setValue(new Date(2016, 10, 13)); // Monat 0-basiert

      const values = fixture.componentInstance.getValues();

      expect(values.length).toBe(1);
      expect(values[0].definitionId).toBe('def-dob');
      expect(values[0].value).toBe('2016-11-13');
    });

    it('lässt nicht-Date Werte unverändert', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      fixture.componentInstance.controls['def-dob'].setValue('2016-11-13');

      const values = fixture.componentInstance.getValues();
      expect(values[0].value).toBe('2016-11-13');
    });

    it('lässt null Werte unverändert', () => {
      const fixture = TestBed.createComponent(SectionFormComponent);
      fixture.componentInstance.definitions = [DATE_DEFINITION];
      fixture.detectChanges();

      const values = fixture.componentInstance.getValues();
      expect(values[0].value).toBeNull();
    });
  });
});
