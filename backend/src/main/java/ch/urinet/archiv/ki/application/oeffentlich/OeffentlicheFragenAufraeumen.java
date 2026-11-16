package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * Löschung der gespeicherten Fragen des öffentlichen Chatbots nach der Aufbewahrung (Spec 03, Req 6.5: 90 Tage;
 * {@code archiv.webseite.chat.aufbewahrung}, Standard {@code P90D}). Nicht in einer laufenden Transaktion aufrufen.
 */
@ApplicationScoped
public class OeffentlicheFragenAufraeumen {

    private final MandantKontext kontext;
    private final OeffentlicheFragenSpeicher speicher;
    private final Period aufbewahrung;

    @Inject
    public OeffentlicheFragenAufraeumen(MandantKontext kontext, OeffentlicheFragenSpeicher speicher,
                                        @ConfigProperty(name = "archiv.webseite.chat.aufbewahrung") String aufbewahrung) {
        this.kontext = kontext;
        this.speicher = speicher;
        this.aufbewahrung = Period.parse(aufbewahrung);
        if (this.aufbewahrung.isNegative() || this.aufbewahrung.isZero()) {
            throw new IllegalArgumentException("archiv.webseite.chat.aufbewahrung muss positiv sein");
        }
    }

    /** Grenze für {@code jetzt}: was davor liegt, wird gelöscht. */
    public Instant grenze(Instant jetzt) {
        return jetzt.atOffset(ZoneOffset.UTC).minus(aufbewahrung).toInstant();
    }

    /** Anzahl der gelöschten Fragen des Mandanten. */
    public int aufraeumen(MandantCode mandant, Instant jetzt) {
        Instant grenze = grenze(jetzt);
        return kontext.mit(mandant, () -> speicher.loeschenVor(grenze));
    }
}
