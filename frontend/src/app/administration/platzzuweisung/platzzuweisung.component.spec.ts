import { of } from 'rxjs';
import { PlatzzuweisungComponent } from './platzzuweisung.component';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ChildDTO } from '../../shared/models/person.model';
import { Semester } from '../../shared/models/semester.model';

class FakeSemesterService {
  semesters: Semester[] = [];
  getAll() {
    return of(this.semesters);
  }
}

class FakePersonService {
  getChildrenCalls: (string | undefined)[] = [];
  assignGroupCalls: { personId: string; definitionId: string; fieldInstanceId: string; semesterId: string | undefined }[] = [];
  children: ChildDTO[] = [];

  getChildren(semesterId?: string) {
    this.getChildrenCalls.push(semesterId);
    return of(this.children);
  }

  assignGroup(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string) {
    this.assignGroupCalls.push({ personId, definitionId, fieldInstanceId, semesterId });
    return of(undefined);
  }
}

class FakeOrganisationService {
  getByTag(_tag: string) {
    return of({ id: 'org-groups', tag: 'groups', definitions: [], entries: [] });
  }
}

class FakeFieldInstanceService {
  listByDefinitionId(_definitionId: string) {
    return of([]);
  }
}

describe('PlatzzuweisungComponent - Semester', () => {
  let component: PlatzzuweisungComponent;
  let personService: FakePersonService;
  let semesterService: FakeSemesterService;

  beforeEach(() => {
    personService = new FakePersonService();
    semesterService = new FakeSemesterService();

    component = new PlatzzuweisungComponent(
      personService as unknown as PersonService,
      new FakeOrganisationService() as unknown as OrganisationService,
      new FakeFieldInstanceService() as unknown as FieldInstanceService,
      semesterService as unknown as SemesterService,
    );
  });

  it('defaults to the most recently created semester and loads children for it', () => {
    semesterService.semesters = [
      { id: 'semester-2', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-02-01T00:00:00Z' },
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];

    component.ngOnInit();

    expect(component.selectedSemesterId).toBe('semester-2');
    expect(personService.getChildrenCalls).toEqual(['semester-2']);
  });

  it('reloads children when the semester changes', () => {
    semesterService.semesters = [
      { id: 'semester-2', start: '2025-09-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-02-01T00:00:00Z' },
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];
    component.ngOnInit();

    component.onSemesterChange('semester-1');

    expect(component.selectedSemesterId).toBe('semester-1');
    expect(personService.getChildrenCalls).toEqual(['semester-2', 'semester-1']);
  });

  it('includes the selected semester when assigning a group', () => {
    semesterService.semesters = [
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];
    component.ngOnInit();
    (component as any).groupDefinitionId = 'def-group';

    component.onGroupChange({ id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null, groupDefinitionId: null, groupInstanceId: null }, 'inst-1');

    expect(personService.assignGroupCalls).toEqual([
      { personId: 'child-1', definitionId: 'def-group', fieldInstanceId: 'inst-1', semesterId: 'semester-1' },
    ]);
  });
});
