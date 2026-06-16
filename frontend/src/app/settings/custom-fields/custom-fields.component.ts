import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FieldDefinitionService } from './services/field-definition.service';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import {
  CustomFieldsDialogComponent,
  CustomFieldDialogData,
  CustomFieldDialogResult,
} from './custom-fields-dialog.component';

type SchemaType = 'text' | 'number' | 'date' | 'boolean' | 'select';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule, MatButtonModule, MatIconModule, MatDialogModule,
    CustomFieldsDialogComponent,
  ],
  templateUrl: './custom-fields.component.html',
  styleUrl: './custom-fields.component.scss',
})
export class CustomFieldsComponent implements OnInit {
  displayedColumns = ['fieldName', 'labelDe', 'description', 'schemaType', 'required', 'status', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  constructor(
    private fieldDefService: FieldDefinitionService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.fieldDefService.list().subscribe(defs => {
      this.dataSource.data = defs;
    });
  }

  openAddDialog(): void {
    this.dialog.open(CustomFieldsDialogComponent, {
      data: {} as CustomFieldDialogData,
    }).afterClosed().subscribe((result: CustomFieldDialogResult | undefined) => {
      if (result) {
        this.fieldDefService.create(this.buildFieldDef(result)).subscribe(() => this.loadData());
      }
    });
  }

  openEditDialog(field: FieldDefinition): void {
    this.dialog.open(CustomFieldsDialogComponent, {
      data: { field } as CustomFieldDialogData,
    }).afterClosed().subscribe((result: CustomFieldDialogResult | undefined) => {
      if (result) {
        this.fieldDefService.update(field.id!, this.buildFieldDef(result)).subscribe(() => this.loadData());
      }
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

  private buildFieldDef(result: CustomFieldDialogResult): FieldDefinition {
    return {
      fieldName: result.fieldName,
      label: { de: result.labelDe, en: result.labelEn },
      description: result.description || undefined,
      jsonSchema: this.buildJsonSchema(result.schemaType, result.options),
      required: result.required,
      keycloakMapping: result.keycloakMapping || null,
    };
  }

  private buildJsonSchema(schemaType: SchemaType, optionsStr: string): Record<string, unknown> {
    switch (schemaType) {
      case 'text':    return { type: 'string' };
      case 'number':  return { type: 'number' };
      case 'date':    return { type: 'string', format: 'date' };
      case 'boolean': return { type: 'boolean' };
      case 'select': {
        const options = optionsStr.split(',').map(o => o.trim()).filter(o => o);
        return { type: 'string', enum: options };
      }
    }
  }
}
