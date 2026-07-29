import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { SemesterService } from '../../shared/services/semester.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { KostenValueService } from '../../shared/services/kosten-value.service';
import { KostenDiscountService } from '../../shared/services/kosten-discount.service';
import { AliquotConfigService } from '../../shared/services/aliquot-config.service';
import { KostenDefinitionService } from '../../shared/services/kosten-definition.service';
import { Semester } from '../../shared/models/semester.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { KostenValue } from '../../shared/models/kosten-value.model';
import { AliquotMode } from '../../shared/models/aliquot-config.model';
import { KostenDiscount, KostenDiscountOrder } from '../../shared/models/kosten-discount.model';
import { KostenDefinition } from '../../shared/models/kosten-definition.model';
import { discountFactors } from '../../settings/organisation/kosten-discount-preview.util';

@Component({
  selector: 'app-kosten-pro-semester',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatTableModule, MatSelectModule, MatFormFieldModule,
    MatInputModule, MatProgressSpinnerModule, MatCheckboxModule, MatTooltipModule,
    MatIconModule, MatButtonModule,
  ],
  template: `
    <div class="page-container">
      <h2>Kosten pro Semester</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
        <div class="filters">
          <mat-form-field appearance="outline">
            <mat-label>Semester</mat-label>
            <mat-select [value]="selectedSemesterId" (selectionChange)="onSemesterChange($event.value)">
              @for (semester of semesters; track semester.id) {
                <mat-option [value]="semester.id">{{ getSemesterLabel(semester) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Gruppe</mat-label>
            <mat-select [value]="selectedGroupId" (selectionChange)="onGroupChange($event.value)">
              @for (group of groups; track group.id) {
                <mat-option [value]="group.id">{{ $any(group.value).label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>

        <table mat-table [dataSource]="kostenValues" class="mat-elevation-z2 full-width">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Bezeichnung</th>
            <td mat-cell *matCellDef="let row">{{ row.label }}</td>
          </ng-container>

          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Betrag</th>
            <td mat-cell *matCellDef="let row">
              <mat-form-field appearance="outline" class="amount-field">
                <input matInput type="number" step="0.01"
                  [value]="row.amount"
                  (change)="onAmountChange(row, parseAmount($any($event.target).value))">
                <span matTextSuffix>{{ row.currency.symbol }}</span>
              </mat-form-field>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        <div class="config-card">
          <div class="card-heading">
            <h3>Geschwisterrabatt</h3>
            <mat-icon class="info-icon" matTooltip="Geschwisterrabatt: Ab dem 2. (bzw. konfigurierten) Kind wird der ausgewählte Kostenbeitrag prozentual reduziert. Die Reihenfolge legt fest, welches Kind als erstes (voller Beitrag) gilt.">info</mat-icon>
          </div>

          <mat-checkbox [(ngModel)]="kdApplyToAll">Rabatt auf alle Kostenpositionen anwenden</mat-checkbox>

          @if (!kdApplyToAll) {
            <div class="eligible-list">
              @for (def of activeDefinitions; track def.id) {
                <mat-checkbox [checked]="kdEligibleIds.includes(def.id)" (change)="toggleEligible(def.id)">{{ def.label }}</mat-checkbox>
              }
            </div>
          }

          <mat-form-field appearance="outline" class="order-field">
            <mat-label>Reihenfolge</mat-label>
            <mat-select [(ngModel)]="kdOrder">
              <mat-option value="MOST_EXPENSIVE_FIRST">Teuerstes Kind zuerst</mat-option>
              <mat-option value="LEAST_EXPENSIVE_FIRST">Günstigstes Kind zuerst</mat-option>
            </mat-select>
          </mat-form-field>

          <div class="tiers">
            @for (tier of kdTiers; track $index) {
              <div class="tier-row">
                <span>Ab dem</span>
                <mat-form-field appearance="outline" class="tier-num">
                  <input matInput type="number" min="2" [(ngModel)]="tier.fromChild" (ngModelChange)="recomputeKdPreview()">
                </mat-form-field>
                <span>. Kind:</span>
                <mat-form-field appearance="outline" class="tier-num">
                  <input matInput type="number" min="0" max="100" [(ngModel)]="tier.percent" (ngModelChange)="recomputeKdPreview()">
                </mat-form-field>
                <span>%</span>
                <button mat-button color="warn" (click)="removeKdTier($index)">Entfernen</button>
              </div>
            }
            <button mat-button (click)="addKdTier()">+ Staffel hinzufügen</button>
          </div>

          <div class="preview">
            <h4>Vorschau (Rabatt)</h4>
            @for (row of kdPreview; track row.child) {
              <div>{{ row.child }}. Kind: {{ row.percent }} % Rabatt</div>
            }
          </div>

          @if (kdError) {
            <p class="error">{{ kdError }}</p>
          }

          <button mat-raised-button color="primary" (click)="saveKostenDiscount()">Speichern</button>
        </div>

        <div class="config-card">
          <div class="card-heading">
            <h3>Aliquotierung (Kosten)</h3>
            <mat-icon class="info-icon" matTooltip="Aliquotierung: Bei unterjährigem Ein- oder Austritt eines Kindes werden die zu leistenden Stunden bzw. Kosten anteilig zu den Tagen berechnet, an denen das Kind im jeweiligen Monat einen Platz hat. 'Ganze Monate' = angefangener Monat zählt voll; 'Taggenau' = taggenaue Anteilsberechnung; 'Keine' = keine Anteilsberechnung.">info</mat-icon>
          </div>

          <mat-form-field appearance="outline">
            <mat-label>Modus</mat-label>
            <mat-select [(ngModel)]="kostenMode">
              <mat-option value="NONE">Keine</mat-option>
              <mat-option value="WHOLE_MONTH">Ganze Monate</mat-option>
              <mat-option value="PER_DAY">Taggenau</mat-option>
            </mat-select>
          </mat-form-field>

          <button mat-raised-button color="primary" (click)="saveKostenAliquot()">Speichern</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .page-container { padding: 24px; }
    .full-width { width: 100%; }
    .filters { display: flex; gap: 16px; margin-bottom: 16px; }
    .amount-field { width: 140px; }
    .config-card { margin-top: 32px; padding: 16px; border: 1px solid rgba(0,0,0,0.12); border-radius: 8px; display: flex; flex-direction: column; gap: 12px; max-width: 640px; }
    .card-heading { display: flex; align-items: center; gap: 8px; }
    .card-heading h3 { margin: 0; }
    .info-icon { cursor: help; color: rgba(0,0,0,0.54); font-size: 20px; width: 20px; height: 20px; }
    .eligible-list { display: flex; flex-direction: column; gap: 4px; padding-left: 16px; }
    .order-field { width: 260px; }
    .tiers { display: flex; flex-direction: column; gap: 8px; }
    .tier-row { display: flex; align-items: center; gap: 8px; }
    .tier-num { width: 90px; }
    .preview { display: flex; flex-direction: column; gap: 2px; }
    .preview h4 { margin: 0 0 4px 0; }
    .error { color: #b00020; }
  `],
})
export class KostenProSemesterComponent implements OnInit {
  displayedColumns = ['label', 'amount'];
  semesters: Semester[] = [];
  groups: FieldInstanceDTO[] = [];
  kostenValues: KostenValue[] = [];
  selectedSemesterId: string | null = null;
  selectedGroupId: string | null = null;
  loading = true;

