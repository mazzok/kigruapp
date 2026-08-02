import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ParentDirectoryService } from './parent-directory.service';
import { ParentDirectory } from '../../shared/models/parent-directory.model';

describe('ParentDirectoryService', () => {
  let service: ParentDirectoryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ParentDirectoryService],
    });
    service = TestBed.inject(ParentDirectoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lädt das Verzeichnis von /parent-directory', () => {
    const payload: ParentDirectory = {
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
              parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: null }],
              address: 'Hauptstraße 1, 1010 Wien',
            },
          ],
        },
      ],
    };

    let result: ParentDirectory | undefined;
    service.load().subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/v1/parent-directory');
    expect(req.request.method).toBe('GET');
    req.flush(payload);

    expect(result).toEqual(payload);
  });
});
