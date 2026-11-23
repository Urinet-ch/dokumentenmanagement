package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
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
    private final EinstellungsAenderungsPruefung pruefung;
    private final VerwaltungGrenzen grenzen;

    public EinstellungenVerwaltung(KonfigurationVerwaltung konfiguration, KonfigurationsEinstellungenSpeichern speichern,
                                   EinstellungsAenderungsPruefung pruefung, VerwaltungGrenzen grenzen) {
        this.konfiguration = konfiguration;
        this.speichern = speichern;
        this.pruefung = pruefung;
        this.grenzen = grenzen;
    }

    public Vorschau vorschau(int erwarteteVersion, Function<Einstellungen, Eingabe> eingabe) {
        konfiguration.aenderungStarten(erwarteteVersion);
        Konfiguration vorher = konfiguration.aktuell();
        Eingabe ziel = eingabe.apply(vorher.einstellungen());
        List<Auswirkung> folgen = pruefung.folgen(vorher.einstellungen(), ziel.ziel());
        gueltigkeitPruefen(vorher.version(), ziel);
        return new Vorschau(vorher.version(), folgen);
    }

    public Ergebnis aendern(int erwarteteVersion, Function<Einstellungen, Eingabe> eingabe,
                            BestaetigteEinstellungen bestaetigt, String akteur) {
        konfiguration.aenderungStarten(erwarteteVersion);
        Konfiguration vorher = konfiguration.aktuell();
        Eingabe ziel = eingabe.apply(vorher.einstellungen());
        List<Auswirkung> folgen = pruefung.folgen(vorher.einstellungen(), ziel.ziel());
        pruefung.bestaetigt(folgen, bestaetigt.schluessel(), EinstellungsAenderungsPruefung.Ursprung.APP);
        bestaetigt.pruefen();
        gueltigkeitPruefen(vorher.version(), ziel);
        if (vorher.einstellungen().equals(ziel.ziel())) {
            return new Ergebnis(vorher.version(), false, folgen);
        }
        grenzen.pruefen(akteur, VerwaltungGrenzen.Art.EINSTELLUNG, null);
        speichern.speichern(ziel.ziel());
        int version = konfiguration.aenderungSpeichern(akteur, null, KonfigurationQuelle.APP, vorher, ziel.ziel());
        return new Ergebnis(version, version != vorher.version(), folgen);
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
