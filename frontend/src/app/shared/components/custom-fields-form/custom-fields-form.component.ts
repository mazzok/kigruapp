import { Component, Input, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { EntityType } from '../../models/field-definition.model';
import { FieldInstanceDTO } from '../../models/field-instance.model';
import { FieldInstanceService } from '../../services/field-instance.service';
import { FieldDefinitionService } from '../../../settings/custom-fields/services/field-definition.service';
import { JsonSchemaFieldComponent } from '../json-schema-field/json-schema-field.component';

@Component({
  selector: 'app-custom-fields-form',
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
export class CustomFieldsFormComponent implements OnInit, OnChanges {
  @Input({ required: true }) entityType!: EntityType;
  @Input() entityId: string | null = null;

  fieldDTOs: FieldInstanceDTO[] = [];
  controls: Record<string, FormControl> = {};
  form = new FormGroup({});

  constructor(
    private fieldInstanceService: FieldInstanceService,
    private fieldDefService: FieldDefinitionService,
  ) {}

  ngOnInit(): void {
    this.loadFields();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['entityId'] && !changes['entityId'].firstChange) {
      this.loadFields();
    }
  }

  get isValid(): boolean {
    return this.form.valid;
  }

  getValues(): { definitionId: string; value: unknown }[] {
    return this.fieldDTOs
      .filter((dto) => !dto.definitionOutdated)
      .map((dto) => ({
        definitionId: dto.definitionId,
        value: this.controls[dto.definitionId]?.value ?? null,
      }));
  }

  saveInstances(entityId: string) {
    const values = this.getValues();
    if (values.length === 0) return;
    return this.fieldInstanceService.batchSave(this.entityType, entityId, values);
  }

  private loadFields(): void {
    if (this.entityId) {
      this.fieldInstanceService.listForEntity(this.entityType, this.entityId).subscribe((dtos) => {
        this.buildControls(dtos);
      });
    } else {
      this.fieldDefService.listActive(this.entityType).subscribe((defs) => {
        const dtos: FieldInstanceDTO[] = defs.map((def) => ({
          definitionId: def.id!,
          fieldName: def.fieldName,
          label: def.label,
          description: def.description,
          jsonSchema: def.jsonSchema,
          required: def.required,
          value: null,
          definitionOutdated: false,
        }));
        this.buildControls(dtos);
      });
    }
  }

  private buildControls(dtos: FieldInstanceDTO[]): void {
    this.fieldDTOs = dtos;
    this.controls = {};
    this.form = new FormGroup({});

    for (const dto of dtos) {
      const validators = dto.required ? [Validators.required] : [];
      const control = new FormControl(dto.value, validators);
      if (dto.definitionOutdated) {
        control.disable();
      }
      this.controls[dto.definitionId] = control;
      this.form.addControl(dto.definitionId, control);
    }
  }
}
