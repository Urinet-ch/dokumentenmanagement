# language: de
@spec-01 @google-oidc
Funktionalität: Anmeldung über den OIDC-Test-IdP

  @req-2.26
  Szenario: Ein unbekanntes Konto wird abgewiesen
    Angenommen ein unbekanntes Google-Testkonto wird angeboten
    Wenn das Konto sich über den OIDC-Test-IdP anmeldet
    Dann weist Keycloak das unbekannte Konto ab

  @req-2.26
  Szenario: Eine gleiche E-Mail-Adresse verknüpft kein Konto
    Angenommen ein bestehender Archivbenutzer hat nur dieselbe E-Mail-Adresse wie das Google-Testkonto
    Wenn das Konto sich über den OIDC-Test-IdP anmeldet
    Dann wird das Konto trotz gleicher E-Mail nicht automatisch verknüpft

  @req-2.25 @req-2.28
  Szenario: Eine verknüpfte Identität verlangt TOTP und erhält Rollen und Mandant nur aus Keycloak trotz feindlicher IdP-Claims
    Angenommen ein Archivbenutzer ist mit der Google-Identität verknüpft
    Wenn das Konto sich über den OIDC-Test-IdP anmeldet
    Und der verknüpfte Benutzer die Anmeldung mit TOTP abschliesst
    Dann der verknüpfte Benutzer erhält erst nach erfolgreichem TOTP einen Keycloak-Token

  @req-2.27
  Szenario: Verknüpfen und Lösen werden protokolliert und der Runbook-Schritt logout beendet die Sitzung
    Angenommen ein Archivbenutzer ist mit der Google-Identität verknüpft
    Wenn der verknüpfte Benutzer sich anmeldet, die Verknüpfung gelöst und der Benutzer per logout abgemeldet wird
    Dann stehen Verknüpfen und Lösen dieses Benutzers im Admin-Ereignisprotokoll und der logout beendet die Sitzung
