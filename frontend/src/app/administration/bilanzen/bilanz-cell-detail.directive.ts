import { Directive, ElementRef, HostListener, Input, OnDestroy } from '@angular/core';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';
import { BilanzCellDetailCardComponent } from './bilanz-cell-detail-card.component';

@Directive({
  selector: '[appBilanzCellDetail]',
  standalone: true,
})
export class BilanzCellDetailDirective implements OnDestroy {
  @Input('appBilanzCellDetail') cell!: BilanzMonthCell;
  @Input() detailMonthLabel = '';
  @Input() detailYear = 0;

  private overlayRef: OverlayRef | null = null;

  constructor(private overlay: Overlay, private host: ElementRef<HTMLElement>) {}

  @HostListener('mouseenter')
  open(): void {
    if (this.overlayRef || !this.cell) return;
    const positionStrategy = this.overlay.position()
      .flexibleConnectedTo(this.host)
      .withPositions([
        { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 6 },
        { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -6 },
      ]);
    this.overlayRef = this.overlay.create({
      positionStrategy,
      scrollStrategy: this.overlay.scrollStrategies.reposition(),
    });
    const portal = new ComponentPortal(BilanzCellDetailCardComponent);
    const ref = this.overlayRef.attach(portal);
    ref.instance.cell = this.cell;
    ref.instance.monthLabel = this.detailMonthLabel;
    ref.instance.year = this.detailYear;
  }

  @HostListener('mouseleave')
  close(): void {
    this.overlayRef?.dispose();
    this.overlayRef = null;
  }

  ngOnDestroy(): void {
    this.close();
  }
}
