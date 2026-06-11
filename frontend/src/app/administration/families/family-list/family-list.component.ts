import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { FamilyWizardComponent } from '../family-wizard/family-wizard.component';
import { Family } from '../../../shared/models/family.model';
import { PersonDTO } from '../../../shared/models/person.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { forkJoin } from 'rxjs';

interface PersonRow {
  type: string;
  name: string;
  email: string;
  phone: string;
  dateOfBirth: string;
  street: string;
  zip: string;
  city: string;
}

interface FamilyNode {
  family: Family;
  persons: PersonRow[];
  childCount: number;
  parentCount: number;
}

@Component({
  selector: 'app-family-list',
  standalone: true,
  imports: [
    CommonModule,
    MatExpansionModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatChipsModule,
  ],
  templateUrl: './family-list.component.html',
  styleUrl: './family-list.component.scss',
})
export class FamilyListComponent implements OnInit {
  familyNodes: FamilyNode[] = [];
  filteredNodes: FamilyNode[] = [];
  personColumns = ['type', 'name', 'email', 'phone', 'dateOfBirth', 'street', 'zip', 'city'];

  constructor(
    private familyService: FamilyService,
    private personService: PersonService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  applyFilter(event: Event): void {
    const query = (event.target as HTMLInputElement).value.trim().toLowerCase();
    if (!query) {
      this.filteredNodes = this.familyNodes;
      return;
    }
    this.filteredNodes = this.familyNodes.filter((node) => {
      if (node.family.name.toLowerCase().includes(query)) return true;
      return node.persons.some((p) =>
        Object.values(p).some((v) => v.toLowerCase().includes(query))
      );
    });
  }

  openWizard(): void {
    const dialogRef = this.dialog.open(FamilyWizardComponent, {
      width: '700px',
      maxWidth: '95vw',
      disableClose: true,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.loadData();
      }
    });
  }

  private loadData(): void {
    this.familyService.list().subscribe((families) => {
      if (families.length === 0) {
        this.familyNodes = [];
        this.filteredNodes = [];
        return;
      }

      const requests = families.map((family) =>
        forkJoin({
          family: [family],
          persons: this.familyService.getPersons(family.id!),
        })
      );

      forkJoin(requests).subscribe((results) => {
        const allPersonIds: { familyIdx: number; personId: string }[] = [];
        const familyData: { family: Family; personIds: string[] }[] = [];

        for (let i = 0; i < results.length; i++) {
          const { family, persons } = results[i];
          const ids = persons.filter((p) => p.id).map((p) => p.id!);
          familyData.push({ family, personIds: ids });
          ids.forEach((id) => allPersonIds.push({ familyIdx: i, personId: id }));
        }

        if (allPersonIds.length === 0) {
          this.familyNodes = familyData.map((fd) => ({
            family: fd.family,
            persons: [],
            childCount: 0,
            parentCount: 0,
          }));
          this.filteredNodes = this.familyNodes;
          return;
        }

        const fullRequests = allPersonIds.map((e) => this.personService.getFull(e.personId));
        forkJoin(fullRequests).subscribe((fullPersons) => {
          const personsByFamily = new Map<number, PersonDTO[]>();
          fullPersons.forEach((person, idx) => {
            const fi = allPersonIds[idx].familyIdx;
            if (!personsByFamily.has(fi)) personsByFamily.set(fi, []);
            personsByFamily.get(fi)!.push(person);
          });

          this.familyNodes = familyData.map((fd, i) => {
            const persons = (personsByFamily.get(i) ?? []).map((p) => this.personToRow(p));
            return {
              family: fd.family,
              persons,
              childCount: persons.filter((p) => p.type === 'Kind').length,
              parentCount: persons.filter((p) => p.type === 'Elternteil').length,
            };
          });
          this.filteredNodes = this.familyNodes;
        });
      });
    });
  }

  private personToRow(person: PersonDTO): PersonRow {
    const getField = (fields: FieldInstanceDTO[], fieldName: string): string => {
      const field = fields.find((f) => f.fieldName === fieldName);
      if (!field || field.value == null) return '';
      return String(field.value);
    };

    const getAddressField = (fields: FieldInstanceDTO[], subField: string): string => {
      const field = fields.find((f) => f.fieldName === 'address');
      if (!field || !field.value || typeof field.value !== 'object') return '';
      return String((field.value as Record<string, unknown>)[subField] ?? '');
    };

    const personType = getField(person.basicProperties, 'personType');
    const firstName = getField(person.basicProperties, 'firstName');
    const lastName = getField(person.basicProperties, 'lastName');

    return {
      type: personType === 'PARENT' ? 'Elternteil' : 'Kind',
      name: `${lastName} ${firstName}`.trim(),
      email: getField(person.basicProperties, 'email'),
      phone: getField(person.basicProperties, 'phone'),
      dateOfBirth: getField(person.basicProperties, 'dateOfBirth'),
      street: getAddressField(person.basicProperties, 'street'),
      zip: getAddressField(person.basicProperties, 'zip'),
      city: getAddressField(person.basicProperties, 'city'),
    };
  }
}
