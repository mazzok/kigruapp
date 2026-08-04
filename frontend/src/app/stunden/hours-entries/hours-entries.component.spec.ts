import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HoursEntriesComponent } from './hours-entries.component';
import { OurHoursEntry } from '../../shared/models/hour-entry.model';

describe('HoursEntriesComponent', () => {
  let fixture: ComponentFixture<HoursEntriesComponent>;

  const entries: OurHoursEntry[] = [
    { id: '1', personId: 'p1', personName: 'Martin', roleLabel: 'Reparatur', date: '2026-11-04', minutes: 450, comment: '' },
    { id: '2', personId: 'p2', personName: 'Anna', roleLabel: 'Küche', date: '2026-10-19', minutes: 270, comment: '' },
    { id: '3', personId: 'p1', personName: 'Martin', roleLabel: 'Garten', date: '2026-10-12', minutes: 180, comment: '' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HoursEntriesComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(HoursEntriesComponent);
    fixture.componentInstance.entries = entries;
    fixture.componentInstance.ownPersonId = 'p1';
    fixture.detectChanges();
  });

  it('zeigt alle Einträge absteigend nach Datum', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('04.11.2026');
    expect(rows[2].textContent).toContain('12.10.2026');
  });

  it('bietet je vorkommendem Monat einen Filterwert', () => {
    expect(fixture.componentInstance.months).toEqual(['2026-11', '2026-10']);
  });

  it('filtert auf den gewählten Monat', () => {
    fixture.componentInstance.selectedMonth = '2026-10';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row').length).toBe(2);
  });

  it('zeigt Aktionen nur bei eigenen Einträgen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row');
    expect(rows[0].querySelector('.entry-actions')).not.toBeNull();   // Martin, eigener Eintrag
    expect(rows[1].querySelector('.entry-actions')).toBeNull();       // Anna
  });

  it('meldet Bearbeiten und Löschen nach außen', () => {
    const edit = jasmine.createSpy('edit');
    const remove = jasmine.createSpy('remove');
    fixture.componentInstance.edit.subscribe(edit);
    fixture.componentInstance.remove.subscribe(remove);
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector('.entry-row')!;
    (row.querySelector('.action-edit') as HTMLButtonElement).click();
    (row.querySelector('.action-delete') as HTMLButtonElement).click();

    expect(edit).toHaveBeenCalledWith(entries[0]);
    expect(remove).toHaveBeenCalledWith(entries[0]);
  });

  it('zeigt einen Hinweis, wenn keine Einträge vorliegen', () => {
    fixture.componentInstance.entries = [];
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Noch keine Einträge');
  });
});
