// Hilfsfunktionen für PKCE (RFC 7636) und das Lesen von JWT-Nutzlasten. Die Signatur prüft das Backend (Req 2.1);
// die Oberfläche liest die Claims nur, um Menüpunkte auszublenden.

export function base64url(bytes: Uint8Array): string {
  let text = '';
  for (const byte of bytes) {
    text += String.fromCharCode(byte);
  }
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function zufall(anzahlBytes = 32): string {
  const bytes = new Uint8Array(anzahlBytes);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

export async function codeChallengeS256(verifier: string): Promise<string> {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64url(new Uint8Array(hash));
}

export function jwtNutzlast(token: string | undefined): Record<string, unknown> | null {
  const teil = token?.split('.')[1];
  if (!teil) {
    return null;
  }
  try {
    const base64 = teil.replace(/-/g, '+').replace(/_/g, '/');
    const binaer = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
    const bytes = Uint8Array.from(binaer, (zeichen) => zeichen.charCodeAt(0));
    const nutzlast: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof nutzlast === 'object' && nutzlast !== null ? (nutzlast as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
