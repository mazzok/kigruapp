import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { forkJoin, of } from 'rxjs';
import { switchMap, catchError } from 'rxjs/operators';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

interface ParentRow {
  person: PersonDTO;
  name: string;
}

@Component({
  selector: 'app-elterneinteilung',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTableModule, MatChipsModule,
    MatFormFieldModule, MatSelectModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './elterneinteilung.component.html',
  styleUrl: './elterneinteilung.component.scss',
})
export class ElterneinteilungComponent implements OnInit {
  teams: FieldInstanceDTO[] = [];
  parentTeamsDefinitionId: string | null = null;
  allParents: ParentRow[] = [];
  displayedParents: ParentRow[] = [];
  filterTeamId: string | null = null;
  loading = false;
  displayedColumns = ['name', 'teams'];

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.orgService.getByTag('parent-teams').pipe(
      catchError(() => of({ id: '', tag: 'parent-teams', definitions: [], entries: [] } as any)),
      switchMap((org) => {
        const templateDef = org.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team' && !d.outdatedAt
        );
        if (!templateDef) {
          this.teams = [];
          this.parentTeamsDefinitionId = null;
          return of([] as FieldInstanceDTO[]);
        }
        this.parentTeamsDefinitionId = templateDef.id!;
        return this.fieldInstanceService.listByDefinitionId(templateDef.id!);
      }),
      switchMap((teams) => {
        this.teams = teams;
        return this.personService.list();
      }),
      switchMap((persons) => {
        if (persons.length === 0) return of([] as PersonDTO[]);
        return forkJoin(persons.map(p => this.personService.getFull(p.id!)));
      }),
    ).subscribe({
      next: (fullPersons) => {
        this.allParents = fullPersons
          .filter(p => !this.isChild(p))
          .map(p => ({ person: p, name: this.getPersonName(p) }));
        this.applyFilter();
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  private isChild(person: PersonDTO): boolean {
    return person.basicProperties.some(
      f => f.fieldName === 'personType' && f.value === 'CHILD'
    );
  }

  private getPersonName(person: PersonDTO): string {
    const firstName = person.basicProperties.find(f => f.fieldName === 'firstName')?.value as string ?? '';
    const lastName = person.basicProperties.find(f => f.fieldName === 'lastName')?.value as string ?? '';
    return `${firstName} ${lastName}`.trim() || person.id!;
  }

  isAssigned(person: PersonDTO, team: FieldInstanceDTO): boolean {
    return (person.assignedDuty ?? []).some(d => d.id === team.id);
  }

  toggleTeam(row: ParentRow, team: FieldInstanceDTO): void {
    if (!this.parentTeamsDefinitionId) return;
    this.personService.assignTeam(row.person.id!, this.parentTeamsDefinitionId, team.id!).subscribe(() => {
      if (this.isAssigned(row.person, team)) {
        row.person.assignedDuty = (row.person.assignedDuty ?? []).filter(d => d.id !== team.id);
      } else {
        row.person.assignedDuty = [...(row.person.assignedDuty ?? []), team];
      }
      this.applyFilter();
    });
  }

  onFilterChange(): void {
    this.applyFilter();
  }

  private applyFilter(): void {
    if (!this.filterTeamId) {
      this.displayedParents = [...this.allParents];
    } else {
      this.displayedParents = this.allParents.filter(row =>
        (row.person.assignedDuty ?? []).some(d => d.id === this.filterTeamId)
      );
    }
  }

  getTeamLabel(team: FieldInstanceDTO): string {
    return (team.value as { label: string })?.label ?? team.id ?? '';
  }
}
