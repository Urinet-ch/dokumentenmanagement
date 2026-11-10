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
