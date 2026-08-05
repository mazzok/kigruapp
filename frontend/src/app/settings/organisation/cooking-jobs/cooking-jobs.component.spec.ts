import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CookingJobsComponent } from './cooking-jobs.component';

describe('CookingJobsComponent', () => {
  let fixture: ComponentFixture<CookingJobsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CookingJobsComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingJobsComponent);
    fixture.detectChanges();
  });

  it('rendert zwei Reiter: Erinnerungen und Übersichtsjobs', () => {
    const elements: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.mdc-tab__text-label');
    const labels = Array.from(elements).map((el) => el.textContent?.trim());

    expect(labels).toContain('Erinnerungen');
    expect(labels).toContain('Übersichtsjobs');
  });
});
