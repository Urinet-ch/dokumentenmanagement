import { UMGEBUNG } from '../../../umgebung/umgebung';
import { kontoverwaltungUrl } from './kontoverwaltung';

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
