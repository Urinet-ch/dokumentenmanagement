# language: de
@spec-04
Funktionalität: Wissensbasisdatenmodell

  @req-4.1 @req-4.2
  Szenario: Mandantenmigration legt Seiten und vollständige Versionen an
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann enthält das Mandantenschema alle Wissensbasistabellen und den Changeset «043-wissen-datenmodell»

  @req-4.3
  Szenario: Wissensversionen und Log sind unveränderbar
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann lehnen Wissensversionen und Log UPDATE und DELETE ab

  @req-4.6 @req-13.9
  Szenario: Manifest hasht Wissensdaten ohne Inhalte zu protokollieren
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann enthält das Manifest Hashes für Wissensversionen und Log, ohne Seiteninhalte zu protokollieren

  @req-4.7
  Szenario: Restore-Roundtrip vergleicht Seiten- und Versionszahlen
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann bleiben die Seiten- und Versionszahlen beim Restore-Roundtrip unverändert
