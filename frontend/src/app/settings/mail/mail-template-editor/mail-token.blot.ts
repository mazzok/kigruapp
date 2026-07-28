import Quill from 'quill';

const Embed: any = Quill.import('blots/embed');

/** Inline, non-editable pill that renders a placeholder in the editor. */
class MailTokenBlot extends Embed {
  static blotName = 'mail-token';
  static tagName = 'span';
  static className = 'mail-token';

  static create(value: { token: string; label: string }): HTMLElement {
    const node: HTMLElement = super.create();
    node.setAttribute('data-token', value.token);
    node.setAttribute('data-label', value.label);
    node.textContent = value.label;
    return node;
  }

  static value(node: HTMLElement): { token: string; label: string } {
    return {
      token: node.getAttribute('data-token') ?? '',
      label: node.getAttribute('data-label') ?? node.textContent ?? '',
    };
  }
}

let registered = false;

export function registerMailTokenBlot(): void {
  if (registered) {
    return;
  }
  registered = true;
  Quill.register(MailTokenBlot);
}
