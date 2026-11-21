import { Routes } from '@angular/router';

// Pfade der App für die Anleitung (Spec 07, Req 7.2, 7.4): nur, was eine Route der App ist. «**» zählt nicht, sonst
// wäre jeder Pfad bekannt; Parameter (z. B. «:id») passen auf jedes Segment.

function routenSegmente(routes: Routes, basis: readonly string[] = []): string[][] {
  return routes.flatMap((route) => {
    if (route.path === undefined || route.path.includes('**')) return [];
    const segmente = [...basis, ...route.path.split('/').filter((segment) => segment !== '')];
    return [segmente, ...routenSegmente(route.children ?? [], segmente)];
  });
}

function istSegment(segment: string): boolean {
  if (segment === '') return false;
  for (const zeichen of segment) {
    if (!((zeichen >= 'a' && zeichen <= 'z') || (zeichen >= '0' && zeichen <= '9') || zeichen === '-')) return false;
  }
  return true;
}

/** Segmente eines Pfads der App («/», Kleinbuchstaben, Ziffern, Bindestrich), sonst null (z. B. «//host», «/a?b»). */
function segmente(pfad: string): string[] | null {
  if (!pfad.startsWith('/')) return null;
  if (pfad === '/') return [];
  const teile = pfad.slice(1).split('/');
  return teile.every(istSegment) ? teile : null;
}

function passt(route: readonly string[], pfad: readonly string[]): boolean {
  return pfad.every((segment, i) => route[i] === segment || (route[i]?.startsWith(':') ?? false));
}

/** Der Pfad ist genau eine Route der App (Ziel eines Links in der Anleitung). */
export function istAppPfad(pfad: string, routes: Routes): boolean {
  const teile = segmente(pfad);
  return teile !== null && routenSegmente(routes).some((route) => route.length === teile.length && passt(route, teile));
}

/** Der Pfad ist eine Route der App oder ihr Anfang (Präfix für die Kontext-Zuordnung, z. B. «/dokument»). */
export function istAppPfadPraefix(pfad: string, routes: Routes): boolean {
  const teile = segmente(pfad);
  return teile !== null && routenSegmente(routes).some((route) => route.length >= teile.length && passt(route, teile));
}
