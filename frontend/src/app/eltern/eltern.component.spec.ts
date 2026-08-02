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
    groups: [
      {
        groupInstanceId: 'g1',
        groupName: 'Käfergruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: ['Lena'],
            parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: '0660 111' }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
          {
            familyId: 'f2',
            isOwnFamily: false,
            children: ['Tim'],
            parents: [{ firstName: 'Clara', lastName: 'Sommer', email: null, phone: null }],
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
            children: ['Paul'],
            parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: '0660 111' }],
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

  it('markiert die eigene Familie', async () => {
    await setup();

    expect(fixture.nativeElement.textContent).toContain('(meine Familie)');
  });

  it('zeigt einen Hinweis, wenn keine Gruppen vorhanden sind', async () => {
    await setup(of({ semesterId: 's1', groups: [] }));

    expect(component.selectedGroupId).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('keiner Gruppe');
  });

  it('meldet Ladefehler und zeigt einen Wiederholen-Hinweis', async () => {
    await setup(throwError(() => new Error('boom')));

    expect(notify.error).toHaveBeenCalledWith('Fehler');
    expect(component.failed).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Erneut laden');
  });
});
