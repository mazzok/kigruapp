import { Component, Input, OnInit, QueryList, ViewChild, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { PersonDTO, SectionInput } from '../../../../shared/models/person.model';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, SectionFormComponent],
  template: `
    @if (isEditMode) {
      @for (entry of childEntries; track $index; let i = $index) {
        <div class="child-block">
          <div style="display:flex; align-items:center; justify-content:space-between;">
            <h4 style="margin:0">Kind {{ i + 1 }}</h4>
            <button mat-icon-button color="warn" (click)="removeChild(i)">
              <mat-icon>delete</mat-icon>
            </button>
          </div>
          @if (definitions.length > 0) {
            <app-section-form #childForm
              [definitions]="definitions"
              [existingFields]="entry.existingFields">
            </app-section-form>
          }
        </div>
      }
      <button mat-stroked-button (click)="addChild()" style="margin-top:8px">
        <mat-icon>child_care</mat-icon> Kind hinzufügen
      </button>
    } @else {
      <h3>Kind</h3>
      @if (definitions.length > 0) {
        <app-section-form #sectionForm [definitions]="definitions"></app-section-form>
      }
    }
  `,
  styles: [`.child-block { margin-bottom: 24px; border-bottom: 1px solid #ccc; padding-bottom: 16px; }`],
})
export class ChildStepComponent implements OnInit {
  private static readonly ALLOWED_FIELDS = ['firstName', 'lastName', 'dateOfBirth', 'gender', 'address'];

  @ViewChild('sectionForm') sectionForm?: SectionFormComponent;
  @ViewChildren('childForm') childForms!: QueryList<SectionFormComponent>;

  definitions: FieldDefinition[] = [];
  private personTypeDef?: FieldDefinition;

  @Input() set existingChildren(children: { id: string; dto: PersonDTO }[]) {
    if (!children || children.length === 0) return;
    this._existingChildren = children;
    // If definitions already loaded, build entries immediately
    if (this.definitions.length > 0) {
      this.buildEditEntries();
    }
  }
  private _existingChildren: { id: string; dto: PersonDTO }[] = [];

  get isEditMode(): boolean {
    return this._existingChildren.length > 0;
  }

  childEntries: { id?: string; existingFields: FieldInstanceDTO[] }[] = [];
  removedChildIds: string[] = [];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
      this.definitions = defs.filter((d) => ChildStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
      if (this._existingChildren.length > 0) {
        this.buildEditEntries();
      }
    });
  }

  private buildEditEntries(): void {
    this.childEntries = this._existingChildren.map((c) => ({
      id: c.id,
      existingFields: c.dto.basicProperties ?? [],
    }));
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

  addChild(): void {
    this.childEntries.push({ existingFields: [] });
  }

  removeChild(index: number): void {
    const entry = this.childEntries[index];
    if (entry.id) {
      this.removedChildIds.push(entry.id);
    }
    this.childEntries.splice(index, 1);
  }

  getChildrenData(): { id?: string; basicProperties: SectionInput[] }[] {
    return this.childForms.toArray().map((form, i) => {
      const values = form.getValues();
      if (this.personTypeDef?.id) {
        values.push({ definitionId: this.personTypeDef.id, value: 'CHILD' });
      }
      return { id: this.childEntries[i]?.id, basicProperties: values };
    });
  }
}
