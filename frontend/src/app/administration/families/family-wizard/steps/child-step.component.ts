import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { SectionInput } from '../../../../shared/models/person.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [CommonModule, SectionFormComponent],
  template: `
    <h3>Kind</h3>
    @if (definitions.length > 0) {
      <app-section-form
        #sectionForm
        [definitions]="definitions"
      ></app-section-form>
    }
  `,
})
export class ChildStepComponent implements OnInit {
  private static readonly ALLOWED_FIELDS = ['firstName', 'lastName', 'dateOfBirth', 'gender', 'address'];

  @ViewChild('sectionForm') sectionForm?: SectionFormComponent;

  definitions: FieldDefinition[] = [];
  private personTypeDef?: FieldDefinition;

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
      this.definitions = defs.filter((d) => ChildStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
    });
  }

  get isValid(): boolean {
    return this.sectionForm?.isValid ?? true;
  }

  prefill(lastName: string, address?: { street: string; zip: string; city: string } | null): void {
    this.sectionForm?.setValueByFieldName('lastName', lastName);
    if (address) {
      this.sectionForm?.setValueByFieldName('address', address);
    }
  }

  getBasicProperties(): SectionInput[] {
    const values = this.sectionForm?.getValues() ?? [];
    if (this.personTypeDef?.id) {
      values.push({ definitionId: this.personTypeDef.id, value: 'CHILD' });
    }
    return values;
  }
}
