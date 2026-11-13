package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bestimmt das Schema jeder Datenbank-Session (Schema pro Mandant, ADR-002). Fail closed: Ohne Mandant gilt nur
 * {@code public}, wo es keine Mandanten-Tabellen gibt; ein ungültiger oder inaktiver Mandant bricht ab.
 */
@ApplicationScoped
public class MandantAufloesung {

    public static final String OHNE_MANDANT = "public";

    private final MandantKontext kontext;
    private final MandantAusAnmeldung anmeldung;
    private final MandantVerzeichnis verzeichnis;

    public MandantAufloesung(MandantKontext kontext, MandantAusAnmeldung anmeldung, MandantVerzeichnis verzeichnis) {
        this.kontext = kontext;
        this.anmeldung = anmeldung;
        this.verzeichnis = verzeichnis;
    }

    /**
     * Schema für die Session: Anmeldung oder Mandanten-Kontext; ohne beides {@code public}. Widersprechen sich
     * Anmeldung und Kontext, bricht es ab (fail closed).
     */
    public String schema() {
        MandantAusAnmeldung.Ergebnis ausAnmeldung = anmeldung.ermitteln();
        if (kontext.aktuell().isPresent()) {
            MandantCode code = kontext.aktuell().get();
            if (!(ausAnmeldung instanceof MandantAusAnmeldung.Anonym)
                    && !(ausAnmeldung instanceof MandantAusAnmeldung.Gueltig g && g.code().equals(code))) {
                throw new MandantVerweigert("Mandanten-Kontext widerspricht der Anmeldung");
            }
            return aktivesSchema(code);
        }
        return switch (ausAnmeldung) {
            case MandantAusAnmeldung.Anonym a -> OHNE_MANDANT;
            case MandantAusAnmeldung.Ungueltig u -> throw new MandantVerweigert("Anmeldung ohne gültigen Mandanten");
            case MandantAusAnmeldung.Gueltig g -> aktivesSchema(g.code());
        };
    }

    /** Für jede angemeldete Anfrage (Req 1.3): gültiger, aktiver Mandant aus der Anmeldung, sonst {@link MandantVerweigert}. */
    public MandantCode pruefen() {
        if (anmeldung.ermitteln() instanceof MandantAusAnmeldung.Gueltig g) {
            aktivesSchema(g.code());
            return g.code();
        }
        throw new MandantVerweigert("Anmeldung ohne gültigen Mandanten");
    }

    /**
     * Für Arbeit ohne Anfrage (Mail-Eingang, Spec 02, Req 1.6): aktiver Mandant aus dem Mandanten-Kontext
     * ({@link MandantKontext#mit}); eine Anmeldung darf es dabei nicht geben, sonst {@link MandantVerweigert}.
     */
    public MandantCode ausKontext() {
        MandantCode code = kontext.aktuell().orElseThrow(() -> new MandantVerweigert("Kein Mandanten-Kontext"));
        if (!(anmeldung.ermitteln() instanceof MandantAusAnmeldung.Anonym)) {
            throw new MandantVerweigert("Mandanten-Kontext widerspricht der Anmeldung");
        }
        aktivesSchema(code);
        return code;
    }

    private String aktivesSchema(MandantCode code) {
        return verzeichnis.aktivesSchema(code)
                .orElseThrow(() -> new MandantVerweigert("Mandant unbekannt oder inaktiv"));
    }
}
