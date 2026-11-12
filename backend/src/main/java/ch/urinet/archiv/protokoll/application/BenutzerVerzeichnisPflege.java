package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.Benutzerangaben;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Führt das Benutzerverzeichnis nach (Req 10.10; design.md «Umsetzung 5.1»): bei jeder Aktion im Zugriffsprotokoll
 * (erste Aktion, geänderter Name oder Rolle, wieder aktiv) und beim Widerruf eines API-Tokens. Läuft in der Transaktion
 * des Aufrufers, damit Verzeichnis und Protokoll zusammen festgeschrieben oder zurückgerollt werden.
 */
@ApplicationScoped
public class BenutzerVerzeichnisPflege {

    private final BenutzerVerzeichnis verzeichnis;

    public BenutzerVerzeichnisPflege(BenutzerVerzeichnis verzeichnis) {
        this.verzeichnis = verzeichnis;
    }

    /** Zur Aktion einer Kennung im Mandanten der Anfrage; fügt nur ein, wenn sich etwas geändert hat. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void nachfuehren(Benutzerangaben angaben) {
        angaben.neueZeile(verzeichnis.letzter(Optional.empty(), angaben.benutzer()))
                .ifPresent(zeile -> verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(
                        angaben.benutzer(), zeile.name(), zeile.rolle(), Optional.empty(), false,
                        VerzeichnisEintrag.Anlass.AKTION, Optional.empty())));
    }

    /**
     * Widerruf eines API-Tokens (A14): neue Zeile mit Ende «jetzt», auch für ein nie genutztes Token (gültig ab
     * {@code erstellt}). Ist die letzte Zeile schon beendet, bleibt es dabei.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void tokenWiderrufen(MandantCode mandant, String benutzer, String name, Instant erstellt,
                                Optional<String> erfasstVon) {
        Objects.requireNonNull(mandant, "mandant");
        Optional<VerzeichnisEintrag> letzter = verzeichnis.letzter(Optional.of(mandant), benutzer);
        if (letzter.filter(VerzeichnisEintrag::beendet).isPresent()) {
            return;
        }
        Instant gueltigVon = letzter.map(VerzeichnisEintrag::gueltigVon).orElse(erstellt);
        verzeichnis.einfuegen(Optional.of(mandant), new BenutzerVerzeichnis.Neu(benutzer, Optional.ofNullable(name),
                Optional.of(VerzeichnisEintrag.ROLLE_API_TOKEN), Optional.of(gueltigVon), true,
                VerzeichnisEintrag.Anlass.WIDERRUF, erfasstVon));
    }
}
