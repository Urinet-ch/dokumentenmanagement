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

    /**
     * Zur Aktion einer Kennung im Mandanten der Anfrage; fügt nur ein, wenn sich etwas geändert hat. Die geteilte
     * Sperre hält einen Austritt oder Widerruf derselben Kennung an, bis die Aktion festgeschrieben ist; nur wer
     * einfügt, nimmt zusätzlich die exklusive Einfüge-Sperre und liest danach neu, damit gleichzeitige erste Aktionen
     * genau eine Zeile schreiben.
     * <p>
     * Ein {@code VERWEIGERT} sperrt nicht: Er entsteht in einer eigenen Transaktion ({@code REQUIRES_NEW}), oft während
     * die Transaktion der Anfrage derselben Kennung ausgesetzt ist und ihre Sperren hält (z. B. A8 bei
     * {@code PATCH /dokumente/{id}}); auf sie zu warten, verklemmte die Anfrage, ohne dass PostgreSQL es erkennt. Die
     * ausgesetzte Transaktion wird mit der Verweigerung zurückgerollt, ihre Zeile entfällt also. Übrig bleibt nur ein
     * seltenes Duplikat, wenn eine erste Verweigerung gleichzeitig mit einer anderen ersten Aktion einfügt.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void nachfuehren(Benutzerangaben angaben) {
        String benutzer = angaben.benutzer();
        if (angaben.verweigert()) {
            angaben.neueZeile(verzeichnis.letzter(Optional.empty(), benutzer)).ifPresent(zeile -> einfuegen(benutzer,
                    zeile));
            return;
        }
        verzeichnis.sperren(Optional.empty(), benutzer, BenutzerVerzeichnis.Sperre.AKTION);
        if (angaben.neueZeile(verzeichnis.letzter(Optional.empty(), benutzer)).isEmpty()) {
            return;
        }
        verzeichnis.sperren(Optional.empty(), benutzer, BenutzerVerzeichnis.Sperre.EINFUEGEN);
        angaben.neueZeile(verzeichnis.letzter(Optional.empty(), benutzer)).ifPresent(zeile -> einfuegen(benutzer,
                zeile));
    }

    private void einfuegen(String benutzer, Benutzerangaben.NeueZeile zeile) {
        verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(benutzer, zeile.name(), zeile.rolle(),
                Optional.empty(), false, VerzeichnisEintrag.Anlass.AKTION, Optional.empty()));
    }

    /**
     * Widerruf eines API-Tokens (A14): neue Zeile mit Ende «jetzt», auch für ein nie genutztes Token (gültig ab
     * {@code erstellt}). Ist die letzte Zeile schon beendet, bleibt es dabei.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void tokenWiderrufen(MandantCode mandant, String benutzer, String name, Instant erstellt,
                                Optional<String> erfasstVon) {
        Objects.requireNonNull(mandant, "mandant");
        verzeichnis.sperren(Optional.of(mandant), benutzer, BenutzerVerzeichnis.Sperre.ABSCHLUSS);
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
