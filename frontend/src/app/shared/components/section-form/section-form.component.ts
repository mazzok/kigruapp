import { Component, Input, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { FieldDefinition } from '../../models/field-definition.model';
import { FieldInstanceDTO } from '../../models/field-instance.model';
import { SectionInput } from '../../models/person.model';
import { JsonSchemaFieldComponent } from '../json-schema-field/json-schema-field.component';

@Component({
  selector: 'app-section-form',
  standalone: true,
  imports: [JsonSchemaFieldComponent],
  template: `
    @for (dto of fieldDTOs; track dto.definitionId) {
      <app-json-schema-field
        [dto]="dto"
        [control]="controls[dto.definitionId]"
      ></app-json-schema-field>
    }
  `,
})
export class SectionFormComponent implements OnInit {
  @Input({ required: true }) definitions!: FieldDefinition[];
  @Input() existingFields: FieldInstanceDTO[] = [];

  fieldDTOs: FieldInstanceDTO[] = [];
  controls: Record<string, FormControl> = {};
  form = new FormGroup({});

  ngOnInit(): void {
    this.buildForm();
  }

  get isValid(): boolean {
    return this.form.valid;
  }

  setValueByFieldName(fieldName: string, value: unknown): void {
    const dto = this.fieldDTOs.find((d) => d.fieldName === fieldName);
    if (dto) {
      const ctrl = this.controls[dto.definitionId];
      if (ctrl && !ctrl.dirty) {
        ctrl.setValue(value);
      }
    }
  }

  getValues(): SectionInput[] {
    return this.fieldDTOs
      .filter((dto) => !dto.definitionOutdated)
      .map((dto) => {
        let value: unknown = this.controls[dto.definitionId]?.value ?? null;
        if (value instanceof Date && dto.jsonSchema?.['format'] === 'date') {
          const y = value.getFullYear();
          const m = String(value.getMonth() + 1).padStart(2, '0');
          const d = String(value.getDate()).padStart(2, '0');
          value = `${y}-${m}-${d}`;
        }
        return { definitionId: dto.definitionId, value };
      });
  }

  private buildForm(): void {
    const existingByDefId = new Map(
      this.existingFields.map((f) => [f.definitionId, f])
    );

    this.fieldDTOs = this.definitions.map((def) => {
      const existing = existingByDefId.get(def.id!);
      return {
        definitionId: def.id!,
        fieldName: def.fieldName,
        label: def.label,
        description: def.description,
        jsonSchema: def.jsonSchema,
        required: def.required,
        keycloakMapping: def.keycloakMapping,
        value: existing?.value ?? null,
        definitionOutdated: def.outdatedAt != null,
      } as FieldInstanceDTO;
    });

    this.controls = {};
    this.form = new FormGroup({});

    for (const dto of this.fieldDTOs) {
      const validators = dto.required ? [Validators.required] : [];
      const isObject = dto.jsonSchema?.['type'] === 'object';
      const control = isObject
        ? new FormGroup({}) as unknown as FormControl
        : new FormControl(dto.value, validators);

      if (isObject && dto.value && typeof dto.value === 'object') {
        const group = control as unknown as FormGroup;
        for (const [key, val] of Object.entries(dto.value as Record<string, unknown>)) {
          group.addControl(key, new FormControl(val));
        }
      }

      if (dto.definitionOutdated) {
        control.disable();
      }
      this.controls[dto.definitionId] = control;
      this.form.addControl(dto.definitionId, control);
    }
  }
}
