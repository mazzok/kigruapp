import { Component, Input, OnInit, ViewChild } from '@angular/core';
import { FieldDefinition } from '../../models/field-definition.model';
import { FieldInstanceDTO } from '../../models/field-instance.model';
import { SectionInput } from '../../models/person.model';
import { FieldDefinitionService } from '../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../section-form/section-form.component';

@Component({
  selector: 'app-custom-fields-form',
  standalone: true,
  imports: [SectionFormComponent],
  template: `
    @if (definitions.length > 0) {
      <app-section-form
        #sectionForm
        [definitions]="definitions"
        [existingFields]="existingFields"
      ></app-section-form>
    }
  `,
})
export class CustomFieldsFormComponent implements OnInit {
  @Input() existingFields: FieldInstanceDTO[] = [];
  @ViewChild('sectionForm') sectionForm?: SectionFormComponent;

  definitions: FieldDefinition[] = [];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.definitions = defs;
    });
  }

  get isValid(): boolean {
    return this.sectionForm?.isValid ?? true;
  }

  getValues(): SectionInput[] {
    return this.sectionForm?.getValues() ?? [];
  }
}
