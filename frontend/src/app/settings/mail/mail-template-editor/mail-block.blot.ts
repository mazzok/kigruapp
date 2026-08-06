import Quill from 'quill';

const BlockEmbed: any = Quill.import('blots/block/embed');

export interface MailBlockValue {
  blockType: string;
  config: Record<string, unknown>;
  summary: string;
}

/** Non-editable card that renders a configurable block (e.g. a Kochdienst table) in the editor. */
class MailBlockBlot extends BlockEmbed {
  static blotName = 'mail-block';
  static tagName = 'div';
  static className = 'mail-block';

  static create(value: MailBlockValue): HTMLElement {
    const node: HTMLElement = super.create();
    node.setAttribute('data-block-type', value.blockType);
    node.setAttribute('data-config', JSON.stringify(value.config));
    node.setAttribute('contenteditable', 'false');

    const summary = document.createElement('span');
    summary.className = 'mail-block-summary';
    summary.textContent = value.summary;

    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'mail-block-edit-btn';
    editBtn.setAttribute('aria-label', 'Baustein bearbeiten');
    editBtn.textContent = '✎';

    node.appendChild(summary);
    node.appendChild(editBtn);
    return node;
  }

  static value(node: HTMLElement): MailBlockValue {
    return {
      blockType: node.getAttribute('data-block-type') ?? '',
      config: JSON.parse(node.getAttribute('data-config') ?? '{}'),
      summary: node.querySelector('.mail-block-summary')?.textContent ?? '',
    };
  }
}

let registered = false;

export function registerMailBlockBlot(): void {
  if (registered) {
    return;
  }
  registered = true;
  Quill.register(MailBlockBlot);
}
