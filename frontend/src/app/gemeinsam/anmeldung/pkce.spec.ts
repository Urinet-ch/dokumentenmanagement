import { base64url, codeChallengeS256, jwtNutzlast, zufall } from './pkce';

describe('PKCE (Req 2.1)', () => {
  it('berechnet die S256-Challenge nach RFC 7636, Anhang B', async () => {
    expect(await codeChallengeS256('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('erzeugt zufällige Werte ohne Base64-Sonderzeichen', () => {
    const a = zufall(48);
    expect(a).toMatch(/^[A-Za-z0-9_-]{64}$/);
    expect(zufall(48)).not.toBe(a);
    expect(base64url(new Uint8Array([251, 255]))).toBe('-_8');
  });

  it('liest die Nutzlast eines JWT, auch mit Umlauten', () => {
    const nutzlast = base64url(new TextEncoder().encode(JSON.stringify({ name: 'Jörg' })));
    expect(jwtNutzlast(`x.${nutzlast}.y`)).toEqual({ name: 'Jörg' });
    expect(jwtNutzlast('kein-token')).toBeNull();
    expect(jwtNutzlast(undefined)).toBeNull();
  });
});
