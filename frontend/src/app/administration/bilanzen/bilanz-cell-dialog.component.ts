import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { BilanzCell, BilanzCellLine } from '../../shared/models/bilanz.model';

export interface BilanzCellDialogData {
  childName: string;
  year: number;
  month: number;
  cell: BilanzCell;
}

export interface BilanzCellDialogResult {
  changed: { personId: string; definitionId: string; amount: number }[];
}

@Component({
  selector: 'app-bilanz-cell-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.childName }} — {{ data.month }}/{{ data.year }}</h2>
    <mat-dialog-content class="dialog-content">
      @if (data.cell.lines.length === 0) {
        <p>keine Posten für diesen Monat</p>
      } @else {
        <form [formGroup]="form" class="lines-grid">
          @for (line of data.cell.lines; track key(line)) {
            <div class="line-row">
              <span class="line-label">{{ line.label }}</span>
              <mat-form-field appearance="outline" class="line-field">
                <input matInput type="number" step="0.01" [formControlName]="key(line)">
                <span matTextSuffix>{{ line.currencySymbol }}</span>
              </mat-form-field>
            </div>
          }
        </form>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-raised-button color="primary" (click)="save()">OK</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .dialog-content {
      max-height: 60vh;
      overflow-y: auto;
    }
    .lines-grid {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .line-row {
      display: grid;
      grid-template-columns: 1fr 140px;
      align-items: center;
      column-gap: 16px;
    }
    .line-label {
      font-size: 14px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .line-field {
      width: 100%;
    }
  `],
})
export class BilanzCellDialogComponent {
  form: FormGroup;

  constructor(
    private dialogRef: MatDialogRef<BilanzCellDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: BilanzCellDialogData,
  ) {
    this.form = new FormGroup({});
    for (const line of data.cell.lines) {
      this.form.addControl(this.key(line), new FormControl(line.effectiveAmount));
    }
  }

  key(line: BilanzCellLine): string {
    return `${line.personId}__${line.definitionId}`;
  }

  save(): void {
    const changed: BilanzCellDialogResult['changed'] = [];
    for (const line of this.data.cell.lines) {
      const raw = this.form.get(this.key(line))?.value;
      const amount = raw === '' || raw == null ? null : Number(raw);
      if (amount != null && amount !== line.effectiveAmount) {
        changed.push({ personId: line.personId, definitionId: line.definitionId, amount });
      }
    }
    this.dialogRef.close({ changed } as BilanzCellDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