  // Geschwisterrabatt state
  kdApplyToAll = false;
  kdOrder: KostenDiscountOrder = 'MOST_EXPENSIVE_FIRST';
  kdTiers: { fromChild: number; percent: number }[] = [];
  kdEligibleIds: string[] = [];
  kdPreview: { child: number; percent: number }[] = [];
  kdError: string | null = null;
  activeDefinitions: KostenDefinition[] = [];

  // Aliquotierung state (kostenMode edited; stundenMode echoed back)
  kostenMode: AliquotMode = 'NONE';
  stundenMode: AliquotMode = 'NONE';

  constructor(
    private semesterService: SemesterService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private kostenValueService: KostenValueService,
    private kostenDiscountService: KostenDiscountService,
    private aliquotConfigService: AliquotConfigService,
    private kostenDefinitionService: KostenDefinitionService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters;
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadValues();
      this.loadSemesterConfig();
    });

    this.orgService.getByTag('groups').subscribe((org) => {
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef) return;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.groups = instances;
        this.selectedGroupId = instances[0]?.id ?? null;
        this.loadValues();
      });
    });
  }

  private loadValues(): void {
    if (!this.selectedSemesterId || !this.selectedGroupId) {
      this.loading = false;
      return;
    }
    this.loading = true;
    this.kostenValueService.getForSemesterAndGroup(this.selectedSemesterId, this.selectedGroupId).subscribe((values) => {
      this.kostenValues = values;
      this.loading = false;
    });
  }

  private loadSemesterConfig(): void {
    const id = this.selectedSemesterId;
    if (!id) return;

    this.kostenDiscountService.get(id).subscribe((cfg) => {
      this.kdApplyToAll = cfg.applyToAll;
      this.kdOrder = cfg.order;
      this.kdTiers = (cfg.tiers ?? []).map((t) => ({ fromChild: t.fromChild, percent: t.percent }));
      this.kdEligibleIds = cfg.eligibleDefinitionIds ?? [];
      this.recomputeKdPreview();
    });

    this.aliquotConfigService.get(id).subscribe((cfg) => {
      this.kostenMode = cfg.kostenMode;
      this.stundenMode = cfg.stundenMode;
    });

    this.kostenDefinitionService.getAll().subscribe((defs) => {
      this.activeDefinitions = defs.filter((d) => d.active);
    });
  }

  onSemesterChange(semesterId: string): void {
    this.selectedSemesterId = semesterId;
    this.loadValues();
    this.loadSemesterConfig();
  }

  onGroupChange(groupId: string): void {
    this.selectedGroupId = groupId;
    this.loadValues();
  }

  getSemesterLabel(semester: Semester): string {
    const startYear = new Date(semester.start).getFullYear();
    const endYear = new Date(semester.end).getFullYear();
    return `${startYear}/${endYear}`;
  }

  parseAmount(value: string): number | null {
    if (value === '' || value == null) return null;
    const parsed = Number(value);
    return Number.isNaN(parsed) ? null : parsed;
  }

  onAmountChange(value: KostenValue, amount: number | null): void {
    if (!this.selectedSemesterId || !this.selectedGroupId) return;
    this.kostenValueService.upsert({
      semesterId: this.selectedSemesterId,
      groupId: this.selectedGroupId,
      definitionId: value.definitionId,
      amount,
    }).subscribe(() => {
      value.amount = amount;
    });
  }

  addKdTier(): void {
    const nextFrom = this.kdTiers.length ? Math.max(...this.kdTiers.map((t) => t.fromChild)) + 1 : 2;
    this.kdTiers.push({ fromChild: nextFrom, percent: 0 });
    this.recomputeKdPreview();
  }

  removeKdTier(index: number): void {
    this.kdTiers.splice(index, 1);
    this.recomputeKdPreview();
  }

  toggleEligible(defId: string): void {
    const i = this.kdEligibleIds.indexOf(defId);
    if (i >= 0) {
      this.kdEligibleIds.splice(i, 1);
    } else {
      this.kdEligibleIds.push(defId);
    }
  }

  recomputeKdPreview(): void {
    const childCounts = Array.from(new Set([1, ...this.kdTiers.map((t) => t.fromChild)])).sort((a, b) => a - b);
    const maxChildren = Math.max(1, ...childCounts);
    const factors = discountFactors(this.kdTiers, maxChildren);
    this.kdPreview = factors.filter((r) => childCounts.includes(r.child));
  }

  saveKostenDiscount(): void {
    const id = this.selectedSemesterId;
    if (!id) return;

    const froms = this.kdTiers.map((t) => t.fromChild);
    for (let i = 0; i < this.kdTiers.length; i++) {
      const t = this.kdTiers[i];
      if (t.fromChild < 2) {
        this.kdError = 'Jede Staffel muss ab dem 2. Kind oder später beginnen.';
        return;
      }
      if (i > 0 && froms[i] <= froms[i - 1]) {
        this.kdError = 'Die "Ab dem ... Kind"-Werte müssen eindeutig und aufsteigend sein.';
        return;
      }
      if (t.percent < 0 || t.percent > 100) {
        this.kdError = 'Der Rabatt muss zwischen 0 und 100 % liegen.';
        return;
      }
    }
    this.kdError = null;

    const dto: KostenDiscount = {
      semesterId: id,
      applyToAll: this.kdApplyToAll,
      order: this.kdOrder,
      tiers: this.kdTiers,
      eligibleDefinitionIds: this.kdEligibleIds,
    };
    this.kostenDiscountService.save(id, dto).subscribe();
  }

  saveKostenAliquot(): void {
    const id = this.selectedSemesterId;
    if (!id) return;
    this.aliquotConfigService.save(id, {
      semesterId: id,
      stundenMode: this.stundenMode,
      kostenMode: this.kostenMode,
    }).subscribe();
  }
}
