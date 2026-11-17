# language: de
@spec-04
Funktionalität: Wissensbasisdatenmodell

  @req-4.1 @req-4.2
  Szenario: Mandantenmigration legt Seiten und vollständige Versionen an
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann enthält das Mandantenschema alle Wissensbasistabellen und den Changeset «043-wissen-datenmodell»
    Und speichert eine Seitenversion Fassung, Akteure, Zeit und Entwurf und lehnt eine doppelte Versionsnummer ab

  @req-4.3
  Szenario: Wissensversionen und Log sind unveränderbar
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann lehnen Wissensversionen, ihre Quellenbezüge und das Log UPDATE, DELETE und TRUNCATE ab

  @req-4.6
  Szenario: Manifest hasht Wissensdaten ohne Inhalte zu protokollieren
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann enthält das Manifest Hashes für Wissensversionen und Log, ohne Seiteninhalte zu protokollieren
