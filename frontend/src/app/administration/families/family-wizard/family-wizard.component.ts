import { Component, Inject, OnInit, Optional, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { CreatePersonRequest, PersonDTO } from '../../../shared/models/person.model';
import { Family, FamilyAddress } from '../../../shared/models/family.model';
import { lastValueFrom } from 'rxjs';

type WizardView = 'overview' | 'family' | 'children' | 'parents';

@Component({
  selector: 'app-family-wizard',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule, MatIconModule, MatDialogModule,
    FamilyStepComponent, ChildStepComponent, ParentsStepComponent,
  ],
  templateUrl: './family-wizard.component.html',
  styleUrl: './family-wizard.component.scss',
})
export class FamilyWizardComponent implements OnInit {
  @ViewChild(FamilyStepComponent) familyStep!: FamilyStepComponent;
  @ViewChild(ChildStepComponent) childStep!: ChildStepComponent;
  @ViewChild(ParentsStepComponent) parentsStep!: ParentsStepComponent;

  view: WizardView = 'overview';
  submitting = false;
  loading = false;
  anyChanges = false;

  resolvedFamilyId?: string;
  familyName = '';
  familyAddress: FamilyAddress | null = null;

  editFamily?: Family;
  existingChildren: { id: string; dto: PersonDTO }[] = [];
  existingParents: { id: string; dto: PersonDTO }[] = [];

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: { familyId?: string } | null,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {
    if (this.data?.familyId) {
      this.resolvedFamilyId = this.data.familyId;
    }
  }

  ngOnInit(): void {
    if (this.data?.familyId) {
      this.loadEditData();
    }
  }

  private async loadEditData(): Promise<void> {
    this.loading = true;
    try {
      const familyId = this.data!.familyId!;
      const family = await lastValueFrom(this.familyService.get(familyId));
      this.editFamily = family;
      this.familyName = family.name;
      this.familyAddress = family.address ?? null;

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

  openSection(target: 'family' | 'children' | 'parents'): void {
    this.view = target;
    if (target === 'children' || target === 'parents') {
      const name = this.familyName;
      const address = this.familyAddress;
      if (name || address) {
        setTimeout(() => {
          if (target === 'children') this.childStep?.prefill(name, address);
          else this.parentsStep?.prefill(name, address);
        }, 0);
      }
    }
  }

  backToOverview(): void {
    this.view = 'overview';
  }

  cancel(): void {
    this.dialogRef.close(this.anyChanges);
  }

  async saveFamily(): Promise<void> {
    this.submitting = true;
    try {
      const request = {
        name: this.familyStep.newFamilyName,
        address: this.familyStep.address ?? undefined,
      };
      const family = this.resolvedFamilyId
        ? await lastValueFrom(this.familyService.update(this.resolvedFamilyId, request))
        : await lastValueFrom(this.familyService.create(request));

      this.resolvedFamilyId = family.id;
      this.familyName = family.name;
      this.familyAddress = family.address ?? null;
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Familie fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  async saveChildren(): Promise<void> {
    this.submitting = true;
    try {
      const familyId = this.resolvedFamilyId!;
      for (const child of this.childStep.getChildrenData()) {
        const req: CreatePersonRequest = { familyId, basicProperties: child.basicProperties };
        if (child.id) {
          await lastValueFrom(this.personService.update(child.id, req));
        } else {
          await lastValueFrom(this.personService.create(req));
        }
      }
      for (const id of this.childStep.removedChildIds ?? []) {
        await lastValueFrom(this.personService.delete(id));
      }
      this.existingChildren = await this.loadPersonsByType(familyId, 'CHILD');
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Kinder fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  async saveParents(): Promise<void> {
    this.submitting = true;
    try {
      const familyId = this.resolvedFamilyId!;
      for (const parent of this.parentsStep.getParentsData()) {
        const req: CreatePersonRequest = { familyId, basicProperties: parent.basicProperties };
        if (parent.id) {
          await lastValueFrom(this.personService.update(parent.id, req));
        } else {
          await lastValueFrom(this.personService.create(req));
        }
      }
      for (const id of this.parentsStep.removedParentIds ?? []) {
        await lastValueFrom(this.personService.delete(id));
      }
      this.existingParents = await this.loadPersonsByType(familyId, 'PARENT');
      this.anyChanges = true;
      this.view = 'overview';
    } catch (err) {
      console.error('Speichern der Eltern fehlgeschlagen:', err);
    } finally {
      this.submitting = false;
    }
  }

  private async loadPersonsByType(
    familyId: string,
    type: 'CHILD' | 'PARENT',
  ): Promise<{ id: string; dto: PersonDTO }[]> {
    const persons = await lastValueFrom(this.personService.list(familyId));
    const dtos = await Promise.all(
      persons.filter((p) => !!p.id).map((p) => lastValueFrom(this.personService.getFull(p.id!)))
    );
    return dtos
      .filter((dto) => dto.basicProperties?.find((f) => f.fieldName === 'personType')?.value === type)
      .map((dto) => ({ id: dto.id!, dto }));
  }
}
