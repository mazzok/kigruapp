import { Component, Input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { FieldInstanceDTO } from '../../models/field-instance.model';

@Component({
  selector: 'app-json-schema-field',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatSlideToggleModule,
  ],
  template: `
    @if (fieldType === 'boolean') {
      <mat-slide-toggle [formControl]="control">
        {{ dto.label['de'] || dto.fieldName }}
      </mat-slide-toggle>
    } @else if (fieldType === 'select') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ dto.label['de'] || dto.fieldName }}</mat-label>
        <mat-select [formControl]="control">
          @for (opt of enumOptions; track opt) {
            <mat-option [value]="opt">{{ opt }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    } @else if (fieldType === 'date') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ dto.label['de'] || dto.fieldName }}</mat-label>
        <input matInput [matDatepicker]="picker" [formControl]="control">
        <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
        <mat-datepicker #picker></mat-datepicker>
      </mat-form-field>
    } @else if (fieldType === 'number') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ dto.label['de'] || dto.fieldName }}</mat-label>
        <input matInput type="number" [formControl]="control">
      </mat-form-field>
    } @else {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ dto.label['de'] || dto.fieldName }}</mat-label>
        <input matInput [formControl]="control">
      </mat-form-field>
    }
  `,
  styles: [`.full-width { width: 100%; }`],
})
export class JsonSchemaFieldComponent {
  @Input({ required: true }) dto!: FieldInstanceDTO;
  @Input({ required: true }) control!: FormControl;

  get fieldType(): string {
    const schema = this.dto.jsonSchema;
    if (!schema) return 'text';

    const type = schema['type'] as string;
    if (type === 'boolean') return 'boolean';
    if (type === 'number' || type === 'integer') return 'number';
    if (type === 'string') {
      if (schema['enum']) return 'select';
      if (schema['format'] === 'date') return 'date';
    }
    return 'text';
  }

  get enumOptions(): string[] {
    return (this.dto.jsonSchema?.['enum'] as string[]) ?? [];
  }
}
