import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { CalendarEvent, CalendarModule } from 'angular-calendar';
import { Subject } from 'rxjs';
import { OrganisationService } from '../shared/services/organisation.service';
import { CookingDutyService } from './services/cooking-duty.service';
import { PersonService } from '../shared/services/person.service';
import { FieldInstanceService } from '../shared/services/field-instance.service';
import { CurrentUserService } from '../core/services/current-user.service';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO } from '../shared/models/organisation.model';
import { PersonDTO, SectionInput } from '../shared/models/person.model';
import {
  CookingDutyDialogComponent,
  CookingDutyDialogData,
  CookingDutyDialogResult,
} from './cooking-duty-dialog.component';

@Component({
  selector: 'app-cooking',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule, MatIconModule, MatCheckboxModule, MatDialogModule,
    CalendarModule,
  ],
  templateUrl: './cooking.component.html',
  styleUrl: './cooking.component.scss',
})
export class CookingComponent implements OnInit {
  viewDate = new Date();
  refresh = new Subject<void>();
  events: CalendarEvent[] = [];
  excludeDays: number[] = [0, 6]; // Exclude Saturday and Sunday

  groups: FieldDefinition[] = [];
  activeGroupIds: Set<string> = new Set();
  foodProperties: FieldDefinition[] = [];
  duties: CookingDutyDTO[] = [];

  // Current user family data
  familyParents: PersonDTO[] = [];
  currentFamilyId = '';
  currentPersonId = '';

  // Cooking duty FieldDefinition ID
  private cookingDutyDefId = '';

  constructor(
    private orgService: OrganisationService,
    private cookingDutyService: CookingDutyService,
    private personService: PersonService,
    private fieldInstanceService: FieldInstanceService,
    private currentUserService: CurrentUserService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    const person = this.currentUserService.currentPerson;
    if (person) {
      this.currentFamilyId = person.familyId;
      this.currentPersonId = person.id;
    } else {
      this.currentUserService.currentPerson$.subscribe(p => {
        if (p) {
          this.currentFamilyId = p.familyId;
          this.currentPersonId = p.id;
        }
      });
    }
    this.loadOrganisationData();
  }

  private loadOrganisationData(): void {
    this.orgService.getByTag('groups').subscribe((org) => {
      this.groups = org.definitions;
      // Initially all groups active (later: filter by child group membership)
      this.groups.forEach((g) => this.activeGroupIds.add(g.id!));
      this.loadDuties();
    });

    this.orgService.getByTag('duty-settings').subscribe((org) => {
      const cooking = org.entries.find((e) => e.name === 'cooking');
      this.foodProperties = cooking?.definitions ?? [];
    });

    const familyId = this.currentUserService.currentFamilyId;
    if (familyId) {
      this.personService.list(familyId).subscribe((persons) => {
        this.familyParents = persons.filter(p => !!p.id) as unknown as PersonDTO[];
      });
    }
  }

  loadDuties(): void {
    const year = this.viewDate.getFullYear();
    const month = String(this.viewDate.getMonth() + 1).padStart(2, '0');
    const monthStr = `${year}-${month}`;
    const activeGroups = Array.from(this.activeGroupIds);
    this.cookingDutyService.getByMonth(monthStr, activeGroups).subscribe((duties) => {
      this.duties = duties;
      this.buildCalendarEvents();
    });
  }

  private buildCalendarEvents(): void {
    this.events = this.duties.map((duty) => {
      // Find the first group's color
      const firstGroupId = duty.groups[0];
      const group = this.groups.find((g) => g.id === firstGroupId);
      const color = (group?.properties?.['color'] as string) ?? '#999';

      // Build title with food property icons (text-based since calendar uses text)
      const foodLabels = this.getFoodLabels(duty);
      const labelStr = foodLabels.length > 0 ? ' (' + foodLabels.join(', ') + ')' : '';

      return {
        start: new Date(duty.date + 'T00:00:00'),
        title: `${duty.personName} - ${duty.description || ''}${labelStr}`,
        color: { primary: color, secondary: color + '33' },
        meta: duty,
      } as CalendarEvent;
    });
    this.refresh.next();
  }

  getFoodLabels(duty: CookingDutyDTO): string[] {
    const labels: string[] = [];
    for (const fp of this.foodProperties) {
      if (duty.foodProperties[fp.id!]) {
        labels.push(fp.label?.['de'] ?? '');
      }
    }
    return labels;
  }

  toggleGroup(groupId: string): void {
    if (this.activeGroupIds.has(groupId)) {
      this.activeGroupIds.delete(groupId);
    } else {
      this.activeGroupIds.add(groupId);
    }
    this.loadDuties();
  }

  isGroupActive(groupId: string): boolean {
    return this.activeGroupIds.has(groupId);
  }

  previousMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() - 1, 1);
    this.loadDuties();
  }

  nextMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() + 1, 1);
    this.loadDuties();
  }

  today(): void {
    this.viewDate = new Date();
    this.loadDuties();
  }

  openCreateDialog(): void {
    this.openDialog();
  }

  onEventClicked(event: CalendarEvent): void {
    const duty = event.meta as CookingDutyDTO;
    const canEdit = duty.familyId === this.currentFamilyId || this.currentUserService.isAdmin;
    this.openDialog(duty, canEdit);
  }

  private openDialog(existingDuty?: CookingDutyDTO, canEdit = true): void {
    const data: CookingDutyDialogData = {
      groups: this.groups,
      foodProperties: this.foodProperties,
      familyParents: this.familyParents,
      currentUserId: this.currentPersonId,
      existingDuty,
      canEdit,
    };

    const dialogRef = this.dialog.open(CookingDutyDialogComponent, {
      width: '500px',
      data,
    });

    dialogRef.afterClosed().subscribe((result: CookingDutyDialogResult | undefined) => {
      if (!result) return;

      if (result.action === 'delete' && existingDuty) {
        this.deleteCookingDuty(existingDuty);
      } else if (result.action === 'save') {
        if (existingDuty) {
          this.updateCookingDuty(existingDuty, result);
        } else {
          this.createCookingDuty(result);
        }
      }
    });
  }

  private createCookingDuty(result: CookingDutyDialogResult): void {
    const value = {
      date: result.date,
      groups: result.groups,
      description: result.description,
      foodProperties: result.foodProperties,
    };

    this.fieldInstanceService.create(this.cookingDutyDefId, value).subscribe(() => {
      this.loadDuties();
    });
  }

  private updateCookingDuty(existing: CookingDutyDTO, result: CookingDutyDialogResult): void {
    const value = {
      date: result.date,
      groups: result.groups,
      description: result.description,
      foodProperties: result.foodProperties,
    };

    this.fieldInstanceService.update(existing.id, {
      definitionId: this.cookingDutyDefId,
      value,
    }).subscribe(() => {
      this.loadDuties();
    });
  }

  private deleteCookingDuty(duty: CookingDutyDTO): void {
    this.fieldInstanceService.delete(duty.id).subscribe(() => {
      this.loadDuties();
    });
  }
}
