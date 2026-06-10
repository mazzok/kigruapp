import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { FamilyWizardComponent } from '../family-wizard/family-wizard.component';
import { PersonDTO } from '../../../shared/models/person.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { forkJoin } from 'rxjs';

interface FamilyRow {
  type: string;
  name: string;
  email: string;
  phone: string;
  street: string;
  zip: string;
  city: string;
  dateOfBirth: string;
  familyName: string;
  exitDate: string;
}

@Component({
  selector: 'app-family-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
  ],
  templateUrl: './family-list.component.html',
  styleUrl: './family-list.component.scss',
})
export class FamilyListComponent implements OnInit {
  displayedColumns: string[] = [
    'type', 'name', 'email', 'phone', 'street', 'zip', 'city',
    'dateOfBirth', 'familyName', 'exitDate',
  ];
  dataSource = new MatTableDataSource<FamilyRow>();

  @ViewChild(MatSort) sort!: MatSort;

  constructor(
    private familyService: FamilyService,
    private personService: PersonService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  ngAfterViewInit(): void {
    this.dataSource.sort = this.sort;
  }

  applyFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.dataSource.filter = value.trim().toLowerCase();
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
        this.dataSource.data = [];
        return;
      }

      const requests = families.map((f) =>
        forkJoin({
          family: [f],
          persons: this.familyService.getPersons(f.id!),
        })
      );

      forkJoin(requests).subscribe((results) => {
        const rows: FamilyRow[] = [];
        const personIds: string[] = [];
        const personFamilyMap = new Map<string, string>();

        for (const { family, persons } of results) {
          for (const person of persons) {
            if (person.id) {
              personIds.push(person.id);
              personFamilyMap.set(person.id, family.name);
            }
          }
        }

        if (personIds.length === 0) {
          this.dataSource.data = [];
          return;
        }

        const fullRequests = personIds.map((id) => this.personService.getFull(id));
        forkJoin(fullRequests).subscribe((fullPersons) => {
          for (const person of fullPersons) {
            rows.push(this.personToRow(person, personFamilyMap.get(person.id) ?? ''));
          }
          this.dataSource.data = rows;
        });
      });
    });
  }

  private personToRow(person: PersonDTO, familyName: string): FamilyRow {
    const getFieldValue = (fields: FieldInstanceDTO[], fieldName: string): string => {
      const field = fields.find((f) => f.fieldName === fieldName);
      if (!field || field.value == null) return '';
      return String(field.value);
    };

    const getAddressField = (fields: FieldInstanceDTO[], subField: string): string => {
      const field = fields.find((f) => f.fieldName === 'address');
      if (!field || !field.value || typeof field.value !== 'object') return '';
      return String((field.value as Record<string, unknown>)[subField] ?? '');
    };

    const personType = getFieldValue(person.basicProperties, 'personType');

    return {
      type: personType === 'PARENT' ? 'Elternteil' : 'Kind',
      name: `${getFieldValue(person.basicProperties, 'lastName')} ${getFieldValue(person.basicProperties, 'firstName')}`.trim(),
      email: getFieldValue(person.basicProperties, 'email'),
      phone: getFieldValue(person.basicProperties, 'phone'),
      street: getAddressField(person.basicProperties, 'street'),
      zip: getAddressField(person.basicProperties, 'zip'),
      city: getAddressField(person.basicProperties, 'city'),
      dateOfBirth: getFieldValue(person.basicProperties, 'dateOfBirth'),
      familyName,
      exitDate: getFieldValue(person.basicProperties, 'exitDate'),
    };
  }
}
