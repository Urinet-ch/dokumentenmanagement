package ch.urinet.archiv.mandant.application;

/**
 * Driven Port: prüft ein Mandanten-Schema gegen das Mapping der Entitäten (Hibernate {@code validate}, Req 1.7);
 * wirft {@link MandantFehler} mit Grund {@code SCHEMA_ABWEICHUNG}.
 */
public interface MandantSchemaPruefung {

    void pruefen(String schema);
}
