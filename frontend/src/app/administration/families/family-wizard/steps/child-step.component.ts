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

  getBasicProperties(): SectionInput[] {
    return this.sectionForm?.getValues() ?? [];
  }
}
