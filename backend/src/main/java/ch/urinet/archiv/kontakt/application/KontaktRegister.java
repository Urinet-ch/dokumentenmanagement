package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driven Port: Kontakte im Schema des Mandanten der Anfrage bzw. des Mandanten-Kontexts (nie aus Benutzereingaben,
 * tech.md Regel 1). Die Namensähnlichkeit rechnet die Datenbank ({@code pg_trgm}).
 */
public interface KontaktRegister {

    /**
     * Suche für die Partnerauswahl: ähnliche oder enthaltene Namen, die besten zuerst; ohne Suchtext nach Name. Nur
     * aktive Kontakte (Req 17.12).
     */
    List<Kontakt> suchen(String nameNormalisiert, int anzahl);

    /** Liste der Kontakte mit Gesamtzahl (Req 17.1, 17.2); nur in einer Transaktion (Namensähnlichkeit). */
    KontaktSeite liste(KontaktSuche suche);

    /** Auch inaktive Kontakte. */
    Optional<Kontakt> finden(UUID id);

    /**
     * Wie {@link #finden(UUID)}, aber für das Setzen eines Partners an einem Dokument: hält die Zeile bis zum Ende der
     * Transaktion unter {@code for share}, damit ein paralleles Zusammenführen den Kontakt nicht zwischen Prüfung und
     * Schreiben inaktiv setzt (Req 17.12). Wartet höchstens {@code archiv.import.sperre-wartezeit}, sonst
     * {@code STATUS_KONFLIKT}. Ohne Sperrmöglichkeit gilt {@link #finden(UUID)}.
     */
    default Optional<Kontakt> fuerZuordnung(UUID id) {
        return finden(id);
    }

    /**
     * Aktiver Kontakt mit der ID aus 21.CRM ({@code crm_id} ist eindeutig), für die Generator-Metadaten (Req 11.7);
     * die ID eines inaktiven gilt als unbekannt (Req 17.12).
     */
    Optional<Kontakt> mitCrmId(String crmId);

    /** Die höchstens {@code anzahl} Kontakte, die in {@code ziel} zusammengeführt wurden, die neuesten zuerst. */
    List<Kontakt> zusammengefuehrtAus(UUID ziel, int anzahl);

    /**
     * Sperrt die Kontakte bis zum Ende der Transaktion ({@code for update}) in der Reihenfolge ihrer IDs, damit zwei
     * gegenläufige Aufrufe nicht verklemmen; unbekannte fehlen. Wartet höchstens {@code archiv.import.sperre-wartezeit}
     * (z. B. auf eine laufende CSV-Übernahme), sonst {@code STATUS_KONFLIKT}.
     */
    List<Kontakt> sperren(Collection<UUID> ids);

    /**
     * Setzt den Status (aktiv: {@code inaktiv_seit} und {@code zusammengefuehrt_in} leer; inaktiv: {@code inaktiv_seit}
     * jetzt, {@code zusammengefuehrtIn} oder leer) und erhöht die Version; nur am gesperrten Kontakt.
     */
    Kontakt statusSetzen(UUID id, boolean aktiv, UUID zusammengefuehrtIn);

    /** Die Kontakte mit diesen IDs (unbekannte fehlen), in beliebiger Reihenfolge. */
    List<Kontakt> finden(Collection<UUID> ids);

    /** {@code angaben} sind bereinigt. */
    Kontakt anlegen(KontaktAngaben angaben, String nameNormalisiert, String quelle);

    /** Ändert nur bei passender Version; leer, wenn die Version nicht (mehr) passt oder es den Kontakt nicht gibt. */
    Optional<Kontakt> aendern(UUID id, int version, KontaktAngaben angaben, String nameNormalisiert);

    /** Partner-Abgleich (Req 7.4): nur aktive Kontakte (Req 17.12), wie {@link #mitUid} und {@link #mitIban}. */
    Set<UUID> mitEmail(String email);

    Set<UUID> mitUid(String uid);

    Set<UUID> mitIban(String iban);

    /** Die {@code anzahl} ähnlichsten aktiven Kontakte mit Ähnlichkeit ab {@code untergrenze}, absteigend. */
    List<PartnerKandidat> aehnlichste(String nameNormalisiert, BigDecimal untergrenze, int anzahl);

    /**
     * Abgleich des CSV-Imports (Req 13.4): alle Kontakte mit einer der {@code crmIds}, einer der {@code emails} (ohne
     * Gross-/Kleinschreibung) oder einem der Paare {@code (namen[i], plz[i])} auf {@code (name_normalisiert, plz)},
     * je exakt, auch inaktive (Req 17.13 wertet sie). {@code namen} und {@code plz} sind gleich lang. Nur in einer
     * Transaktion.
     */
    List<KontaktTreffer> mitSchluesseln(Collection<String> crmIds, Collection<String> emails, List<String> namen,
                                        List<String> plz);

    /**
     * Je Name die höchstens {@code anzahl} ähnlichsten aktiven Kontakte mit Ähnlichkeit ab {@code untergrenze}, absteigend;
     * Namen ohne Treffer fehlen. Nur in einer Transaktion (wie {@link #aehnlichste}).
     */
    Map<String, List<UUID>> aehnlichsteJe(Collection<String> namen, BigDecimal untergrenze, int anzahl);
}
