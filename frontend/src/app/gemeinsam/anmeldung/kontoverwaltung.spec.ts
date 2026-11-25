import { UMGEBUNG } from '../../../umgebung/umgebung';
import { kontoverwaltungUrl } from './kontoverwaltung';

describe('kontoverwaltungUrl (Req 10.1)', () => {
  it('leitet die Adresse aus dem Aussteller der OIDC-Konfiguration ab', () => {
    expect(kontoverwaltungUrl()).toBe(`${UMGEBUNG.oidc.aussteller}/account`);
  });

  it('verdoppelt keinen Schrägstrich am Ende des Ausstellers', () => {
    expect(kontoverwaltungUrl('https://sso.example/realms/r/')).toBe('https://sso.example/realms/r/account');
  });
});
