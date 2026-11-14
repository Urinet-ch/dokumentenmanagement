package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.application.MandantKontext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Function;

/**
 * Rahmen jedes Werkzeugaufrufs (Spec 02, Req 5.1): Kontext aus dem {@link WerkzeugKontextRegister} unter der Lauf-ID,
 * sonst {@link WerkzeugFehler#KEIN_KONTEXT} ohne Datenbankzugriff; Werkzeug nach der {@link WerkzeugAuswahl} angeboten,
 * sonst {@link WerkzeugFehler#NICHT_VERFUEGBAR}; dann die Arbeit unabhängig vom aufrufenden Thread im Mandanten des
 * Kontexts ({@link MandantKontext#mit}). Die Transaktion beginnt erst in der Arbeit (eigene, kurze Transaktion je
 * Aufruf), weil {@code MandantKontext} keine laufende übernimmt.
 */
@ApplicationScoped
public class Werkzeuglauf {

    private final WerkzeugKontextRegister register;
    private final WerkzeugAuswahl auswahl;
    private final MandantKontext mandanten;

    public Werkzeuglauf(WerkzeugKontextRegister register, WerkzeugAuswahl auswahl, MandantKontext mandanten) {
        this.register = register;
        this.auswahl = auswahl;
        this.mandanten = mandanten;
    }

    public <T> T ausfuehren(Object lauf, String werkzeug, Function<WerkzeugKontext, T> arbeit) {
        WerkzeugKontext kontext = register.finden(lauf)
                .orElseThrow(() -> new WerkzeugFehler(WerkzeugFehler.KEIN_KONTEXT));
        if (!auswahl.erlaubt(kontext, werkzeug)) {
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_VERFUEGBAR);
        }
        return mandanten.mit(kontext.mandant(), () -> arbeit.apply(kontext));
    }
}
