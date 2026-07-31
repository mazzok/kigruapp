import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-closure-revise-dialog',
  standalone: true,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Achtung</h2>
    <mat-dialog-content>
      <p>
        Die Definition wurde geändert. Bereits verknüpfte Daten werden
        <strong>nicht</strong> geändert.
      </p>
      <p>
        Mit „Fortfahren" wird eine Kopie mit den neuen Werten angelegt; die
        bisherige Definition bleibt für vorhandene Zeiträume erhalten und
        verschwindet aus der Auswahlliste.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-raised-button color="primary" (click)="confirm()">Fortfahren</button>
    </mat-dialog-actions>
  `,
})
export class ClosureReviseDialogComponent {
  constructor(private dialogRef: MatDialogRef<ClosureReviseDialogComponent, 'revise' | undefined>) {}

  confirm(): void {
    this.dialogRef.close('revise');
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
