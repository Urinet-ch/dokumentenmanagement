import { UMGEBUNG } from '../../../umgebung/umgebung';
import { kontoverwaltungEinstiege, kontoverwaltungUrl } from './kontoverwaltung';

describe('kontoverwaltungEinstiege (Spec 06, Req 11.4)', () => {
  it('führt Passkeys, Passwort und Einmalcode auf «Anmeldung», die Sitzungen auf «Geräteaktivität»', () => {
    const basis = `${UMGEBUNG.oidc.aussteller}/account`;
    expect(kontoverwaltungEinstiege()).toEqual({
      passkeys: `${basis}/account-security/signing-in`,
      passwort: `${basis}/account-security/signing-in`,
      einmalcode: `${basis}/account-security/signing-in`,
      geraete: `${basis}/account-security/device-activity`,
    });
  });

  it('gibt ohne Kontoverwaltung keine Einstiege', () => {
    expect(kontoverwaltungEinstiege('')).toBeNull();
    expect(kontoverwaltungEinstiege('http://sso.example/realms/r')).toBeNull();
  });
});

describe('kontoverwaltungUrl (Req 10.1)', () => {
  it('leitet die Adresse aus dem Aussteller der OIDC-Konfiguration ab', () => {
    expect(kontoverwaltungUrl()).toBe(`${UMGEBUNG.oidc.aussteller}/account`);
  });

  it('verdoppelt keinen Schrägstrich am Ende des Ausstellers', () => {
    expect(kontoverwaltungUrl('https://sso.example/realms/r/')).toBe('https://sso.example/realms/r/account');
  });

  it('erzeugt bei leerem oder nicht-https-Aussteller keinen Link', () => {
    expect(kontoverwaltungUrl('')).toBeNull();
    expect(kontoverwaltungUrl('/realms/r')).toBeNull();
    expect(kontoverwaltungUrl('http://sso.example/realms/r')).toBeNull();
    expect(kontoverwaltungUrl('javascript:alert(1)')).toBeNull();
  });

  it('erlaubt http://localhost für die Entwicklung', () => {
    expect(kontoverwaltungUrl('http://localhost:8180/realms/r')).toBe('http://localhost:8180/realms/r/account');
  });
});
