# language: de
@spec-01 @req-1.7
Funktionalität: Schema nur über versionierte Migrationen

  Szenario: Hibernate prüft das Schema nur, Liquibase führt die Changelogs
    Dann ist die Schema-Strategie von Hibernate «validate»
    Und Liquibase verwendet den Changelog «db/public/changelog.xml»
    Und der Changelog «db/mandant/changelog.xml» liegt im Artefakt
