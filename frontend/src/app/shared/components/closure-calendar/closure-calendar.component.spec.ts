import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { ClosureCalendarComponent } from './closure-calendar.component';
import { ClosureDefinition } from '../../models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-01T00:00:00Z',
};

describe('ClosureCalendarComponent', () => {
  let fixture: ComponentFixture<ClosureCalendarComponent>;
  let component: ClosureCalendarComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ClosureCalendarComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ClosureCalendarComponent);
    component = fixture.componentInstance;
    // Mo 07.09. bis So 20.09.2026 — zwei volle Wochen mit Wochenenden dazwischen.
    component.from = '2026-09-07';
    component.to = '2026-09-20';
    component.definitions = [ferien];
    fixture.detectChanges();
  });

  function cell(iso: string): HTMLElement {
    const element = fixture.nativeElement.querySelector(`[data-date="${iso}"]`);
    if (!element) throw new Error(`Zelle ${iso} nicht gefunden`);
    return element as HTMLElement;
  }

  function press(iso: string, ctrl = false): void {
    cell(iso).dispatchEvent(new MouseEvent('mousedown', { ctrlKey: ctrl, bubbles: true }));
  }

  function moveOver(iso: string): void {
    cell(iso).dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  }

  function release(): void {
    document.dispatchEvent(new MouseEvent('mouseup'));
    fixture.detectChanges();
  }

  it('markiert einen einzelnen Tag', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-08');
    release();

    expect(emitted.length).toBe(1);
    expect(emitted[0]).toEqual(['2026-09-08']);
  });

  it('markiert beim Ziehen den gesamten Bereich', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-07');
    moveOver('2026-09-09');
    release();

    expect(emitted[0]).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
  });

  it('ueberspringt Wochenenden, ueberspannt sie aber', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-11');
    moveOver('2026-09-14');
    release();

    // Sa 12. und So 13. fehlen in der Auswahl.
    expect(emitted[0]).toEqual(['2026-09-11', '2026-09-14']);
  });

  it('zieht rueckwaerts genauso', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-09');
    moveOver('2026-09-07');
    release();

    expect(emitted[0]).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
  });

  it('ersetzt die Auswahl ohne STRG', () => {
    press('2026-09-07');
    release();
    press('2026-09-10');
    release();

    expect(component.selectedDays).toEqual(['2026-09-10']);
  });

  it('erweitert die Auswahl mit STRG', () => {
    press('2026-09-07');
    release();
    press('2026-09-10', true);
    release();

    expect(component.selectedDays).toEqual(['2026-09-07', '2026-09-10']);
  });

  it('nimmt mit STRG einen bereits markierten Tag wieder weg', () => {
    press('2026-09-07');
    moveOver('2026-09-09');
    release();
    press('2026-09-08', true);
    release();

    expect(component.selectedDays).toEqual(['2026-09-07', '2026-09-09']);
  });

  it('ignoriert Klicks auf Wochenenden', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-12');
    release();

    expect(emitted.length).toBe(0);
    expect(component.selectedDays).toEqual([]);
  });

  it('reagiert im readonly-Modus gar nicht', () => {
    component.readonly = true;
    fixture.detectChanges();
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-08');
    release();

    expect(emitted.length).toBe(0);
    expect(component.selectedDays).toEqual([]);
  });

  it('zeigt den Plus-Cursor nur bei STRG innerhalb des Kalenders', () => {
    const host = fixture.nativeElement.querySelector('.closure-calendar') as HTMLElement;

    host.dispatchEvent(new MouseEvent('mouseenter'));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control', ctrlKey: true }));
    fixture.detectChanges();
    expect(host.classList).toContain('ctrl-active');

    host.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(host.classList).not.toContain('ctrl-active');
  });

  it('leert die Auswahl auf Anforderung', () => {
    press('2026-09-08');
    release();
    expect(component.selectedDays.length).toBe(1);

    component.clearSelection();
    fixture.detectChanges();

    expect(component.selectedDays).toEqual([]);
  });

  describe('layout', () => {
    it('setzt standardmaessig kein layout-row', () => {
      const root = fixture.nativeElement.querySelector('.closure-calendar');
      expect(root.classList.contains('layout-row')).toBe(false);
    });

    it('setzt layout-row wenn layout auf row steht', () => {
      component.layout = 'row';
      fixture.detectChanges();

      const root = fixture.nativeElement.querySelector('.closure-calendar');
      expect(root.classList.contains('layout-row')).toBe(true);
    });

    it('scrollt horizontal statt zu umbrechen im row-layout', () => {
      component.layout = 'row';
      fixture.detectChanges();

      const root = fixture.nativeElement.querySelector('.closure-calendar');
      const style = getComputedStyle(root);
      expect(style.overflowX).toBe('auto');
      expect(style.flexWrap).toBe('nowrap');
    });
  });

  describe('mode: single', () => {
    beforeEach(() => {
      component.mode = 'single';
      component.restrictWeekends = false;
      component.ngOnChanges();
      fixture.detectChanges();
    });

    it('ersetzt die Auswahl bei jedem Klick statt sie zu erweitern', () => {
      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-08');
      press('2026-09-10');

      expect(emitted).toEqual([['2026-09-08'], ['2026-09-10']]);
      expect(component.selectedDays).toEqual(['2026-09-10']);
    });

    it('ignoriert Ziehen (kein Range-Select)', () => {
      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-07');
      moveOver('2026-09-09');

      expect(component.selectedDays).toEqual(['2026-09-07']);
      expect(emitted).toEqual([['2026-09-07']]);
    });

    it('erlaubt die Auswahl von Wochenendtagen, wenn restrictWeekends=false', () => {
      press('2026-09-12');

      expect(component.selectedDays).toEqual(['2026-09-12']);
    });

    it('blockiert Tage, die einer Schliessperiode zugeordnet sind', () => {
      component.periods = [{ id: 'p1', from: '2026-09-08', to: '2026-09-08', definitionId: 'def-ferien' }];
      component.ngOnChanges();
      fixture.detectChanges();

      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-08');

      expect(emitted.length).toBe(0);
      expect(component.selectedDays).toEqual([]);
    });

    it('zeigt eine initiale Auswahl aus initialSelection', () => {
      component.initialSelection = ['2026-09-09'];
      component.ngOnChanges();
      fixture.detectChanges();

      expect(component.selectedDays).toEqual(['2026-09-09']);
      expect(component.isSelected(findDayFixture('2026-09-09'))).toBe(true);
    });
  });

  function findDayFixture(iso: string) {
    return component.months.flatMap(m => m.days).find(d => d.date === iso)!;
  }
});
