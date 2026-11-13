package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.BeispielVorauswahl;
import ch.urinet.archiv.ki.domain.ErfassungMandant;
import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.ki.domain.VorschlagNachbearbeitung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Korrekturbeispiele für den Aufruf der Erfassung (Req 7.9; design.md «KI», {@code BeispielVorauswahl}): Merkmale des
 * Belegs erkennen, Kandidaten aus {@code ki_beispiel} lesen und deterministisch auswählen, ohne zweiten KI-Aufruf. In den
 * Prompt kommen sie nur mit {@code archiv.ki.beispiele.aktiv=true}; sonst bleibt die Nachricht die des Gate-Laufs, und
 * das Log nennt nur die Anzahl. Ein Fehler beim Lesen kostet nur die Beispiele, nicht den Vorschlag.
 */
@ApplicationScoped
public class BeispielAuswahl {

    private static final Logger LOG = Logger.getLogger(BeispielAuswahl.class);
    /** Kandidaten aus der Datenbank, bevor die Vorauswahl rangiert. */
    static final int KANDIDATEN_MAX = 200;

    private final KiBeispielPort beispiele;
    private final BenutzerVerzeichnis verzeichnis;
    private final boolean aktiv;
    private final Duration zeitgrenze;

    public BeispielAuswahl(KiBeispielPort beispiele, BenutzerVerzeichnis verzeichnis,
                           @ConfigProperty(name = "archiv.ki.beispiele.aktiv") boolean aktiv,
                           @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration zeitgrenze) {
        this.beispiele = beispiele;
        this.verzeichnis = verzeichnis;
        this.aktiv = aktiv;
        this.zeitgrenze = zeitgrenze;
    }

    /** Text für {beispiele}; leer ohne Beispiele oder mit ausgeschaltetem Schalter. */
    public String fuer(UUID dokumentId, MandantCode mandant, String text, Optional<String> qrIban,
                       ErfassungMandant eigene) {
        List<Beispiel> auswahl;
        try {
            Merkmale merkmale = merkmale(text, qrIban, eigene, dokumentId);
            BeispielSicht sicht = sicht(dokumentId, mandant);
            auswahl = BeispielVorauswahl.auswaehlen(merkmale,
                    merkmale.leer() ? List.of() : beispiele.kandidaten(merkmale, sicht, KANDIDATEN_MAX),
                    beispiele.neueste(sicht, BeispielVorauswahl.ANZAHL), sicht);
        } catch (RuntimeException e) {
            LOG.warnf("Korrekturbeispiele für Dokument %s nicht gelesen (%s)", dokumentId, e.getClass().getName());
            return "";
        }
        LOG.infof("Korrekturbeispiele für Dokument %s: %d ausgewählt, im Prompt %s", dokumentId, auswahl.size(),
                aktiv && !auswahl.isEmpty());
        return aktiv ? ErfassungPrompt.beispiele(auswahl) : "";
    }

    /** Merkmale nach {@link Merkmale#erkennen}; ein abgebrochenes Muster nur mit seiner Nummer im Log (Spec 05, Req 2.4). */
    Merkmale merkmale(String text, Optional<String> qrIban, ErfassungMandant eigene, UUID dokumentId) {
        Merkmale.Ergebnis ergebnis = Merkmale.erkennen(text, qrIban, eigene.uid(), eigene.ibans(), muster(),
                Merkmale.Frist.cpu(zeitgrenze));
        ergebnis.abgebrochen().forEach(nr -> LOG.warnf(
                "Belegnummer-Muster %d für Dokument %s nach %s abgebrochen, gilt als nicht zutreffend", nr,
                dokumentId, zeitgrenze));
        return ergebnis.merkmale();
    }

    static List<Pattern> muster() {
        return VorschlagNachbearbeitung.belegnummerMuster();
    }

    /**
     * Sicht des Erfassers über seinen letzten Eintrag im Benutzerverzeichnis: ohne Eintrag, als API-Token oder nach
     * Austritt nur {@code NORMAL} und eigene {@code PERSONAL}-Beispiele.
     */
    BeispielSicht sicht(UUID dokumentId, MandantCode mandant) {
        String erfasser = beispiele.erfasser(dokumentId).orElse("");
        Optional<Rolle> rolle = erfasser.isEmpty() ? Optional.empty()
                : verzeichnis.letzter(Optional.of(mandant), erfasser)
                .filter(e -> !e.beendet())
                .flatMap(VerzeichnisEintrag::rolle)
                .flatMap(BeispielAuswahl::rolle);
        if (rolle.isEmpty()) {
            return new BeispielSicht(erfasser, true, false);
        }
        Sichtregel regel = Sichtregel.fuer(new Benutzer(erfasser, rolle.get()));
        return new BeispielSicht(erfasser, regel.normal(), regel.intern());
    }

    private static Optional<Rolle> rolle(String name) {
        return Arrays.stream(Rolle.values()).filter(r -> r.name().equals(name)).findFirst();
    }
}
