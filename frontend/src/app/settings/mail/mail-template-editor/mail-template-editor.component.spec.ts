import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MailTemplateEditorComponent } from './mail-template-editor.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, SaveMailTemplateRequest } from '../../../shared/models/mail-template.model';
import { NotificationService } from '../../../shared/services/notification.service';

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
    { id: 't1', name: 'Willkommen', bodyHtml: '<p>Hallo</p>', kind: 'GENERAL', createdAt: '2026-01-01', updatedAt: '2026-01-01' },
  ];
  createCalls: SaveMailTemplateRequest[] = [];
  updateCalls: { id: string; request: SaveMailTemplateRequest }[] = [];
  deleteCalls: string[] = [];

  list() {
    return of(this.templates);
  }
  create(request: SaveMailTemplateRequest) {
    this.createCalls.push(request);
    return of({ id: 't2', ...request, kind: 'GENERAL', createdAt: '2026-01-02', updatedAt: '2026-01-02' } as MailTemplate);
  }
  update(id: string, request: SaveMailTemplateRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, kind: 'GENERAL', createdAt: '2026-01-01', updatedAt: '2026-01-02' } as MailTemplate);
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

  it('selecting a template loads its value into the embedded form', () => {
    component.onSelectTemplate('t1');

    expect(component.editorValue).toEqual({ name: 'Willkommen', bodyHtml: '<p>Hallo</p>' });
  });

  it('does not open cooking templates for editing', () => {
    service.templates.push({
      id: 't-cooking', name: 'Kochdienst', bodyHtml: '<p>x</p>', kind: 'COOKING',
      createdAt: '2026-01-01', updatedAt: '2026-01-01',
    });

    component.onSelectTemplate('t-cooking');

    expect(component.editing).toBe(false);
    expect(component.selectedId).toBeNull();
  });

  it('isCooking reflects the template kind', () => {
    expect(component.isCooking(service.templates[0])).toBe(false);
    expect(component.isCooking({ ...service.templates[0], kind: 'COOKING' })).toBe(true);
  });

  it('closes the editor after a successful save', () => {
    component.newTemplate();
    component.onFormValueChange({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(component.editing).toBe(false);
  });

  it('saving a new template calls create', () => {
    component.newTemplate();
    component.onFormValueChange({ name: 'Neu', bodyHtml: '<p>x</p>' });

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
    component.onFormValueChange({ name: 'Neu', bodyHtml: '<p>x</p>' });

    component.save();

    expect(notify.errorCalls).toEqual(['bodyHtml is required']);
    expect(notify.successCalls).toEqual([]);
  });

  it('saving an existing (selected) template calls update', () => {
    component.onSelectTemplate('t1');
    component.onFormValueChange({ name: 'Geändert', bodyHtml: '<p>Hallo</p>' });

    component.save();

    expect(service.updateCalls.length).toBe(1);
    expect(service.updateCalls[0].id).toBe('t1');
    expect(service.updateCalls[0].request.name).toBe('Geändert');
  });

  it('formValid requires both name and content from the embedded form', () => {
    component.newTemplate();
    expect(component.formValid).toBe(false);

    component.onFormValueChange({ name: 'Neu', bodyHtml: '' });
    expect(component.formValid).toBe(false);

    component.onFormValueChange({ name: 'Neu', bodyHtml: '<p>x</p>' });
    expect(component.formValid).toBe(true);
  });

  it('delete calls service delete', () => {
    component.delete(service.templates[0]);

    expect(service.deleteCalls).toEqual(['t1']);
  });
});
