import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import Quill from 'quill';
import { AlignStyle } from 'quill/formats/align';
import { SizeStyle } from 'quill/formats/size';
import { DomSanitizer } from '@angular/platform-browser';
import { MailTemplateEditorComponent } from './mail-template-editor.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile, SaveMailTemplateRequest } from '../../../shared/models/mail-template.model';
import { NotificationService } from '../../../shared/services/notification.service';

const fakeSanitizer = { bypassSecurityTrustHtml: (v: string) => v } as unknown as DomSanitizer;

class FakeNotificationService {
  successCalls: string[] = [];
  errorCalls: string[] = [];
  success(message: string) {
    this.successCalls.push(message);
  }
  error(message: string) {
    this.errorCalls.push(message);
  }
  extractError(err: unknown) {
    return err instanceof HttpErrorResponse ? String(err.error) : 'error';
  }
}

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
  let notify: FakeNotificationService;

  beforeEach(() => {
    service = new FakeMailTemplateService();
    notify = new FakeNotificationService();
    component = new MailTemplateEditorComponent(
      service as unknown as MailTemplateService,
      fakeSanitizer,
      notify as unknown as NotificationService,
    );
    component.ngOnInit();
  });

  it('loads and lists templates', () => {
    expect(component.templates.length).toBe(1);
    expect(component.templates[0].name).toBe('Willkommen');
  });

  it('starts with the editor closed and opens it via newTemplate / selectForEdit', () => {
    expect(component.editing).toBe(false);

    component.newTemplate();
    expect(component.editing).toBe(true);

    component.closeEditor();
    expect(component.editing).toBe(false);

    component.selectForEdit(service.templates[0]);
    expect(component.editing).toBe(true);
    expect(component.selectedId).toBe('t1');
  });

  it('closes the editor after a successful save', () => {
    component.newTemplate();
    component.form.patchValue({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(component.editing).toBe(false);
  });

  it('saving a new template calls create', () => {
    component.newTemplate();
    component.form.patchValue({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].name).toBe('Neu');
    expect(service.updateCalls.length).toBe(0);
    expect(notify.successCalls).toEqual(['Vorlage gespeichert']);
  });

  it('shows an error popup when saving a template fails', () => {
    service.create = () =>
      throwError(() => new HttpErrorResponse({ status: 400, error: 'bodyHtml is required' }));
    component.newTemplate();
    component.form.patchValue({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(notify.errorCalls).toEqual(['bodyHtml is required']);
    expect(notify.successCalls).toEqual([]);
  });

  it('saving an existing (selected) template calls update', () => {
    component.onSelectTemplate('t1');
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

  it('clicking a tile inserts a pill embed via the Quill instance', () => {
    const fakeQuill = {
      getSelection: () => ({ index: 3 }),
      getLength: () => 10,
      insertEmbed: jasmine.createSpy('insertEmbed'),
      setSelection: jasmine.createSpy('setSelection'),
      root: { innerHTML: '<p>Hal<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>lo</p>' },
    };
    component.onEditorCreated(fakeQuill);

    component.insertPlaceholder(service.placeholderTiles[0]);

    expect(fakeQuill.insertEmbed).toHaveBeenCalledWith(
      3, 'mail-token', { token: '{{person.firstName}}', label: 'Vorname' });
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  });

  it('clicking a tile with no editor appends a pill span to the body', () => {
    component.form.patchValue({ bodyHtml: '<p>Hallo</p>' });

    component.insertPlaceholder(service.placeholderTiles[0]);

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('>Vorname<');
  });

  it('selecting a template loads it with tokens converted to pills', () => {
    service.templates[0].bodyHtml = '<p>Hallo {{person.firstName}}</p>';

    component.onSelectTemplate('t1');

    expect(component.selectedId).toBe('t1');
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).not.toContain('{{person.firstName}}</p>');
  });

  it('saving converts pills back to raw tokens (no mail-token spans persisted)', () => {
    component.newTemplate();
    component.form.patchValue({
      name: 'Neu',
      bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
    });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].bodyHtml).toBe('<p>Hallo {{person.firstName}}</p>');
    expect(service.createCalls[0].bodyHtml).not.toContain('mail-token');
  });

  it('preview substitutes sample data for tokens on body change', () => {
    component.form.patchValue({
      bodyHtml: '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>',
    });

    expect(component.previewHtml as unknown as string).toBe('<p>Hallo Anna</p>');
  });

  it('dragging a chip sets the token payload on the drag event', () => {
    const data: Record<string, string> = {};
    const event = { dataTransfer: { setData: (k: string, v: string) => (data[k] = v), effectAllowed: '' } } as unknown as DragEvent;

    component.onChipDragStart(event, service.placeholderTiles[0]);

    expect(data['application/x-mail-token']).toBe('{{person.firstName}}');
  });

  it('dropping on the editor inserts a pill (falls back to end when caret is unresolved)', () => {
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
