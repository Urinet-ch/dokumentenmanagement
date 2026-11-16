package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Objects;

/** Was die Werkzeuge einer öffentlichen Frage vom Server wissen: Mandant und Lesezugriff, nie vom Modell. */
public record OeffentlicherKontext(MandantCode mandant, Lesezugriff zugriff) {

    public OeffentlicherKontext {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(zugriff, "zugriff");
    }
}
