package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * Löschung nach der Aufbewahrung (Requirement 5.4; design.md «Vertrag Welle 7», Löschung): je Nachricht nach
 * {@code archiv.chat.aufbewahrung} (Standard 12 Monate ab {@code zeit}), danach Gespräche ohne Nachrichten. Nicht in
 * einer laufenden Transaktion aufrufen.
 */
@ApplicationScoped
public class ChatAufraeumen {

    private final MandantKontext kontext;
    private final ChatSpeicher speicher;
    private final Period aufbewahrung;

    @Inject
    public ChatAufraeumen(MandantKontext kontext, ChatSpeicher speicher,
                          @ConfigProperty(name = "archiv.chat.aufbewahrung") String aufbewahrung) {
        this.kontext = kontext;
        this.speicher = speicher;
        this.aufbewahrung = Period.parse(aufbewahrung);
        if (this.aufbewahrung.isNegative() || this.aufbewahrung.isZero()) {
            throw new IllegalArgumentException("archiv.chat.aufbewahrung muss positiv sein");
        }
    }

    /** Grenze für {@code jetzt}: was davor liegt, wird gelöscht. */
    public Instant grenze(Instant jetzt) {
        return jetzt.atOffset(ZoneOffset.UTC).minus(aufbewahrung).toInstant();
    }

    public Aufgeraeumt aufraeumen(MandantCode mandant, Instant jetzt) {
        Instant grenze = grenze(jetzt);
        return kontext.mit(mandant, () -> speicher.aufraeumen(grenze));
    }
}
