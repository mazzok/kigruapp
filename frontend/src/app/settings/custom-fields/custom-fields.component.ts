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
import { FieldDefinitionService } from './services/field-definition.service';
import { FieldDefinition, EntityType, FieldType } from '../../shared/models/field-definition.model';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatTableModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule,
  ],
  templateUrl: './custom-fields.component.html',
  styleUrl: './custom-fields.component.scss',
})
export class CustomFieldsComponent implements OnInit {
  displayedColumns = ['entity', 'fieldName', 'labelDe', 'labelEn', 'type', 'required', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  entityTypes: EntityType[] = ['CHILD', 'PARENT', 'FAMILY'];
  fieldTypes: FieldType[] = ['TEXT', 'DATE', 'SELECT', 'BOOLEAN'];

  form = new FormGroup({
    entity: new FormControl<EntityType>('CHILD', Validators.required),
    fieldName: new FormControl('', Validators.required),
    labelDe: new FormControl('', Validators.required),
    labelEn: new FormControl('', Validators.required),
    type: new FormControl<FieldType>('TEXT', Validators.required),
    options: new FormControl(''),
    required: new FormControl(false),
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
    const fieldDef: FieldDefinition = {
      entity: val.entity!,
      fieldName: val.fieldName!,
      label: { de: val.labelDe!, en: val.labelEn! },
      type: val.type!,
      options: val.type === 'SELECT' ? val.options!.split(',').map((o) => o.trim()) : undefined,
      required: val.required!,
    };

    this.fieldDefService.create(fieldDef).subscribe(() => {
      this.form.reset({ entity: 'CHILD', type: 'TEXT', required: false });
      this.loadData();
    });
  }

  deleteField(id: string): void {
    this.fieldDefService.delete(id).subscribe(() => this.loadData());
  }
}
