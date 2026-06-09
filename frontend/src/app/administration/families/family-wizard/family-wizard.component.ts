import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { ChildService } from '../services/child.service';
import { ParentService } from '../services/parent.service';
import { Child } from '../../../shared/models/child.model';
import { Parent } from '../../../shared/models/parent.model';
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
    private childService: ChildService,
    private parentService: ParentService,
  ) {}

  cancel(): void {
    this.dialogRef.close(false);
  }

  async submit(): Promise<void> {
    this.submitting = true;

    try {
      let familyId: string;
      if (this.familyStep.isNewFamily) {
        const childData = this.childStep.getChildData();
        const family = await lastValueFrom(
          this.familyService.create({ name: childData['lastName'] as string })
        );
        familyId = family.id!;

        const familySave$ = this.familyStep.saveCustomFields(familyId);
        if (familySave$) {
          await lastValueFrom(familySave$);
        }
      } else {
        familyId = this.familyStep.selectedFamilyId!;
      }

      const childData = this.childStep.getChildData();
      const child = await lastValueFrom(
        this.childService.create({ ...childData, familyId } as Child)
      );

      const childSave$ = this.childStep.saveCustomFields(child.id!);
      if (childSave$) {
        await lastValueFrom(childSave$);
      }

      const parentsData = this.parentsStep.getParentsData();
      const parentIds: string[] = [];
      for (const parentData of parentsData) {
        const parent = await lastValueFrom(
          this.parentService.create({ ...parentData, familyId } as Parent)
        );
        parentIds.push(parent.id!);
      }

      const parentSaves = this.parentsStep.saveCustomFields(parentIds);
      if (parentSaves) {
        for (const save$ of parentSaves) {
          if (save$) {
            await lastValueFrom(save$);
          }
        }
      }

      this.dialogRef.close(true);
    } catch (err) {
      console.error('Wizard failed:', err);
      this.submitting = false;
    }
  }
}
