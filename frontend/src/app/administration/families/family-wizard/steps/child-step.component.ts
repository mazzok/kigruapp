import { Component, Input, OnInit, QueryList, ViewChildren } from '@angular/core';
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
    <div class="section-head">
      <h3>Kinder <span class="count">{{ childEntries.length }}</span></h3>
      <button mat-stroked-button (click)="addChild()">
        <mat-icon>child_care</mat-icon> Kind hinzufügen
      </button>
    </div>
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
  `,
  styles: [`
    .section-head { position:sticky; top:60px; z-index:5; background:#fff; display:flex; align-items:center; justify-content:space-between; gap:12px; padding-top:4px; padding-bottom:12px; margin-bottom:16px; border-bottom:1px solid #ccc; }
    .section-head h3 { margin:0; display:flex; align-items:center; gap:8px; }
    .section-head .count { font-size:12px; font-weight:500; color:rgba(0,0,0,0.54); }
    .child-block { margin-bottom: 24px; border-bottom: 1px solid #ccc; padding-bottom: 16px; }
  `],
})
export class ChildStepComponent implements OnInit {
  private static readonly ALLOWED_FIELDS = ['firstName', 'lastName', 'dateOfBirth', 'gender', 'address'];

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

  childEntries: { id?: string; existingFields: FieldInstanceDTO[] }[] = [{ existingFields: [] }];
  removedChildIds: string[] = [];

  private prefillLastName?: string;
  private prefillAddress?: { street: string; zip: string; city: string } | null;

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
    return this.childForms?.length > 0 &&
      this.childForms.toArray().every((f) => f.isValid);
  }

  prefill(lastName: string, address?: { street: string; zip: string; city: string } | null): void {
    this.prefillLastName = lastName;
    this.prefillAddress = address;
    this.childForms?.forEach((f) => {
      f.setValueByFieldName('lastName', lastName);
      if (address) {
        f.setValueByFieldName('address', address);
      }
    });
  }

  addChild(): void {
    this.childEntries.push({ existingFields: [] });
    if (this.prefillLastName || this.prefillAddress) {
      setTimeout(() => {
        const form = this.childForms?.last;
        if (!form) return;
        if (this.prefillLastName) {
          form.setValueByFieldName('lastName', this.prefillLastName);
        }
        if (this.prefillAddress) {
          form.setValueByFieldName('address', this.prefillAddress);
        }
      }, 0);
    }
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
