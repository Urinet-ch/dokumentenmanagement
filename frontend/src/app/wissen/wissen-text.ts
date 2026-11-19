export interface Textteil {
  art: string;
  text?: string;
  pfad?: string;
  quelle?: string;
  kinder?: Textteil[];
}

const erlaubteElemente = new Set([
  'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'em', 'strong', 'code', 'br',
]);
const verworfeneElemente = new Set(['script', 'style', 'img', 'iframe', 'object', 'svg']);

export function wissenText(html: string): Textteil[] {
  const dokument = new DOMParser().parseFromString(html, 'text/html');

  function lesen(node: Node): Textteil[] {
    if (node.nodeType === Node.TEXT_NODE) {
      return node.textContent ? [{ art: 'text', text: node.textContent }] : [];
    }
    if (!(node instanceof Element) || node.hasAttribute('data-footnotes')) return [];
    const art = node.tagName.toLowerCase();
    if (verworfeneElemente.has(art)) return [];
    if (art === 'a') {
      const href = node.getAttribute('href');
      const text = node.textContent ?? '';
      if (node.hasAttribute('data-footnote-ref') && href?.startsWith('#fn-')) {
        return [{ art: 'quelle', text: `[${text}]`, quelle: href.slice(4) }];
      }
      if (href && /^\/(?:ueberblick|(?:quellen|konzepte|akteure|projekte|analysen)\/[a-z0-9-]+)$/.test(href)) {
        return [{ art: 'link', text, pfad: `/wissen${href}` }];
      }
      return [{ art: 'text', text }];
    }
    const kinder = Array.from(node.childNodes).flatMap(lesen);
    return erlaubteElemente.has(art) ? [{ art, kinder }] : kinder;
  }

  return Array.from(dokument.body.childNodes).flatMap(lesen);
}
