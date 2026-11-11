package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;

import java.math.BigDecimal;
import java.util.List;
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

    /** {@code angaben} sind bereinigt. */
    Kontakt anlegen(KontaktAngaben angaben, String nameNormalisiert, String quelle);

    /** Ändert nur bei passender Version; leer, wenn die Version nicht (mehr) passt oder es den Kontakt nicht gibt. */
    Optional<Kontakt> aendern(UUID id, int version, KontaktAngaben angaben, String nameNormalisiert);

    Set<UUID> mitEmail(String email);

    Set<UUID> mitUid(String uid);

    Set<UUID> mitIban(String iban);

    /** Die {@code anzahl} ähnlichsten Kontakte mit Ähnlichkeit ab {@code untergrenze}, absteigend. */
    List<PartnerKandidat> aehnlichste(String nameNormalisiert, BigDecimal untergrenze, int anzahl);
}
