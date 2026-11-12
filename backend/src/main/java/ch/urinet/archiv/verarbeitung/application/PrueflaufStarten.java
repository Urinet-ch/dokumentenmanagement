package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * Startet den Prüflauf eines Mandanten (Aufgabe 5.3, Req 15.5): legt den Lauf an und reiht den ersten Teilauftrag ein.
 * Mit Monat höchstens ein Lauf je Monat (der Zeitplan); ohne Monat immer ein neuer Lauf (Tests, Probe).
 */
@ApplicationScoped
public class PrueflaufStarten {

    private final MandantKontext kontext;
    private final PrueflaufSpeichern speichern;
    private final Stufe stufe;

    public PrueflaufStarten(MandantKontext kontext, PrueflaufSpeichern speichern,
                            @ConfigProperty(name = "archiv.stufe") Optional<String> stufe) {
        this.kontext = kontext;
        this.speichern = speichern;
        this.stufe = Stufe.aus(stufe);
    }

    /** ID des neuen Laufs; leer, wenn für den Monat schon einer besteht. Nicht in einer laufenden Transaktion. */
    public Optional<UUID> starten(MandantCode mandant, Optional<YearMonth> monat) {
        return kontext.mit(mandant, () -> speichern.anlegen(monat.map(YearMonth::toString), stufe));
    }
}
