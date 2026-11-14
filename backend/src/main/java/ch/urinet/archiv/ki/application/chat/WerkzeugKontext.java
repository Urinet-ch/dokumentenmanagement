package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.Objects;
import java.util.Optional;

/**
 * Wer fragt, festgehalten beim Start einer Chat-Anfrage aus der verifizierten Anmeldung (Spec 02, Req 5.1; tech.md
 * Regel 1): Mandant, Benutzer, Rolle, Sichtregel und IP der Anfrage. Die Werkzeuge beziehen diese Angaben nur von hier,
 * nie aus Parametern des Sprachmodells. Rolle und Sichtregel müssen zum Benutzer passen (fail closed).
 *
 * @param ip IP der Chat-Anfrage ({@code X-Real-Ip}) für das Zugriffsprotokoll, leer ohne gültige Adresse
 */
public record WerkzeugKontext(MandantCode mandant, Benutzer benutzer, Rolle rolle, Sichtregel sicht,
                              Optional<IpAdresse> ip) {

    public WerkzeugKontext {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(sicht, "sicht");
        Objects.requireNonNull(ip, "ip");
        if (rolle != benutzer.rolle() || !sicht.equals(Sichtregel.fuer(benutzer))) {
            throw new IllegalArgumentException("Rolle oder Sichtregel passen nicht zum Benutzer");
        }
    }

    /** Kontext aus Mandant, Benutzer und dem Wert des Headers {@code X-Real-Ip} (ungültig oder fehlend: ohne IP). */
    public static WerkzeugKontext fuer(MandantCode mandant, Benutzer benutzer, String realIp) {
        return new WerkzeugKontext(mandant, benutzer, benutzer.rolle(), Sichtregel.fuer(benutzer),
                IpAdresse.aus(realIp));
    }
}
