import { Component, Input, OnInit, QueryList, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { SectionInput } from '../../../../shared/models/person.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-parents-step',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    SectionFormComponent,
  ],
  template: `
    <h3>Elternteile</h3>
    @for (idx of parentIndices; track idx) {
      <div class="parent-block">
        <h4>
          Elternteil {{ idx + 1 }}
          @if (idx > 0) {
            <button mat-icon-button (click)="removeParent(idx)">
              <mat-icon>delete</mat-icon>
            </button>
          }
        </h4>
        @if (definitions.length > 0) {
          <app-section-form
            #parentForm
            [definitions]="definitions"
          ></app-section-form>
        }
      </div>
    }
    <button mat-stroked-button (click)="addParent()">
      <mat-icon>add</mat-icon> Elternteil hinzufuegen
    </button>
  `,
  styles: [`.parent-block { margin-bottom: 24px; border-bottom: 1px solid #ccc; padding-bottom: 16px; }`],
})
export class ParentsStepComponent implements OnInit {
  private static readonly ALLOWED_FIELDS = ['firstName', 'lastName', 'email', 'phone', 'dateOfBirth', 'address'];

  @ViewChildren('parentForm') parentForms!: QueryList<SectionFormComponent>;
  @Input() keycloakPrefill: { firstName: string; lastName: string; email: string } | null = null;

  definitions: FieldDefinition[] = [];
  private personTypeDef?: FieldDefinition;
  parentIndices: number[] = [0];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.personTypeDef = defs.find((d) => d.fieldName === 'personType');
      this.definitions = defs.filter((d) => ParentsStepComponent.ALLOWED_FIELDS.includes(d.fieldName));
      if (this.keycloakPrefill) {
        setTimeout(() => this.applyKeycloakPrefill(), 0);
      }
    });
  }

  addParent(): void {
    this.parentIndices.push(this.parentIndices.length);
  }

  removeParent(index: number): void {
    this.parentIndices.splice(index, 1);
    this.parentIndices = this.parentIndices.map((_, i) => i);
  }

  private applyKeycloakPrefill(): void {
    const first = this.parentForms?.first;
    if (!first || !this.keycloakPrefill) return;
    first.setValueByFieldName('firstName', this.keycloakPrefill.firstName);
    first.setValueByFieldName('lastName', this.keycloakPrefill.lastName);
    first.setValueByFieldName('email', this.keycloakPrefill.email);
  }

  prefill(lastName: string, address?: { street: string; zip: string; city: string } | null): void {
    this.parentForms?.forEach((f) => {
      f.setValueByFieldName('lastName', lastName);
      if (address) {
        f.setValueByFieldName('address', address);
      }
    });
  }

  get isValid(): boolean {
    return this.parentForms?.length > 0 &&
      this.parentForms.toArray().every((f) => f.isValid);
  }

  getParentsBasicProperties(): SectionInput[][] {
    return this.parentForms.toArray().map((f) => {
      const values = f.getValues();
      if (this.personTypeDef?.id) {
        values.push({ definitionId: this.personTypeDef.id, value: 'PARENT' });
      }
      return values;
    });
  }
}
