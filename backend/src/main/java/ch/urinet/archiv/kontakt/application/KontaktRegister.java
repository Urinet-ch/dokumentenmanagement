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

    /** Suche für die Partnerauswahl: ähnliche oder enthaltene Namen, die besten zuerst; ohne Suchtext nach Name. */
    List<Kontakt> suchen(String nameNormalisiert, int anzahl);

    Optional<Kontakt> finden(UUID id);

    /** Kontakt mit der ID aus 21.CRM ({@code crm_id} ist eindeutig), für die Generator-Metadaten (Req 11.7). */
    Optional<Kontakt> mitCrmId(String crmId);

    /** Die Kontakte mit diesen IDs (unbekannte fehlen), in beliebiger Reihenfolge. */
    List<Kontakt> finden(Collection<UUID> ids);

    /** {@code angaben} sind bereinigt. */
    Kontakt anlegen(KontaktAngaben angaben, String nameNormalisiert, String quelle);

    /** Ändert nur bei passender Version; leer, wenn die Version nicht (mehr) passt oder es den Kontakt nicht gibt. */
    Optional<Kontakt> aendern(UUID id, int version, KontaktAngaben angaben, String nameNormalisiert);

    Set<UUID> mitEmail(String email);

    Set<UUID> mitUid(String uid);

    Set<UUID> mitIban(String iban);

    /** Die {@code anzahl} ähnlichsten Kontakte mit Ähnlichkeit ab {@code untergrenze}, absteigend. */
    List<PartnerKandidat> aehnlichste(String nameNormalisiert, BigDecimal untergrenze, int anzahl);

    /**
     * Abgleich des CSV-Imports (Req 13.4): alle Kontakte mit einer der {@code crmIds}, einer der {@code emails} (ohne
     * Gross-/Kleinschreibung) oder einem der Paare {@code (namen[i], plz[i])} auf {@code (name_normalisiert, plz)},
     * je exakt. {@code namen} und {@code plz} sind gleich lang. Nur in einer Transaktion.
     */
    List<KontaktTreffer> mitSchluesseln(Collection<String> crmIds, Collection<String> emails, List<String> namen,
                                        List<String> plz);

    /**
     * Je Name die höchstens {@code anzahl} ähnlichsten Kontakte mit Ähnlichkeit ab {@code untergrenze}, absteigend;
     * Namen ohne Treffer fehlen. Nur in einer Transaktion (wie {@link #aehnlichste}).
     */
    Map<String, List<UUID>> aehnlichsteJe(Collection<String> namen, BigDecimal untergrenze, int anzahl);
}
