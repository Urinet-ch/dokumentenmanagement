# language: de
@spec-01 @req-1.7
Funktionalität: Schema nur über versionierte Migrationen

  Szenario: Hibernate prüft das Schema nur, Liquibase führt die Changelogs
    Dann ist die Schema-Strategie von Hibernate «validate»
    Und Liquibase verwendet den Changelog «db/public/changelog.xml»
    Und der Changelog «db/mandant/changelog.xml» liegt im Artefakt

  Szenario: Liquibase legt die Tabellen an, Hibernate prüft nur und erzeugt nichts
    Dann hat Liquibase im Schema «public» die Changesets «001-mandant», «002-auftrag» und «003-api-client» ausgeführt
    Und das Schema «public» enthält nur die Tabellen «api_client», «auftrag», «databasechangelog», «databasechangeloglock» und «mandant»
    Und das Schema «public» enthält keine Sequenz ausser «auftrag_id_seq»
    Und Hibernate bildet die Entität «MandantEintrag» auf die Tabelle «mandant» ab

  Szenario: Nach der Migration prüft das Archiv jedes Mandanten-Schema gegen das Mapping
    Angenommen im Register steht der aktive Mandant «probe-pruefung» mit leerem Schema
    Wenn das Archiv die aktiven Mandanten migriert
    Dann ist das Schema «m_probe_pruefung» migriert und ohne Abweichung geprüft

  Szenario: Weicht ein Mandanten-Schema vom Mapping ab, bricht die API beim Start ab
    Angenommen im Register steht der aktive Mandant «probe-abweichung» mit leerem Schema
    Und das Archiv die aktiven Mandanten migriert
    Und im Schema «m_probe_abweichung» fehlt die Spalte «aktiv» der Tabelle «dokumentart»
    Wenn das Archiv die aktiven Mandanten migriert
    Dann bricht das Archiv mit «SCHEMA_ABWEICHUNG» ab

  Szenario: Der Worker migriert nicht, prüft aber jedes Mandanten-Schema
    Angenommen im Register steht der aktive Mandant «probe-worker» mit leerem Schema
    Wenn das Archiv die aktiven Mandanten nur prüft
    Dann bricht das Archiv mit «SCHEMA_ABWEICHUNG» ab
