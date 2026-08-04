import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import Quill from 'quill';
import { AlignStyle } from 'quill/formats/align';
import { SizeStyle } from 'quill/formats/size';
import { MailTemplateFormComponent } from './mail-template-form.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { PlaceholderTile } from '../../../shared/models/mail-template.model';

const TILES: PlaceholderTile[] = [
  { token: '{{duty.date}}', fieldName: 'date', label: { de: 'Datum' }, group: 'KOCHDIENST', groupLabel: 'Kochdienst' },
  { token: '{{person.firstName}}', fieldName: 'firstName', label: { de: 'Vorname' }, group: 'PERSON', groupLabel: 'Person' },
];

describe('MailTemplateFormComponent', () => {
  let fixture: ComponentFixture<MailTemplateFormComponent>;
  let component: MailTemplateFormComponent;
  let service: jasmine.SpyObj<MailTemplateService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('MailTemplateService', ['placeholders']);
    service.placeholders.and.returnValue(of(TILES));

    await TestBed.configureTestingModule({
      imports: [MailTemplateFormComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [{ provide: MailTemplateService, useValue: service }],
    }).compileComponents();

    fixture = TestBed.createComponent(MailTemplateFormComponent);
    component = fixture.componentInstance;
  });

  it('laedt die Platzhalter fuer die uebergebene Art', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(service.placeholders).toHaveBeenCalledWith('COOKING');
  });

  it('gruppiert die Chips nach Gruppe', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(component.groups.map((g) => g.label)).toEqual(['Kochdienst', 'Person']);
    expect(component.groups[0].tiles.length).toBe(1);
  });

  it('meldet Aenderungen in Token-Form nach aussen', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const emitted: { name: string; bodyHtml: string }[] = [];
    component.valueChange.subscribe((v) => emitted.push(v));

    component.form.patchValue({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });

    expect(emitted[emitted.length - 1]).toEqual({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });
  });

  it('ist erst mit Name und Inhalt gueltig', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(component.valid).toBeFalse();
    component.form.patchValue({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });
    expect(component.valid).toBeTrue();
  });

  it('editorEmitsInlineStyledHtml: registers inline-style attributors for align and size, replacing the class-based defaults', () => {
    // constructing the component runs configureQuillForEmailSafeOutput()
    expect(Quill.import('formats/align')).toBe(AlignStyle as unknown as ReturnType<typeof Quill.import>);
    expect(Quill.import('formats/size')).toBe(SizeStyle as unknown as ReturnType<typeof Quill.import>);
  });

  it('toolbar only exposes formats that serialize to inline styles or semantic tags (no indent/list)', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    const flatButtons = component.quillModules.toolbar.flat().map((b) => (typeof b === 'string' ? b : Object.keys(b)[0]));
    expect(flatButtons).not.toContain('indent');
    expect(flatButtons).not.toContain('list');
  });

  it('clicking a tile inserts a pill embed via the Quill instance', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const fakeQuill = {
      getSelection: () => ({ index: 3 }),
      getLength: () => 10,
      insertEmbed: jasmine.createSpy('insertEmbed'),
      setSelection: jasmine.createSpy('setSelection'),
      root: { innerHTML: '<p>Hal<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>lo</p>' },
    };
    component.onEditorCreated(fakeQuill);

    component.insertPlaceholder(TILES[1]);

    expect(fakeQuill.insertEmbed).toHaveBeenCalledWith(
      3, 'mail-token', { token: '{{person.firstName}}', label: 'Vorname' });
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  });

  it('clicking a tile with no editor appends a pill span to the body', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    component.form.patchValue({ bodyHtml: '<p>Hallo</p>' });

    component.insertPlaceholder(TILES[1]);

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('>Vorname<');
  });

  it('setting value loads it with tokens converted to pills', () => {
    // Angular fires @Input() setters before ngOnInit, so `value` must be set
    // before the first detectChanges() to reproduce real template-binding order.
    component.kind = 'COOKING';
    component.value = { name: 'Vorlage', bodyHtml: '<p>Hallo {{person.firstName}}</p>' };
    fixture.detectChanges();

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).not.toContain('{{person.firstName}}</p>');
  });

  it('re-applies the pill conversion once placeholders arrive after the initial value setter', () => {
    // Reproduces the real ordering: `value` is set (as Angular does, before
    // ngOnInit) while `placeholders` is still empty, so if the component only
    // converted once at setter-time this would be stuck showing raw tokens.
    component.kind = 'COOKING';
    component.value = { name: 'Vorlage', bodyHtml: '<p>Hallo {{person.firstName}}</p>' };

    expect(component.form.value.bodyHtml).toContain('{{person.firstName}}');
    expect(component.form.value.bodyHtml).not.toContain('data-token');

    fixture.detectChanges(); // triggers ngOnInit -> placeholders() resolves synchronously (of())

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('>Vorname<');
    expect(component.form.value.bodyHtml).not.toContain('{{person.firstName}}</p>');
  });

  it('emits pills converted back to raw tokens (no mail-token spans) via valueChange', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const emitted: { name: string; bodyHtml: string }[] = [];
    component.valueChange.subscribe((v) => emitted.push(v));

    component.form.patchValue({
      name: 'Neu',
      bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
    });

    expect(emitted[emitted.length - 1].bodyHtml).toBe('<p>Hallo {{person.firstName}}</p>');
    expect(emitted[emitted.length - 1].bodyHtml).not.toContain('mail-token');
  });

  it('preview substitutes sample data for tokens on body change', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    component.form.patchValue({
      bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
    });

    expect((component.previewHtml as any).changingThisBreaksApplicationSecurity).toBe('<p>Hallo Anna</p>');
  });

  it('dragging a chip sets the token payload on the drag event', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const data: Record<string, string> = {};
    const event = { dataTransfer: { setData: (k: string, v: string) => (data[k] = v), effectAllowed: '' } } as unknown as DragEvent;

    component.onChipDragStart(event, TILES[1]);

    expect(data['application/x-mail-token']).toBe('{{person.firstName}}');
  });

  it('dropping on the editor inserts a pill (falls back to end when caret is unresolved)', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const fakeQuill = {
      getLength: () => 5,
      insertEmbed: jasmine.createSpy('insertEmbed'),
      setSelection: jasmine.createSpy('setSelection'),
      root: { innerHTML: '' },
    };
    component.onEditorCreated(fakeQuill);
    const event = {
      preventDefault: () => {},
      clientX: -1, clientY: -1,
      dataTransfer: { getData: (k: string) => (k === 'application/x-mail-token' ? '{{person.firstName}}' : '') },
    } as unknown as DragEvent;

    component.onEditorDrop(event);

    expect(fakeQuill.insertEmbed).toHaveBeenCalled();
    const args = (fakeQuill.insertEmbed as jasmine.Spy).calls.mostRecent().args;
    expect(args[1]).toBe('mail-token');
    expect(args[2]).toEqual({ token: '{{person.firstName}}', label: 'Vorname' });
  });
});
