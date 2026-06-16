import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { FieldDefinition } from '../../shared/models/field-definition.model';

export interface CustomFieldDialogData {
  field?: FieldDefinition;
}

export interface CustomFieldDialogResult {
  fieldName: string;
  labelDe: string;
  labelEn: string;
  description: string;
  schemaType: SchemaType;
  options: string;
  required: boolean;
  keycloakMapping: string;
}

type SchemaType = 'text' | 'number' | 'date' | 'boolean' | 'select';

@Component({
  selector: 'app-custom-fields-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatButtonModule,
  ],
  templateUrl: './custom-fields-dialog.component.html',
})
export class CustomFieldsDialogComponent implements OnInit {
  form!: FormGroup;
  isEditMode: boolean;

  constructor(
    private dialogRef: MatDialogRef<CustomFieldsDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CustomFieldDialogData,
  ) {
    this.isEditMode = !!data?.field;
  }

  ngOnInit(): void {
    const f = this.data?.field;
    this.form = new FormGroup({
      fieldName: new FormControl(
        { value: f?.fieldName ?? '', disabled: this.isEditMode },
        Validators.required,
      ),
      labelDe: new FormControl(f?.label?.['de'] ?? '', Validators.required),
      labelEn: new FormControl(f?.label?.['en'] ?? '', Validators.required),
      description: new FormControl(f?.description ?? ''),
      schemaType: new FormControl<SchemaType>(this.detectSchemaType(f), Validators.required),
      options: new FormControl(this.detectOptions(f)),
      required: new FormControl(f?.required ?? false),
      keycloakMapping: new FormControl(f?.keycloakMapping ?? ''),
    });
  }

  private detectSchemaType(f?: FieldDefinition): SchemaType {
    if (!f?.jsonSchema) return 'text';
    const s = f.jsonSchema;
    if (s['type'] === 'boolean') return 'boolean';
    if (s['type'] === 'number' || s['type'] === 'integer') return 'number';
    if (s['enum']) return 'select';
    if (s['format'] === 'date') return 'date';
    return 'text';
  }

  private detectOptions(f?: FieldDefinition): string {
    const enumVals = f?.jsonSchema?.['enum'] as string[] | undefined;
    return enumVals ? enumVals.join(', ') : '';
  }

  get isSelectType(): boolean {
    return this.form.get('schemaType')?.value === 'select';
  }

  submit(): void {
    if (this.form.invalid) return;
    this.dialogRef.close(this.form.getRawValue() as CustomFieldDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
