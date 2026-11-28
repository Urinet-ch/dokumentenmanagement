package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ordnet Uploads einem Quelllauf zu (Spec 09, 10.3, 10.4, 10.7; design.md Abschnitt 5): Formularfeld {@code quelllauf}
 * (ID) oder {@code quelle} (Code); mit dem Code nimmt der Server den laufenden Lauf der Quelle oder legt je Tag einen
 * Lauf {@code AUTOMATISCH} an. Ein Trockenlauf nimmt keine Uploads (10.5).
 */
@ApplicationScoped
public class QuellLaufZuordnung {

    static final ZoneId ZONE = ZoneId.of("Europe/Zurich");

    /** Lauf, dem die Uploads der Anfrage zugeordnet werden, und die Quelle für {@code dokument.quelle}. */
    public record Ziel(UUID laufId, Optional<String> dokumentQuelle) {
    }

    /** Ergebnis einer Datei des Uploads. */
    public enum Ergebnis {
        NEU, DUBLETTE, ABGELEHNT
    }

    /** Eine Datei des Uploads mit dem neuen Dokument (nur bei {@link Ergebnis#NEU}). */
    public record Eintrag(Ergebnis ergebnis, Optional<UUID> dokumentId) {
    }

    private final QuellenRegister register;
    private final Clock uhr;

    @Inject
    public QuellLaufZuordnung(QuellenRegister register) {
        this(register, Clock.systemUTC());
    }

    QuellLaufZuordnung(QuellenRegister register, Clock uhr) {
        this.register = register;
        this.uhr = uhr;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Ziel> bestimmen(String quelleCode, String laufId) {
        boolean mitCode = quelleCode != null && !quelleCode.isBlank();
        boolean mitLauf = laufId != null && !laufId.isBlank();
        if (!mitCode && !mitLauf) {
            return Optional.empty();
        }
        if (mitLauf) {
            QuellLauf lauf = register.lauf(uuid(laufId), false)
                    .orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
            if (mitCode && !lauf.quelleCode().equals(quelleCode.strip())) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            if (!lauf.nimmtUploadsAuf()) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            return Optional.of(new Ziel(lauf.id(), quelle(lauf.quelleCode()).typ().dokumentQuelle()));
        }
        Quelle quelle = register.finde(quelleCode.strip(), true)
                .orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
        Optional<QuellLauf> laufend = register.laufenderLauf(quelle.id());
        LocalDate heute = LocalDate.now(uhr.withZone(ZONE));
        if (laufend.isPresent()) {
            QuellLauf lauf = laufend.get();
            if (lauf.art() == LaufArt.TROCKEN) {
                throw new FachFehler(Fehlercode.QUELLE_LAEUFT);
            }
            if (lauf.art() != LaufArt.AUTOMATISCH || LocalDate.ofInstant(lauf.gestartet(), ZONE).equals(heute)) {
                return Optional.of(new Ziel(lauf.id(), quelle.typ().dokumentQuelle()));
            }
            register.laufBeenden(lauf.id(), QuellLaufStatus.FERTIG, Optional.empty(), 0, List.of());
        }
        QuellLauf neu = register.laufAnlegen(quelle.id(), LaufArt.AUTOMATISCH, Optional.empty());
        return Optional.of(new Ziel(neu.id(), quelle.typ().dokumentQuelle()));
    }

    /** Zählt die Dateien des Uploads im Lauf und verweist jedes neue Dokument auf ihn (10.3, 10.4). */
    @Transactional(Transactional.TxType.MANDATORY)
    public void verbuchen(Ziel ziel, List<Eintrag> eintraege) {
        int neu = 0;
        int dublette = 0;
        int fehler = 0;
        for (Eintrag eintrag : eintraege) {
            switch (eintrag.ergebnis()) {
                case NEU -> {
                    neu++;
                    eintrag.dokumentId().ifPresent(id -> register.dokumentZuordnen(id, ziel.laufId(),
                            ziel.dokumentQuelle()));
                }
                case DUBLETTE -> dublette++;
                case ABGELEHNT -> fehler++;
            }
        }
        register.verbuchen(ziel.laufId(), neu, dublette, fehler);
    }

    private Quelle quelle(String code) {
        return register.finde(code, false).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
