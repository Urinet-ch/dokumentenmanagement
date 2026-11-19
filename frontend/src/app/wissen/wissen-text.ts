export interface Textteil {
  text: string;
  pfad?: string;
  quelle?: string;
}

export function wissenText(html: string): Textteil[][] {
  const dokument = new DOMParser().parseFromString(html, 'text/html');
  const abschnitte: Textteil[][] = [];
  const bloecke = new Set(['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'LI', 'BLOCKQUOTE', 'PRE']);

  function lesen(node: Node, teile: Textteil[]): void {
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.textContent) teile.push({ text: node.textContent });
      return;
    }
    if (!(node instanceof Element)) return;
    if (node.tagName === 'A') {
      const href = node.getAttribute('href');
      const text = node.textContent ?? '';
      if (node.hasAttribute('data-footnote-ref') && href?.startsWith('#fn-')) {
        teile.push({ text: `[${text}]`, quelle: href.slice(4) });
        return;
      }
      if (href && /^\/wissen\/(?:ueberblick|(?:quellen|konzepte|akteure|projekte|analysen)\/[a-z0-9-]+)$/.test(href)) {
        teile.push({ text, pfad: href });
      } else {
        teile.push({ text });
      }
      return;
    }
    if (node.tagName === 'BR') {
      teile.push({ text: '\n' });
      return;
    }
    if (['SCRIPT', 'STYLE', 'IMG', 'IFRAME', 'OBJECT', 'SVG'].includes(node.tagName)) return;
    for (const kind of node.childNodes) lesen(kind, teile);
  }

  function sammeln(node: Node): void {
    if (node instanceof Element && node.hasAttribute('data-footnotes')) return;
    if (node instanceof Element && bloecke.has(node.tagName)) {
      const teile: Textteil[] = [];
      lesen(node, teile);
      if (teile.some((teil) => teil.text.trim())) abschnitte.push(teile);
      return;
    }
    for (const kind of node.childNodes) sammeln(kind);
  }
  sammeln(dokument.body);
  return abschnitte;
}
