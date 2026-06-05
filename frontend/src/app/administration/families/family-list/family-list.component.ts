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
import { FamilyWizardComponent } from '../family-wizard/family-wizard.component';
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
          children: this.familyService.getChildren(f.id!),
          parents: this.familyService.getParents(f.id!),
        })
      );

      forkJoin(requests).subscribe((results) => {
        const rows: FamilyRow[] = [];
        for (const { family, children, parents } of results) {
          for (const child of children) {
            rows.push({
              type: 'Kind',
              name: `${child.lastName} ${child.firstName}`,
              email: '',
              phone: '',
              street: '',
              zip: '',
              city: '',
              dateOfBirth: child.dateOfBirth,
              familyName: family.name,
              exitDate: child.exitDate ?? '',
            });
          }
          for (const parent of parents) {
            rows.push({
              type: 'Elternteil',
              name: `${parent.lastName} ${parent.firstName}`,
              email: parent.email ?? '',
              phone: parent.phone ?? '',
              street: parent.address?.street ?? '',
              zip: parent.address?.zip ?? '',
              city: parent.address?.city ?? '',
              dateOfBirth: '',
              familyName: family.name,
              exitDate: '',
            });
          }
        }
        this.dataSource.data = rows;
      });
    });
  }
}
