import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HoursBreakdownComponent } from './hours-breakdown.component';
import { OurHours } from '../../shared/models/hour-entry.model';

describe('HoursBreakdownComponent', () => {
  let fixture: ComponentFixture<HoursBreakdownComponent>;

  const our: OurHours = {
    familyId: 'f1',
    familyMonthlyMinutes: 705,
    monthsInSemester: 3,
    sollMinutes: 1890,
    istMinutes: 900,
    allGroups: false,
    children: [
      {
        childId: 'a', name: 'Lena', groupLabel: 'Käfergruppe', groupColor: '#43a047',
        baseMinutesPerMonth: 480, entryDate: null, exitDate: null, sollMinutes: 1440,
      },
      {
        childId: 'b', name: 'Jonas', groupLabel: 'Bärengruppe', groupColor: '#fb8c00',
        baseMinutesPerMonth: 300, entryDate: '2026-10-16', exitDate: null, sollMinutes: 450,
      },
    ],
    months: [
      { month: '2026-09', sollMinutes: 480, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
      ] },
      { month: '2026-10', sollMinutes: 593, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
        { childId: 'b', minutes: 113, fractionPercent: 50, discountPercent: 25 },
      ] },
      { month: '2026-11', sollMinutes: 705, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
        { childId: 'b', minutes: 225, fractionPercent: 100, discountPercent: 25 },
      ] },
    ],
    entries: [],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HoursBreakdownComponent] }).compileComponents();
    fixture = TestBed.createComponent(HoursBreakdownComponent);
  });

  function render(value: OurHours | null): HTMLElement {
    fixture.componentInstance.our = value;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('zeigt eine Zeile je Kind mit Gruppe und Satz', () => {
    const el = render(our);
    const rows = el.querySelectorAll('.child-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Lena');
    expect(rows[0].textContent).toContain('Käfergruppe');
    expect(rows[0].textContent).toContain('08:00');   // Satz/Monat Lena
    expect(rows[1].textContent).toContain('Jonas');
    expect(rows[1].textContent).toContain('05:00');   // Satz/Monat Jonas
    expect(rows[1].textContent).toContain('07:30');   // Summe Jonas: 450 Minuten
  });

  it('nennt bei abweichendem Zeitraum das Eintrittsdatum', () => {
    const el = render(our);
    expect(el.querySelectorAll('.child-row')[1].textContent).toContain('ab 16.10.2026');
  });

  it('blendet die Gruppenspalte aus, wenn ein Satz für alle gilt', () => {
    const el = render({ ...our, allGroups: true });
    expect(el.querySelector('.group-column')).toBeNull();
  });

  it('zeigt den Monatsverlauf als Spannen', () => {
    const el = render(our);
    const spans = el.querySelectorAll('.month-span');
    expect(spans.length).toBe(3);
    expect(spans[0].textContent).toContain('Sep 2026');
  });

  it('zeigt Kennzahlen mit Bilanz und Erfüllungsstufe', () => {
    const el = render(our);
    expect(el.querySelector('.kpi-soll')!.textContent).toContain('31:30');
    expect(el.querySelector('.kpi-ist')!.textContent).toContain('15:00');
    expect(el.querySelector('.progress-bar-fill')!.classList).toContain('level-level5');
  });

  it('zeigt bei fehlendem Soll nur einen Hinweis', () => {
    const el = render({ ...our, sollMinutes: 0, children: [], months: [] });
    expect(el.querySelector('.kpi-soll')).toBeNull();
    expect(el.textContent).toContain('keine zu leistenden Stunden hinterlegt');
  });

  it('zeigt nichts, solange keine Daten geladen sind', () => {
    const el = render(null);
    expect(el.textContent!.trim()).toBe('');
  });
});
