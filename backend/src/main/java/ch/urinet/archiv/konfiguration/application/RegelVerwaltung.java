package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Transactional
public class RegelVerwaltung {

    private final RegelRepository repository;
    private final RegelVerletzungsZaehler zaehler;
    private final KonfigurationVerwaltung konfiguration;

    public RegelVerwaltung(RegelRepository repository, RegelVerletzungsZaehler zaehler,
                           KonfigurationVerwaltung konfiguration) {
        this.repository = repository;
        this.zaehler = zaehler;
        this.konfiguration = konfiguration;
    }

    public List<Regel> alle() {
        return repository.alle();
    }

    public int aktuelleVersion() {
        return konfiguration.aktuelleVersion();
    }

    public Ergebnis anlegen(Regel neu, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        Konfiguration vorher = konfiguration.aktuell();
        pruefen(mitRegel(vorher, neu));
        long betroffeneDokumente = zaehler.anzahlBetroffeneDokumente(neu);
        repository.anlegen(neu);
        int neueVersion = konfiguration.aenderungSpeichern(wer, grund);
        return new Ergebnis(repository.finden(neu.code()).orElseThrow(), neueVersion, betroffeneDokumente);
    }

    public Ergebnis aendern(String code, RegelAenderung aenderung, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        Regel alt = repository.finden(code).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        Regel neu = new Regel(code, wert(aenderung, "art", aenderung.art(), alt.art()),
                wert(aenderung, "typ", aenderung.typ(), alt.typ()),
                wert(aenderung, "parameter", aenderung.parameter(), alt.parameter()),
                wert(aenderung, "schwere", aenderung.schwere(), alt.schwere()),
                wert(aenderung, "meldung", aenderung.meldung(), alt.meldung()),
                wert(aenderung, "aktiv", aenderung.aktiv(), alt.aktiv()));
        Konfiguration vorher = konfiguration.aktuell();
        pruefen(mitErsetzterRegel(vorher, neu));
        Long betroffeneDokumente = alt.aktiv() || neu.aktiv()
                ? zaehler.anzahlBetroffeneDokumente(neu) : null;
        repository.speichern(neu);
        int neueVersion = konfiguration.aenderungSpeichern(wer, grund);
        return new Ergebnis(repository.finden(code).orElseThrow(), neueVersion, betroffeneDokumente);
    }

    private static void pruefen(Konfiguration neu) {
        if (!neu.pruefen().isEmpty()) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    private static Konfiguration mitRegel(Konfiguration aktuell, Regel neu) {
        List<Regel> regeln = new ArrayList<>(aktuell.regeln());
        regeln.add(neu);
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), aktuell.arten(),
                aktuell.klassifikationen(), regeln);
    }

    private static Konfiguration mitErsetzterRegel(Konfiguration aktuell, Regel neu) {
        List<Regel> regeln = aktuell.regeln().stream()
                .map(alt -> alt.code().equals(neu.code()) ? neu : alt).toList();
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), aktuell.arten(),
                aktuell.klassifikationen(), regeln);
    }

    private static <T> T wert(RegelAenderung aenderung, String feld, T neu, T alt) {
        return aenderung.hat(feld) ? neu : alt;
    }

    public record Ergebnis(Regel regel, int version, Long betroffeneDokumente) {
    }
}
