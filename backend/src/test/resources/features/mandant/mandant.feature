# language: de
@spec-01
Funktionalität: Mandanten anlegen und migrieren

  @req-1.1 @req-1.5
  Szenario: Zwei Mandanten erhalten getrennte Schemas und Buckets
    Wenn der Betreiber die Mandanten «urinet» und «probe» anlegt
    Dann gibt es die Schemas «m_urinet» und «m_probe» je mit eigener Tabelle «dokumentart»
    Und eine Dokumentart im Schema «m_probe» ist im Schema «m_urinet» nicht sichtbar
    Und die Buckets «urinet-archiv-urinet» und «urinet-archiv-probe» haben Object Lock und Versionierung ohne Standard-Aufbewahrung
    Und ein Objekt im Bucket «urinet-archiv-urinet» fehlt im Bucket «urinet-archiv-probe»
    Und die Buckets «urinet-archiv-urinet» und «urinet-archiv-probe» tragen die Bucket Policy mit Deny auf «s3:BypassGovernanceRetention»

  @req-1.5
  Szenario: Der Seed des Mandanten wird ohne Entwurfsfelder eingespielt
    Wenn der Betreiber die Mandanten «urinet» und «probe» anlegt
    Dann enthält das Schema «m_urinet» genau die Dokumentarten aus «seed/urinet/dokumentarten.json»

  @req-1.5
  Szenario: Ohne Seed wird kein Mandant angelegt
    Wenn der Betreiber den Mandanten «ohne-seed» anlegt
    Dann lehnt das Archiv mit «SEED_FEHLT» ab
    Und es gibt weder den Registereintrag «ohne-seed» noch das Schema «m_ohne_seed»

  @req-1.5 @req-9.5
  Szenario: Ein ungültiger Seed bricht ab, bevor etwas angelegt ist
    Wenn der Betreiber den Mandanten «probe-seed-ungueltig» anlegt
    Dann lehnt das Archiv mit «SEED_UNGUELTIG» ab
    Und es gibt weder den Registereintrag «probe-seed-ungueltig» noch das Schema «m_probe_seed_ungueltig»

  @req-1.5
  Szenario: Einen Mandanten gibt es nur einmal
    Wenn der Betreiber die Mandanten «urinet» und «probe» anlegt
    Und der Betreiber den Mandanten «urinet» anlegt
    Dann lehnt das Archiv mit «MANDANT_EXISTIERT» ab

  @req-1.5
  Szenario: Beim Start bringt das Archiv alle aktiven Mandanten auf den aktuellen Stand
    Angenommen im Register steht der aktive Mandant «probe-migration» mit leerem Schema
    Wenn das Archiv die aktiven Mandanten migriert
    Dann gibt es das Schema «m_probe_migration» mit der Tabelle «dokumentart»

  @req-1.5
  Szenario: Fehlt das Schema eines aktiven Mandanten, legt der Start es nicht leer neu an
    Angenommen im Register steht der aktive Mandant «probe-ohne-schema» ohne Schema
    Wenn das Archiv die aktiven Mandanten migriert
    Dann bricht das Archiv mit «SCHEMA_FEHLT» ab
    Und es gibt das Schema «m_probe_ohne_schema» nicht

  @req-9.6
  Szenario: Ohne Angabe gilt das Geschäftsjahresende 12-31
    Wenn der Betreiber die Mandanten «urinet» und «probe» anlegt
    Dann führt das Register für «urinet» das Geschäftsjahresende «12-31»

  @req-9.6
  Szenario: Das Geschäftsjahresende ist je Mandant einstellbar
    Wenn der Betreiber den Mandanten «probe-gj» mit Geschäftsjahresende «06-30» anlegt
    Dann führt das Register für «probe-gj» das Geschäftsjahresende «06-30»

  @req-9.6
  Szenario: Ein ungültiges Geschäftsjahresende wird abgelehnt
    Wenn der Betreiber den Mandanten «probe-ungueltig» mit Geschäftsjahresende «02-30» anlegt
    Dann lehnt das Archiv mit «UNGUELTIG» ab
