package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Eine Frage an den {@link ChatAgentPort}. Mandant, Benutzer (mit Rolle) und Sichtregel stammen aus der Anmeldung und
 * sind beim Start festgehalten, weil der Agent auf einem eigenen Thread ohne Request-Kontext läuft; die Werkzeuge nehmen
 * sie von hier, nie aus Parametern des Modells (Requirement 5.1).
 *
 * @param ip            IP der Chat-Anfrage ({@code X-Real-Ip}) für das Zugriffsprotokoll von {@code dokument_zeigen}
 * @param mandantenname Name des Mandanten für den Systemprompt (leer, wenn nicht gepflegt)
 * @param verlauf       die letzten 10 Nachrichten des Gesprächs vor dieser Frage, nur Fragen und Antworten, älteste
 *                      zuerst
 */
public record ChatAnfrage(MandantCode mandant, Benutzer benutzer, Sichtregel sichtregel, Optional<IpAdresse> ip,
                          String mandantenname, String frage, List<VerlaufEintrag> verlauf) {

    public ChatAnfrage {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(sichtregel, "sichtregel");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(mandantenname, "mandantenname");
        Objects.requireNonNull(frage, "frage");
        verlauf = List.copyOf(verlauf);
    }
}
