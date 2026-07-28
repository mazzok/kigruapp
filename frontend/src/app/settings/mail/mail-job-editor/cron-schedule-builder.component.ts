import { Component, forwardRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatExpansionModule } from '@angular/material/expansion';
import {
  buildQuartzCron,
  describeSchedule,
  parseQuartzCron,
  CronFrequency,
  ScheduleModel,
  Weekday,
  WEEKDAYS,
  DEFAULT_SCHEDULE,
} from './cron-schedule.util';

/**
 * Visual schedule builder. The user sets values (frequency, time, weekday/day)
 * and this control emits the corresponding Quartz cron string via ControlValueAccessor,
 * so the raw expression never has to be typed. A collapsible section reveals the
 * generated expression for transparency.
 */
@Component({
  selector: 'app-cron-schedule-builder',
  standalone: true,
  imports: [
    CommonModule,
    MatFormFieldModule, MatSelectModule, MatButtonToggleModule, MatExpansionModule,
  ],
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => CronScheduleBuilderComponent), multi: true },
  ],
  templateUrl: './cron-schedule-builder.component.html',
  styleUrl: './cron-schedule-builder.component.scss',
})
export class CronScheduleBuilderComponent implements ControlValueAccessor {
  readonly frequencies: { value: CronFrequency; label: string }[] = [
    { value: 'MINUTELY', label: 'Jede Minute' },
    { value: 'HOURLY', label: 'Stündlich' },
    { value: 'DAILY', label: 'Täglich' },
    { value: 'WEEKLY', label: 'Wöchentlich' },
    { value: 'MONTHLY', label: 'Monatlich' },
  ];
  readonly weekdays = WEEKDAYS;
  readonly minutes = Array.from({ length: 60 }, (_, i) => i);
  readonly hours = Array.from({ length: 24 }, (_, i) => i);
  readonly daysOfMonth = Array.from({ length: 31 }, (_, i) => i + 1);

  model: ScheduleModel = { ...DEFAULT_SCHEDULE, weekdays: [...DEFAULT_SCHEDULE.weekdays] };
  disabled = false;

  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  get cron(): string {
    return buildQuartzCron(this.model);
  }

  get summary(): string {
    return describeSchedule(this.model);
  }

  get showMinute(): boolean {
    return this.model.frequency !== 'MINUTELY';
  }

  get showHour(): boolean {
    return this.model.frequency === 'DAILY' || this.model.frequency === 'WEEKLY' || this.model.frequency === 'MONTHLY';
  }

  get showWeekdays(): boolean {
    return this.model.frequency === 'WEEKLY';
  }

  get showDayOfMonth(): boolean {
    return this.model.frequency === 'MONTHLY';
  }

  setFrequency(frequency: CronFrequency): void {
    this.model = { ...this.model, frequency };
    this.emit();
  }

  setMinute(minute: number): void {
    this.model = { ...this.model, minute };
    this.emit();
  }

  setHour(hour: number): void {
    this.model = { ...this.model, hour };
    this.emit();
  }

  setDayOfMonth(dayOfMonth: number): void {
    this.model = { ...this.model, dayOfMonth };
    this.emit();
  }

  setWeekdays(weekdays: Weekday[]): void {
    this.model = { ...this.model, weekdays: weekdays.length ? weekdays : ['MON'] };
    this.emit();
  }

  private emit(): void {
    this.onChange(this.cron);
    this.onTouched();
  }

  // ControlValueAccessor
  writeValue(value: string | null): void {
    const parsed = parseQuartzCron(value);
    this.model = parsed ?? { ...DEFAULT_SCHEDULE, weekdays: [...DEFAULT_SCHEDULE.weekdays] };
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }
}
