import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { HoursSummaryService } from '../shared/services/hours-summary.service';
import { NotificationService } from '../shared/services/notification.service';
import { CurrentUserService } from '../core/services/current-user.service';
import {
  HourEntry, OurHours, OurHoursEntry, RoleOption, SaveHourEntryRequest,
} from '../shared/models/hour-entry.model';
import { HoursBreakdownComponent } from './hours-breakdown/hours-breakdown.component';
import { HoursEntriesComponent } from './hours-entries/hours-entries.component';
import {
  HoursEntryDialogComponent, HoursEntryDialogData,
} from './hours-entry-dialog/hours-entry-dialog.component';

@Component({
  selector: 'app-stunden',
  standalone: true,
  imports: [CommonModule, HoursBreakdownComponent, HoursEntriesComponent],
  templateUrl: './stunden.component.html',
  styleUrl: './stunden.component.scss',
})
export class StundenComponent implements OnInit, OnDestroy {
  our: OurHours | null = null;
  options: RoleOption[] = [];
  /** Eigene Einträge als Formular-Modell, für Bearbeiten und Löschen. */
  private mine: HourEntry[] = [];
  private summarySub?: Subscription;

  constructor(
    private hourService: HourEntryService,
    private notify: NotificationService,
    private currentUser: CurrentUserService,
    private hoursSummary: HoursSummaryService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadMine();
    this.summarySub = this.hoursSummary.summary$.subscribe((o) => (this.our = o));
    if (!this.hoursSummary.current) {
      this.hoursSummary.reload();
    }
    this.hourService.roleOptions().subscribe({
      next: (opts) => (this.options = opts),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  ngOnDestroy(): void {
    this.summarySub?.unsubscribe();
  }

  get ownPersonId(): string | null {
    return this.currentUser.currentPerson?.id ?? null;
  }

  get entries(): OurHoursEntry[] {
    return this.our?.entries ?? [];
  }

  newEntry(): void {
    this.openDialog(null);
  }

  editEntry(entry: OurHoursEntry): void {
    const own = this.mine.find((e) => e.id === entry.id);
    if (own) {
      this.openDialog(own);
    }
  }

  deleteEntry(entry: OurHoursEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        this.loadMine();
        this.hoursSummary.reload();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  private openDialog(entry: HourEntry | null): void {
    const data: HoursEntryDialogData = { entry, options: this.options };
    this.dialog.open(HoursEntryDialogComponent, { data, width: '420px' })
      .afterClosed().subscribe((request?: SaveHourEntryRequest) => {
        if (!request) {
          return;
        }
        const save$ = entry
          ? this.hourService.update(entry.id, request)
          : this.hourService.create(request);
        save$.subscribe({
          next: () => {
            this.notify.success(entry ? 'Eintrag aktualisiert' : 'Eintrag gespeichert');
            this.loadMine();
            this.hoursSummary.reload();
          },
          error: (err) => this.notify.error(this.notify.extractError(err)),
        });
      });
  }

  private loadMine(): void {
    this.hourService.listMine().subscribe({
      next: (entries) => (this.mine = entries),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
