import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCardModule } from '@angular/material/card';
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
    MatNativeDateModule,
    MatSlideToggleModule,
    MatCardModule,
  ],
  template: `
    @if (fieldType === 'boolean') {
      <mat-slide-toggle [formControl]="control">
        {{ label }}
      </mat-slide-toggle>
    } @else if (fieldType === 'select') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <mat-select [formControl]="control">
          @for (opt of enumOptions; track opt) {
            <mat-option [value]="opt">{{ opt }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    } @else if (fieldType === 'date') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput [matDatepicker]="picker" [formControl]="control">
        <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
        <mat-datepicker #picker></mat-datepicker>
      </mat-form-field>
    } @else if (fieldType === 'time') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput type="time" [formControl]="control">
      </mat-form-field>
    } @else if (fieldType === 'number') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput type="number" [formControl]="control">
      </mat-form-field>
    } @else if (fieldType === 'object') {
      <mat-card class="fieldgroup">
        <mat-card-header>
          <mat-card-title>{{ label }}</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @for (prop of objectProperties; track prop.key) {
            <app-json-schema-field
              [dto]="prop.dto"
              [control]="getObjectControl(prop.key)"
            ></app-json-schema-field>
          }
        </mat-card-content>
      </mat-card>
    } @else {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput [formControl]="control">
      </mat-form-field>
    }
  `,
  styles: [`
    .full-width { width: 100%; }
    .fieldgroup { margin: 8px 0; }
    .fieldgroup mat-card-content { padding: 16px; }
  `],
})
export class JsonSchemaFieldComponent {
  @Input({ required: true }) dto!: FieldInstanceDTO;
  @Input({ required: true }) control!: FormControl;

  get label(): string {
    return this.dto.label['de'] || this.dto.fieldName;
  }

  get fieldType(): string {
    const schema = this.dto.jsonSchema;
    if (!schema) return 'text';

    const type = schema['type'] as string;
    if (type === 'boolean') return 'boolean';
    if (type === 'number' || type === 'integer') return 'number';
    if (type === 'object' && schema['properties']) return 'object';
    if (type === 'string') {
      if (schema['enum']) return 'select';
      if (schema['format'] === 'date') return 'date';
      if (schema['format'] === 'time') return 'time';
    }
    return 'text';
  }

  get enumOptions(): string[] {
    return (this.dto.jsonSchema?.['enum'] as string[]) ?? [];
  }

  get objectProperties(): { key: string; dto: FieldInstanceDTO }[] {
    const props = this.dto.jsonSchema?.['properties'] as Record<string, Record<string, unknown>> | undefined;
    if (!props) return [];
    return Object.entries(props).map(([key, schema]) => ({
      key,
      dto: {
        definitionId: this.dto.definitionId,
        fieldName: key,
        label: { de: key.charAt(0).toUpperCase() + key.slice(1) },
        jsonSchema: schema,
        required: false,
        value: null,
        definitionOutdated: false,
      } as FieldInstanceDTO,
    }));
  }

  getObjectControl(key: string): FormControl {
    const group = this.control as unknown as FormGroup;
    if (!group.contains(key)) {
      group.addControl(key, new FormControl(null));
    }
    return group.get(key) as FormControl;
  }
}
