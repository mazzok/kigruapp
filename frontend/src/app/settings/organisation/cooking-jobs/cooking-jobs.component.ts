import { Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { CookingReminderJobsComponent } from '../cooking-reminder-jobs/cooking-reminder-jobs.component';
import { CookingOverviewJobsComponent } from '../cooking-overview-jobs/cooking-overview-jobs.component';

/** Duenner Wrapper: hostet die Kochdienst-Erinnerungen und -Uebersichtsjobs als zwei Reiter. */
@Component({
  selector: 'app-cooking-jobs',
  standalone: true,
  imports: [MatTabsModule, CookingReminderJobsComponent, CookingOverviewJobsComponent],
  templateUrl: './cooking-jobs.component.html',
})
export class CookingJobsComponent {}
