package ch.urinet.archiv.suche.adapter.out.konfiguration;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.suche.application.AktualitaetsregelPort;
import ch.urinet.archiv.suche.domain.Aktualitaetsregel;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;

/**
 * Aktualitätsregel je Mandant aus {@code archiv.suche.aktualitaet.mandanten.<code>} (Spec 03, Req 5.12); ohne Eintrag
 * {@link Aktualitaetsregel#AUS}. Die Werte werden beim Start geprüft: Ein ungültiger Mandant-Code oder Wert bricht den
 * Start ab, statt erst die öffentliche Suche scheitern zu lassen.
 */
@Startup
@ApplicationScoped
public class KonfigurierteAktualitaetsregel implements AktualitaetsregelPort {

    private final Map<String, Aktualitaetsregel> regeln;

    public KonfigurierteAktualitaetsregel(AktualitaetsKonfiguration konfiguration) {
        Map<String, Aktualitaetsregel> gelesen = new HashMap<>();
        konfiguration.mandanten().forEach((code, eintrag) -> gelesen.put(code, regel(code, eintrag)));
        regeln = Map.copyOf(gelesen);
    }

    private static Aktualitaetsregel regel(String code, AktualitaetsKonfiguration.Mandant eintrag) {
        try {
            new MandantCode(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("archiv.suche.aktualitaet.mandanten: «" + code
                    + "» ist kein gültiger Mandant-Code; Schlüssel korrigieren", e);
        }
        try {
            return new Aktualitaetsregel(eintrag.aktiviert(), eintrag.faktor(), eintrag.halbwertzeitTage());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("archiv.suche.aktualitaet.mandanten." + code + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Aktualitaetsregel fuer(String mandantCode) {
        return regeln.getOrDefault(mandantCode, Aktualitaetsregel.AUS);
    }
}
