package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.Verkaufschance;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driven Port: Schreiben der Übernahme des CSV-Imports (Aufgabe 4.3; Req 13.6, 13.7, 13.9) im Schema des Mandanten der
 * Anfrage bzw. des Mandanten-Kontexts (tech.md Regel 1). Alle Methoden nur in einer Transaktion; gelöscht wird nie.
 */
public interface KontaktUebernahme {

    /** Quelle eines aus dem CSV-Import angelegten Kontakts. */
    String QUELLE_CSV_IMPORT = "CSV_IMPORT";

    /** Sperrt den Kontakt bis zum Ende der Transaktion; leer, wenn es ihn nicht gibt (auch inaktive). */
    Optional<Kontakt> sperren(UUID id);

    /**
     * Legt einen Kontakt mit Quelle {@link #QUELLE_CSV_IMPORT} an ({@code angaben} bereinigt); leer ohne Änderung, wenn
     * es die {@code crmId} schon gibt.
     */
    Optional<UUID> anlegen(String crmId, KontaktAngaben angaben, String nameNormalisiert);

    /**
     * Schreibt die Felder ({@code angaben} bereinigt), erhöht die Version; {@code crmId} setzt es nur, wenn der Kontakt
     * noch keine hat und kein anderer Kontakt sie trägt. Hebt die Kennzeichnung «nicht mehr im CRM-Export» nicht auf.
     */
    void aktualisieren(UUID id, String crmId, KontaktAngaben angaben, String nameNormalisiert);

    /**
     * Vollexport (Req 13.7): Kennzeichnet jeden aktiven Kontakt mit {@code crm_id}, die nicht unter {@code imExport}
     * ist, als «nicht mehr im CRM-Export» und hebt die Kennzeichnung für die übrigen aktiven auf; inaktive bleiben, wie
     * sie sind (Req 17.14). Liefert die Anzahl neu gekennzeichneter Kontakte.
     */
    int exportAbgleichen(Collection<String> imExport);

    /** Die inaktiven unter den Kontakten mit diesen IDs (Req 17.14). */
    Set<UUID> inaktive(Collection<UUID> ids);

    /**
     * Kontakt-IDs der aktiven Kontakte je {@code crm_id} (Verkaufschancen, Req 13.9); IDs ohne aktiven Kontakt fehlen,
     * weil ein inaktiver kein Ziel neuer Zuordnungen ist (Req 17.12).
     */
    Map<String, UUID> kontakteMitCrmId(Collection<String> crmIds);

    /** Verkaufschancen je {@code crm_id}; IDs ohne Verkaufschance fehlen. */
    Map<String, Verkaufschance> verkaufschancen(Collection<String> crmIds);

    /**
     * Legt die Verkaufschance an oder aktualisiert die mit derselben {@code crm_id}: leere Werte ({@code null})
     * überschreiben nichts. Ohne {@code crm_id} immer eine neue. Liefert {@code true}, wenn sie neu ist.
     */
    boolean verkaufschanceSpeichern(Verkaufschance chance);
}
