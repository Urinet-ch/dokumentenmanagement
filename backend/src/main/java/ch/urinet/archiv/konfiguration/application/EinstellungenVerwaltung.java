package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
@Transactional
public class EinstellungenVerwaltung {
    public record Eingabe(Einstellungen ziel, List<KonfigurationUngueltig.Befund> befunde) {
        public Eingabe {
            befunde = List.copyOf(befunde);
        }
    }

    public record Vorschau(int version, List<Auswirkung> auswirkungen) {
        public Vorschau {
            auswirkungen = List.copyOf(auswirkungen);
        }
    }

    public record Ergebnis(int version, boolean geaendert, List<Auswirkung> auswirkungen) {
        public Ergebnis {
            auswirkungen = List.copyOf(auswirkungen);
        }
    }

    private final KonfigurationVerwaltung konfiguration;
    private final KonfigurationsEinstellungenSpeichern speichern;
    private final EinstellungsStartwerte startwerte;
    private final EinstellungsAuswirkungBerechnen auswirkung;
    private final StepUp stepUp;
    private final VerwaltungGrenzen grenzen;
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;

    public EinstellungenVerwaltung(KonfigurationVerwaltung konfiguration, KonfigurationsEinstellungenSpeichern speichern,
                                   EinstellungsStartwerte startwerte, EinstellungsAuswirkungBerechnen auswirkung,
                                   StepUp stepUp, VerwaltungGrenzen grenzen, MandantAufloesung aufloesung,
                                   MandantKontext kontext) {
        this.konfiguration = konfiguration;
        this.speichern = speichern;
        this.startwerte = startwerte;
        this.auswirkung = auswirkung;
        this.stepUp = stepUp;
        this.grenzen = grenzen;
        this.aufloesung = aufloesung;
        this.kontext = kontext;
    }

    public Vorschau vorschau(int erwarteteVersion, Function<Einstellungen, Eingabe> eingabe) {
        konfiguration.aenderungStarten(erwarteteVersion);
        Konfiguration vorher = konfiguration.aktuell();
        Eingabe ziel = eingabe.apply(vorher.einstellungen());
        List<Auswirkung> folgen = folgen(vorher.einstellungen(), ziel.ziel());
        gueltigkeitPruefen(vorher.version(), ziel);
        return new Vorschau(vorher.version(), folgen);
    }

    public Ergebnis aendern(int erwarteteVersion, Function<Einstellungen, Eingabe> eingabe,
                            Set<String> bestaetigt, String akteur) {
        konfiguration.aenderungStarten(erwarteteVersion);
        Konfiguration vorher = konfiguration.aktuell();
        Eingabe ziel = eingabe.apply(vorher.einstellungen());
        List<Auswirkung> folgen = folgen(vorher.einstellungen(), ziel.ziel());
        auswirkung.bestaetigungPruefen(folgen, bestaetigt);
        if (!folgen.isEmpty()) {
            stepUp.pruefen();
        }
        gueltigkeitPruefen(vorher.version(), ziel);
        if (vorher.einstellungen().equals(ziel.ziel())) {
            return new Ergebnis(vorher.version(), false, folgen);
        }
        grenzen.pruefen(akteur, VerwaltungGrenzen.Art.EINSTELLUNG, null);
        speichern.speichern(ziel.ziel());
        int version = konfiguration.aenderungSpeichern(akteur, null, KonfigurationQuelle.APP, vorher, ziel.ziel());
        return new Ergebnis(version, version != vorher.version(), folgen);
    }

    private List<Auswirkung> folgen(Einstellungen vorher, Einstellungen ziel) {
        String mandant = kontext.aktuell().orElseGet(aufloesung::pruefen).wert();
        return auswirkung.berechnen(vorher, ziel, startwerte.lesen(), mandant, Instant.now());
    }

    private static void gueltigkeitPruefen(int version, Eingabe eingabe) {
        List<KonfigurationUngueltig.Befund> befunde = new ArrayList<>(eingabe.befunde());
        new Konfiguration(version, eingabe.ziel(), List.of()).pruefen().stream()
                .map(befund -> new KonfigurationUngueltig.Befund(befund.pfad(), befund.code(), befund.meldung()))
                .forEach(befunde::add);
        if (!befunde.isEmpty()) {
            throw new KonfigurationUngueltig(befunde);
        }
    }
}
