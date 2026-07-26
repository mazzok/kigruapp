import { of } from 'rxjs';
import Quill from 'quill';
import { AlignStyle } from 'quill/formats/align';
import { SizeStyle } from 'quill/formats/size';
import { MailTemplateEditorComponent } from './mail-template-editor.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile, SaveMailTemplateRequest } from '../../../shared/models/mail-template.model';

class FakeMailTemplateService {
  templates: MailTemplate[] = [
    { id: 't1', name: 'Willkommen', bodyHtml: '<p>Hallo</p>', createdAt: '2026-01-01', updatedAt: '2026-01-01' },
  ];
  placeholderTiles: PlaceholderTile[] = [
    { token: '{{person.firstName}}', fieldName: 'firstName', label: { de: 'Vorname', en: 'First name' } },
  ];
  createCalls: SaveMailTemplateRequest[] = [];
  updateCalls: { id: string; request: SaveMailTemplateRequest }[] = [];
  deleteCalls: string[] = [];

  list() {
    return of(this.templates);
  }
  placeholders() {
    return of(this.placeholderTiles);
  }
  create(request: SaveMailTemplateRequest) {
    this.createCalls.push(request);
    return of({ id: 't2', ...request, createdAt: '2026-01-02', updatedAt: '2026-01-02' } as MailTemplate);
  }
  update(id: string, request: SaveMailTemplateRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, createdAt: '2026-01-01', updatedAt: '2026-01-02' } as MailTemplate);
  }
  delete(id: string) {
    this.deleteCalls.push(id);
    return of(undefined);
  }
}

describe('MailTemplateEditorComponent', () => {
  let component: MailTemplateEditorComponent;
  let service: FakeMailTemplateService;

  beforeEach(() => {
    service = new FakeMailTemplateService();
    component = new MailTemplateEditorComponent(service as unknown as MailTemplateService);
    component.ngOnInit();
  });

  it('loads and lists templates', () => {
    expect(component.templates.length).toBe(1);
    expect(component.templates[0].name).toBe('Willkommen');
  });

  it('saving a new template calls create', () => {
    component.newTemplate();
    component.form.patchValue({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].name).toBe('Neu');
    expect(service.updateCalls.length).toBe(0);
  });

  it('saving an existing (selected) template calls update', () => {
    component.selectForEdit(service.templates[0]);
    component.form.patchValue({ name: 'Geändert' });

    component.save();

    expect(service.updateCalls.length).toBe(1);
    expect(service.updateCalls[0].id).toBe('t1');
    expect(service.updateCalls[0].request.name).toBe('Geändert');
  });

  it('delete calls service delete', () => {
    component.delete(service.templates[0]);

    expect(service.deleteCalls).toEqual(['t1']);
  });

  it('editorEmitsInlineStyledHtml: registers inline-style attributors for align and size, replacing the class-based defaults', () => {
    // constructing the component (in beforeEach) runs configureQuillForEmailSafeOutput()
    expect(Quill.import('formats/align')).toBe(AlignStyle as unknown as ReturnType<typeof Quill.import>);
    expect(Quill.import('formats/size')).toBe(SizeStyle as unknown as ReturnType<typeof Quill.import>);
  });

  it('toolbar only exposes formats that serialize to inline styles or semantic tags (no indent/list)', () => {
    const flatButtons = component.quillModules.toolbar.flat().map((b) => (typeof b === 'string' ? b : Object.keys(b)[0]));
    expect(flatButtons).not.toContain('indent');
    expect(flatButtons).not.toContain('list');
  });

  it('loads placeholder tiles from the service', () => {
    expect(component.placeholders.length).toBe(1);
    expect(component.placeholders[0].fieldName).toBe('firstName');
  });

  it('clicking a tile inserts the token into the body (no editor instance yet — appends)', () => {
    component.form.patchValue({ bodyHtml: '<p>Hallo</p>' });

    component.insertPlaceholder(service.placeholderTiles[0]);

    expect(component.form.value.bodyHtml).toContain('{{person.firstName}}');
  });

  it('clicking a tile inserts the token via the Quill instance when one is present', () => {
    const fakeQuill = {
      getSelection: () => ({ index: 3 }),
      getLength: () => 10,
      insertText: jasmine.createSpy('insertText'),
      root: { innerHTML: '<p>Hal{{person.firstName}}lo</p>' },
    };
    component.onEditorCreated(fakeQuill);

    component.insertPlaceholder(service.placeholderTiles[0]);

    expect(fakeQuill.insertText).toHaveBeenCalledWith(3, '{{person.firstName}}');
    expect(component.form.value.bodyHtml).toBe('<p>Hal{{person.firstName}}lo</p>');
  });
});
