import { TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { JsonSchemaFieldComponent } from './json-schema-field.component';
import { FieldInstanceDTO } from '../../models/field-instance.model';

const DATE_DTO: FieldInstanceDTO = {
  definitionId: 'def-date',
  fieldName: 'dateOfBirth',
  label: { de: 'Geburtsdatum' },
  jsonSchema: { type: 'string', format: 'date' },
  required: false,
  value: null,
  definitionOutdated: false,
};

describe('JsonSchemaFieldComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JsonSchemaFieldComponent],
      providers: [provideNativeDateAdapter(), provideAnimations()],
    }).compileComponents();
  });

  it('rendert mat-datepicker für Feld mit format:date', () => {
    const fixture = TestBed.createComponent(JsonSchemaFieldComponent);
    fixture.componentInstance.dto = DATE_DTO;
    fixture.componentInstance.control = new FormControl(null);
    fixture.detectChanges();

    const picker = fixture.nativeElement.querySelector('mat-datepicker');
    expect(picker).withContext('mat-datepicker soll im DOM vorhanden sein').toBeTruthy();

    const toggle = fixture.nativeElement.querySelector('mat-datepicker-toggle');
    expect(toggle).withContext('mat-datepicker-toggle soll im DOM vorhanden sein').toBeTruthy();
  });
});
