import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { StepperSelectionEvent } from '@angular/cdk/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { CreatePersonRequest } from '../../../shared/models/person.model';
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
export class FamilyWizardComponent {
  @ViewChild('stepper') stepper!: MatStepper;
  @ViewChild(FamilyStepComponent) familyStep!: FamilyStepComponent;
  @ViewChild(ChildStepComponent) childStep!: ChildStepComponent;
  @ViewChild(ParentsStepComponent) parentsStep!: ParentsStepComponent;

  submitting = false;

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {}

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
        organisationalUnit: [],
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
          organisationalUnit: [],
        };
        await lastValueFrom(this.personService.create(parentRequest));
      }

      this.dialogRef.close(true);
    } catch (err) {
      console.error('Wizard failed:', err);
      this.submitting = false;
    }
  }
}
