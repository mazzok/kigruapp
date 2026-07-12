import { Component, Inject, OnInit, Optional, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { StepperSelectionEvent } from '@angular/cdk/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { CreatePersonRequest, PersonDTO } from '../../../shared/models/person.model';
import { Family } from '../../../shared/models/family.model';
import { lastValueFrom } from 'rxjs';

@Component({
  selector: 'app-family-wizard',
  standalone: true,
  imports: [
    CommonModule,
    MatStepperModule, MatButtonModule, MatDialogModule,
    FamilyStepComponent, ChildStepComponent, ParentsStepComponent,
  ],
  templateUrl: './family-wizard.component.html',
  styleUrl: './family-wizard.component.scss',
})
export class FamilyWizardComponent implements OnInit {
  @ViewChild('stepper') stepper!: MatStepper;
  @ViewChild(FamilyStepComponent) familyStep!: FamilyStepComponent;
  @ViewChild(ChildStepComponent) childStep!: ChildStepComponent;
  @ViewChild(ParentsStepComponent) parentsStep!: ParentsStepComponent;

  submitting = false;
  loading = false;

  editFamily?: Family;
  existingChildren: { id: string; dto: PersonDTO }[] = [];
  existingParents: { id: string; dto: PersonDTO }[] = [];

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: { familyId?: string } | null,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {}

  get isEditMode(): boolean {
    return !!this.data?.familyId;
  }

  ngOnInit(): void {
    if (this.isEditMode) {
      this.loadEditData();
    }
  }

  private async loadEditData(): Promise<void> {
    this.loading = true;
    try {
      const familyId = this.data!.familyId!;
      const family = await lastValueFrom(this.familyService.get(familyId));
      this.editFamily = family;

      const persons = await lastValueFrom(this.personService.list(familyId));
      const dtos = await Promise.all(
        persons.filter((p) => !!p.id).map((p) => lastValueFrom(this.personService.getFull(p.id!)))
      );

      for (const dto of dtos) {
        const personType = dto.basicProperties?.find((f) => f.fieldName === 'personType')?.value;
        if (personType === 'CHILD') {
          this.existingChildren.push({ id: dto.id!, dto });
        } else {
          this.existingParents.push({ id: dto.id!, dto });
        }
      }
    } finally {
      this.loading = false;
    }
  }

  onStepChange(event: StepperSelectionEvent): void {
    const name = this.familyStep.newFamilyName;
    const address = this.familyStep.address;
    if (!name && !address) return;

    if (event.selectedIndex === 1) {
      this.childStep.prefill(name, address);
    } else if (event.selectedIndex === 2) {
      this.parentsStep.prefill(name, address);
    }
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  async submit(): Promise<void> {
    this.submitting = true;
    try {
      if (this.isEditMode) {
        await this.submitEdit();
      } else {
        await this.submitCreate();
      }
      this.dialogRef.close(true);
    } catch (err) {
      console.error('Wizard failed:', err);
      this.submitting = false;
    }
  }

  private async submitCreate(): Promise<void> {
    let familyId: string;
    if (this.familyStep.isNewFamily) {
      const family = await lastValueFrom(
        this.familyService.create({
          name: this.familyStep.newFamilyName,
          address: this.familyStep.address ?? undefined,
        })
      );
      familyId = family.id!;
    } else {
      familyId = this.familyStep.selectedFamilyId!;
    }

    const childRequest: CreatePersonRequest = {
      familyId,
      basicProperties: this.childStep.getBasicProperties(),
      roles: [],
      schedules: [],
      duties: [],
      finance: [],
      customProperties: [],
    };
    await lastValueFrom(this.personService.create(childRequest));

    const parentsProps = this.parentsStep?.getParentsBasicProperties() ?? [];
    for (const parentProps of parentsProps) {
      const parentRequest: CreatePersonRequest = {
        familyId,
        basicProperties: parentProps,
        roles: [],
        schedules: [],
        duties: [],
        finance: [],
        customProperties: [],
      };
      await lastValueFrom(this.personService.create(parentRequest));
    }
  }

  private async submitEdit(): Promise<void> {
    const familyId = this.data!.familyId!;

    // 1. Update family metadata
    await lastValueFrom(this.familyService.update(familyId, {
      name: this.familyStep.newFamilyName,
      address: this.familyStep.address ?? undefined,
    }));

    // 2. Save children (create new, update existing)
    const childrenData = this.childStep.getChildrenData();
    for (const child of childrenData) {
      const req: CreatePersonRequest = {
        familyId,
        basicProperties: child.basicProperties,
        // roles/schedules/duties/finance/customProperties intentionally omitted to preserve existing values
      };
      if (child.id) {
        await lastValueFrom(this.personService.update(child.id, req));
      } else {
        await lastValueFrom(this.personService.create(req));
      }
    }

    // 3. Save parents (create new, update existing)
    const parentsData = this.parentsStep.getParentsData();
    for (const parent of parentsData) {
      const req: CreatePersonRequest = {
        familyId,
        basicProperties: parent.basicProperties,
        // roles/schedules/duties/finance/customProperties intentionally omitted to preserve existing values
      };
      if (parent.id) {
        await lastValueFrom(this.personService.update(parent.id, req));
      } else {
        await lastValueFrom(this.personService.create(req));
      }
    }

    // 4. Delete removed persons
    const removedIds = [
      ...(this.childStep.removedChildIds ?? []),
      ...(this.parentsStep.removedParentIds ?? []),
    ];
    for (const id of removedIds) {
      await lastValueFrom(this.personService.delete(id));
    }
  }
}
