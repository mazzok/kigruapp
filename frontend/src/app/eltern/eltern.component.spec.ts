import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ElternComponent } from './eltern.component';
import { ParentDirectoryService } from './services/parent-directory.service';
import { NotificationService } from '../shared/services/notification.service';
import { ParentDirectory } from '../shared/models/parent-directory.model';

describe('ElternComponent', () => {
  let fixture: ComponentFixture<ElternComponent>;
  let component: ElternComponent;
  let service: jasmine.SpyObj<ParentDirectoryService>;
  let notify: jasmine.SpyObj<NotificationService>;

  const directory: ParentDirectory = {
    semesterId: 's1',
    columns: [
      { key: 'childName', label: 'Vorname', scope: 'CHILD' },
      { key: 'firstName', label: 'Vorname', scope: 'PARENT' },
      { key: 'email', label: 'E-Mail', scope: 'PARENT' },
      { key: 'address', label: 'Adresse', scope: 'FAMILY' },
    ],
    groups: [
      {
        groupInstanceId: 'g1',
        groupName: 'Käfergruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: [{ name: 'Lena', entryDate: null, exitDate: null }],
            parents: [{ values: { firstName: 'Anna', email: 'anna@x.at' } }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
          {
            familyId: 'f2',
            isOwnFamily: false,
            children: [{ name: 'Tim', entryDate: null, exitDate: null }],
            parents: [{ values: { firstName: 'Clara' } }],
            address: null,
          },
        ],
      },
      {
        groupInstanceId: 'g2',
        groupName: 'Bienengruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: [{ name: 'Paul', entryDate: '2026-09-01', exitDate: null }],
            parents: [{ values: { firstName: 'Anna', email: 'anna@x.at' } }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
        ],
      },
    ],
  };

  async function setup(response = of(directory)): Promise<void> {
    service = jasmine.createSpyObj<ParentDirectoryService>('ParentDirectoryService', ['load']);
    service.load.and.returnValue(response);
    notify = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error', 'extractError']);
    notify.extractError.and.returnValue('Fehler');

    await TestBed.configureTestingModule({
      imports: [ElternComponent],
      providers: [
        provideNoopAnimations(),
        { provide: ParentDirectoryService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ElternComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('wählt beim Laden die erste Gruppe vor und zeigt deren Familien', async () => {
    await setup();

    expect(component.selectedGroupId).toBe('g1');
    expect(component.selectedGroup?.families.length).toBe(2);

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Lena');
    expect(fixture.nativeElement.textContent).toContain('Clara');
  });

  it('tauscht die Zeilen beim Gruppenwechsel', async () => {
    await setup();

    component.selectGroup('g2');
    fixture.detectChanges();

    expect(component.selectedGroup?.families.length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Paul');
    expect(fixture.nativeElement.textContent).not.toContain('Tim');
  });

  it('markiert die eigene Familie farblich statt mit Text', async () => {
    await setup();

    expect(fixture.nativeElement.querySelectorAll('tr.own-family').length).toBe(1);
    expect(fixture.nativeElement.textContent).not.toContain('meine Familie');
  });

  it('zeigt einen Hinweis, wenn keine Gruppen vorhanden sind', async () => {
    await setup(of({ semesterId: 's1', columns: directory.columns, groups: [] }));

    expect(component.selectedGroupId).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('keiner Gruppe');
  });

  it('rendert Familien mit doppelten oder fehlenden Kindernamen ohne zu werfen', async () => {
    const duplicateNames: ParentDirectory = {
      semesterId: 's1',
      columns: directory.columns,
      groups: [
        {
          groupInstanceId: 'g1',
          groupName: 'Käfergruppe',
          families: [
            {
              familyId: 'f1',
              isOwnFamily: true,
              children: [
                { name: 'Lena', entryDate: null, exitDate: null },
                { name: 'Lena', entryDate: null, exitDate: null },
              ],
              parents: [{ values: { firstName: 'Anna', email: 'anna@x.at' } }],
              address: 'Hauptstraße 1, 1010 Wien',
            },
            {
              familyId: 'f2',
              isOwnFamily: false,
              children: [
                { name: null, entryDate: null, exitDate: null },
                { name: null, entryDate: null, exitDate: null },
              ],
              parents: [{ values: { firstName: 'Bert', email: 'bert@x.at' } }],
              address: null,
            },
          ],
        },
      ],
    };

    await setup(of(duplicateNames));

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    // Beide gleichnamigen Kinder erscheinen, der leere Name bleibt eine leere Zeile.
    expect(rows[0].querySelectorAll('td')[0].textContent.replace(/\s+/g, '')).toContain('LenaLena');
    expect(rows[1].querySelectorAll('td')[0].querySelectorAll('div').length).toBe(2);
  });

  it('meldet Ladefehler und zeigt einen Wiederholen-Hinweis', async () => {
    await setup(throwError(() => new Error('boom')));

    expect(notify.error).toHaveBeenCalledWith('Fehler');
    expect(component.failed).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Erneut laden');
  });

  it('rendert genau die gelieferten Spalten', async () => {
    await setup();

    const headers = Array.from(fixture.nativeElement.querySelectorAll('th'))
      .map((th) => (th as HTMLElement).textContent?.trim());

    expect(headers).toEqual(['Kind(er)', 'Vorname', 'E-Mail', 'Adresse']);
  });

  it('laesst die Adressspalte weg, wenn sie nicht geliefert wird', async () => {
    await setup(of({ ...directory, columns: directory.columns.filter((c) => c.key !== 'address') }));

    const headers = Array.from(fixture.nativeElement.querySelectorAll('th'))
      .map((th) => (th as HTMLElement).textContent?.trim());

    expect(headers).not.toContain('Adresse');
  });

  it('zeigt Eintrittsdatum unter dem Kindernamen', async () => {
    await setup(of({
      ...directory,
      columns: [...directory.columns, { key: 'childEntryDate', label: 'Eintritt', scope: 'CHILD' as const }],
    }));

    component.selectGroup('g2');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2026-09-01');
  });

  it('verlinkt E-Mail-Adressen', async () => {
    await setup();

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a[href^="mailto:"]');

    expect(link.getAttribute('href')).toBe('mailto:anna@x.at');
  });
});
