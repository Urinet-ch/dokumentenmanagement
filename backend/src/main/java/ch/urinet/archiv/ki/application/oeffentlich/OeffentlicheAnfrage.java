package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Eine Frage an den {@link OeffentlicherChatAgentPort}. Mandant und {@link Lesezugriff} (und damit die Stufen des festen
 * Filters) stammen aus dem Webseiten-Schlüssel und dem Header der Anfrage und sind beim Start festgehalten; die Werkzeuge
 * nehmen sie von hier, nie aus Parametern des Modells. Es gibt keinen Benutzer, keine Rolle und keine Sicht der App.
 *
 * @param mandantenname Name des Mandanten für den Systemprompt (leer, wenn nicht gepflegt)
 * @param verlauf       die letzten Fragen und Antworten dieses Gesprächs vor dieser Frage, älteste zuerst
 */
public record OeffentlicheAnfrage(MandantCode mandant, Lesezugriff zugriff, LocalDate budgetMonat,
                                  String mandantenname, String frage, List<VerlaufEintrag> verlauf) {

    public OeffentlicheAnfrage {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(zugriff, "zugriff");
        Objects.requireNonNull(budgetMonat, "budgetMonat");
        Objects.requireNonNull(mandantenname, "mandantenname");
        Objects.requireNonNull(frage, "frage");
        verlauf = List.copyOf(verlauf);
    }
}
