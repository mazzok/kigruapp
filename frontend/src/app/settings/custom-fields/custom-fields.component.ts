import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { FieldDefinitionService } from './services/field-definition.service';
import { FieldDefinition } from '../../shared/models/field-definition.model';

type SchemaType = 'text' | 'number' | 'date' | 'boolean' | 'select';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatTableModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatChipsModule,
  ],
  templateUrl: './custom-fields.component.html',
  styleUrl: './custom-fields.component.scss',
})
export class CustomFieldsComponent implements OnInit {
  displayedColumns = ['fieldName', 'labelDe', 'description', 'schemaType', 'required', 'status', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  schemaTypes: { value: SchemaType; label: string }[] = [
    { value: 'text', label: 'Text' },
    { value: 'number', label: 'Zahl' },
    { value: 'date', label: 'Datum' },
    { value: 'boolean', label: 'Ja/Nein' },
    { value: 'select', label: 'Auswahl' },
  ];

  form = new FormGroup({
    fieldName: new FormControl('', Validators.required),
    labelDe: new FormControl('', Validators.required),
    labelEn: new FormControl('', Validators.required),
    description: new FormControl(''),
    schemaType: new FormControl<SchemaType>('text', Validators.required),
    options: new FormControl(''),
    required: new FormControl(false),
    keycloakMapping: new FormControl(''),
  });

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.fieldDefService.list().subscribe((defs) => {
      this.dataSource.data = defs;
    });
  }

  addField(): void {
    if (!this.form.valid) return;

    const val = this.form.value;
    const jsonSchema = this.buildJsonSchema(val.schemaType!, val.options || '');

    const fieldDef: FieldDefinition = {
      fieldName: val.fieldName!,
      label: { de: val.labelDe!, en: val.labelEn! },
      description: val.description || undefined,
      jsonSchema,
      required: val.required!,
      keycloakMapping: val.keycloakMapping || null,
    };

    this.fieldDefService.create(fieldDef).subscribe(() => {
      this.form.reset({ schemaType: 'text', required: false });
      this.loadData();
    });
  }

  outdateField(id: string): void {
    this.fieldDefService.outdate(id).subscribe(() => this.loadData());
  }

  getSchemaTypeLabel(def: FieldDefinition): string {
    const schema = def.jsonSchema;
    if (!schema) return '?';
    const type = schema['type'] as string;
    if (type === 'boolean') return 'Ja/Nein';
    if (type === 'number' || type === 'integer') return 'Zahl';
    if (type === 'string') {
      if (schema['enum']) return 'Auswahl';
      if (schema['format'] === 'date') return 'Datum';
      return 'Text';
    }
    return type || '?';
  }

  isOutdated(def: FieldDefinition): boolean {
    return !!def.outdatedAt;
  }

  private buildJsonSchema(schemaType: SchemaType, optionsStr: string): Record<string, unknown> {
    switch (schemaType) {
      case 'text':
        return { type: 'string' };
      case 'number':
        return { type: 'number' };
      case 'date':
        return { type: 'string', format: 'date' };
      case 'boolean':
        return { type: 'boolean' };
      case 'select': {
        const options = optionsStr.split(',').map((o) => o.trim()).filter((o) => o);
        return { type: 'string', enum: options };
      }
    }
  }
}
