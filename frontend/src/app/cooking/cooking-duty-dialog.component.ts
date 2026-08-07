import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO } from '../shared/models/organisation.model';
import { ParentSummaryDTO } from '../shared/models/person.model';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../shared/models/closure.model';
import { ClosureCalendarComponent } from '../shared/components/closure-calendar/closure-calendar.component';

export interface CookingDutyDialogData {
  groups: FieldDefinition[];
  foodProperties: FieldDefinition[];
  familyParents: ParentSummaryDTO[];
  currentUserId: string;
  existingDuty?: CookingDutyDTO;
  canEdit: boolean;
  reminderAvailable: boolean;
  closurePeriods?: ClosurePeriod[];
  closureDefinitions?: ClosureDefinition[];
  holidays?: Holiday[];
  calendarFrom?: string;
  calendarTo?: string;
}

export interface CookingDutyDialogResult {
  action: 'save' | 'delete';
  date: string;
  groups: string[];
  personId: string;
  description: string;
  foodProperties: Record<string, boolean>;
  reminderEnabled: boolean;
  reminderDaysBefore: number | null;
}

@Component({
  selector: 'app-cooking-duty-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatSelectModule, MatButtonModule, MatIconModule,
    ClosureCalendarComponent,
  ],
  templateUrl: './cooking-duty-dialog.component.html',
  styleUrl: './cooking-duty-dialog.component.scss',
})
export class CookingDutyDialogComponent implements OnInit {
  form!: FormGroup;
  isEdit: boolean;
  canEdit: boolean;

  closurePeriods: ClosurePeriod[];
  closureDefinitions: ClosureDefinition[];
  holidays: Holiday[];
  calendarFrom: string;
  calendarTo: string;
  /** Vorbelegte Kalenderauswahl beim Bearbeiten eines bestehenden Kochdienstes. */
  initialDateSelection: string[] = [];

  /** Klartextdatum der Erinnerung, null solange Datum oder Vorlaufzeit fehlen. */
  reminderDate: string | null = null;
  reminderInPast = false;

  constructor(
    private dialogRef: MatDialogRef<CookingDutyDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CookingDutyDialogData,
  ) {
    this.isEdit = !!data.existingDuty;
    this.canEdit = data.canEdit;
    this.closurePeriods = data.closurePeriods ?? [];
    this.closureDefinitions = data.closureDefinitions ?? [];
    this.holidays = data.holidays ?? [];
    this.calendarFrom = data.calendarFrom ?? '';
    this.calendarTo = data.calendarTo ?? '';
  }

  ngOnInit(): void {
    const duty = this.data.existingDuty;
    this.initialDateSelection = duty ? [duty.date] : [];

    this.form = new FormGroup({
      date: new FormControl(duty ? new Date(duty.date) : null, Validators.required),
      person: new FormControl(
        duty ? duty.personId : this.data.currentUserId,
        Validators.required,
      ),
      description: new FormControl(duty?.description ?? ''),
    });

    // Add group checkboxes
    for (const group of this.data.groups) {
      const isChecked = duty ? duty.groups.includes(group.id!) : false;
      this.form.addControl('group_' + group.id, new FormControl(isChecked));
    }

    // Add food property checkboxes
    for (const fp of this.data.foodProperties) {
      const isChecked = duty ? duty.foodProperties[fp.id!] === true : false;
      this.form.addControl('food_' + fp.id, new FormControl(isChecked));
    }

    this.form.addControl('reminderEnabled', new FormControl(duty?.reminderEnabled ?? false));
    this.form.addControl('reminderDaysBefore', new FormControl(
      duty?.reminderDaysBefore ?? 3,
      duty?.reminderEnabled ? [Validators.required, Validators.min(1), Validators.max(14)] : [],
    ));

    // Die Vorlaufzeit ist nur relevant, solange die Erinnerung aktiv ist —
    // sonst bleibt das (dann unsichtbare) Feld ein stummer Blocker fuer save().
    this.form.get('reminderEnabled')?.valueChanges.subscribe((enabled: boolean) => {
      const daysControl = this.form.get('reminderDaysBefore');
      daysControl?.setValidators(
        enabled ? [Validators.required, Validators.min(1), Validators.max(14)] : [],
      );
      daysControl?.updateValueAndValidity({ emitEvent: false });
    });

    this.updateReminderPreview();
    this.form.valueChanges.subscribe(() => this.updateReminderPreview());

    if (!this.canEdit) {
      this.form.disable();
    }
  }

  /**
   * Erinnerungstag = Dienstdatum minus Vorlaufzeit. Liegt er vor heute, wird
   * nichts versendet — der Dialog weist darauf hin, blockiert das Speichern
   * aber nicht.
   */
  private updateReminderPreview(): void {
    const date: Date | null = this.form.get('date')?.value ?? null;
    const days: number | null = this.form.get('reminderDaysBefore')?.value ?? null;
    const enabled: boolean = this.form.get('reminderEnabled')?.value ?? false;

    if (!enabled || !date || !days) {
      this.reminderDate = null;
      this.reminderInPast = false;
      return;
    }

    const reminder = new Date(date);
    reminder.setDate(reminder.getDate() - days);
    reminder.setHours(0, 0, 0, 0);

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    this.reminderInPast = reminder.getTime() < today.getTime();
    this.reminderDate = reminder.toLocaleDateString('de-AT', {
      weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric',
    });
  }

  getParentName(parent: ParentSummaryDTO): string {
    return `${parent.lastName ?? ''} ${parent.firstName ?? ''}`.trim();
  }

  onDateSelected(dates: string[]): void {
    const iso = dates[0] ?? null;
    this.form.get('date')?.setValue(iso ? new Date(`${iso}T00:00:00`) : null);
  }

  hasSelectedGroups(): boolean {
    return this.data.groups.some((g) => this.form.get('group_' + g.id)?.value);
  }

  save(): void {
    if (!this.form.valid || !this.hasSelectedGroups()) return;

    const dateValue: Date = this.form.value.date;
    const dateStr = dateValue.toISOString().split('T')[0];

    const selectedGroups = this.data.groups
      .filter((g) => this.form.get('group_' + g.id)?.value)
      .map((g) => g.id!);

    const foodProps: Record<string, boolean> = {};
    for (const fp of this.data.foodProperties) {
      if (this.form.get('food_' + fp.id)?.value) {
        foodProps[fp.id!] = true;
      }
    }

    const reminderEnabled: boolean = this.form.get('reminderEnabled')?.value ?? false;
    const reminderDaysBefore: number | null = reminderEnabled
      ? (this.form.get('reminderDaysBefore')?.value ?? null)
      : null;

    this.dialogRef.close({
      action: 'save',
      date: dateStr,
      groups: selectedGroups,
      personId: this.form.value.person,
      description: this.form.value.description,
      foodProperties: foodProps,
      reminderEnabled,
      reminderDaysBefore,
    } as CookingDutyDialogResult);
  }

  deleteDuty(): void {
    this.dialogRef.close({ action: 'delete' } as CookingDutyDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
