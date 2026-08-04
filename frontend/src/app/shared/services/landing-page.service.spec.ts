import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { LandingPageService } from './landing-page.service';
import { ApiService } from '../../core/services/api.service';

describe('LandingPageService', () => {
  let service: LandingPageService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'put', 'postBinary']);
    TestBed.configureTestingModule({
      providers: [LandingPageService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(LandingPageService);
  });

  it('liest den Inhalt von /landing-page', () => {
    api.get.and.returnValue(of({ bodyHtml: '<p>x</p>', updatedAt: null }));

    service.get().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page');
  });

  it('speichert den Inhalt per PUT auf /landing-page', () => {
    api.put.and.returnValue(of({ bodyHtml: '<p>neu</p>', updatedAt: null }));

    service.save('<p>neu</p>').subscribe();

    expect(api.put).toHaveBeenCalledWith('/landing-page', { bodyHtml: '<p>neu</p>' });
  });

  it('liest die Kacheln von /landing-page/placeholders', () => {
    api.get.and.returnValue(of([]));

    service.placeholders().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page/placeholders');
  });

  it('liest die Werte von /landing-page/context', () => {
    api.get.and.returnValue(of({}));

    service.context().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page/context');
  });

  it('lädt ein Bild binär auf /landing-page/images hoch', () => {
    api.postBinary.and.returnValue(of({ id: '1', url: '/api/v1/landing-page/images/1' }));
    const file = new File(['x'], 'bild.png', { type: 'image/png' });

    service.uploadImage(file).subscribe();

    expect(api.postBinary).toHaveBeenCalledWith('/landing-page/images', file, 'image/png');
  });
});
