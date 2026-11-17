package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KlassifikationUngueltig;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Transactional
public class KlassifikationVerwaltung {

    private final KlassifikationRepository repository;
    private final DokumentartRepository dokumentarten;
    private final KonfigurationVerwaltung konfiguration;

    public KlassifikationVerwaltung(KlassifikationRepository repository, DokumentartRepository dokumentarten,
                                    KonfigurationVerwaltung konfiguration) {
        this.repository = repository;
        this.dokumentarten = dokumentarten;
        this.konfiguration = konfiguration;
    }

    public List<Klassifikation> alle() {
        return repository.alle();
    }

    public Klassifikation finden(String code) {
        return repository.finden(code).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    public int aktuelleVersion() {
        return konfiguration.aktuelleVersion();
    }

    public Klassifikation anlegen(Klassifikation neu, int version, String wer, String grund) {
        aendern(version, wer, grund, aktuell -> mitKlassifikationen(aktuell,
                ergaenzen(aktuell.klassifikationen(), neu)), () -> repository.anlegen(neu));
        return finden(neu.code());
    }

    public Klassifikation aendern(String code, KlassifikationAenderung aenderung, int version, String wer,
                                  String grund) {
        konfiguration.aenderungStarten(version);
        Klassifikation alt = finden(code);
        Klassifikation neu = new Klassifikation(code, wert(aenderung, "name", aenderung.name(), alt.name()),
                wert(aenderung, "beschreibung", aenderung.beschreibung(), alt.beschreibung()), alt.werte(),
                wert(aenderung, "mehrfach", aenderung.mehrfach(), alt.mehrfach()),
                wert(aenderung, "kiVorschlag", aenderung.kiVorschlag(), alt.kiVorschlag()),
                wert(aenderung, "suchfilter", aenderung.suchfilter(), alt.suchfilter()),
                wert(aenderung, "oeffentlichFilter", aenderung.oeffentlichFilter(), alt.oeffentlichFilter()),
                wert(aenderung, "aktiv", aenderung.aktiv(), alt.aktiv()));
        if (alt.mehrfach() && !neu.mehrfach() && repository.mehrfachWirdVerletzt(code)) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        aendernGesperrt(wer, grund, aktuell -> mitErsetzterKlassifikation(aktuell, neu),
                () -> repository.speichern(neu));
        return finden(code);
    }

    public void loeschen(String code, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        finden(code);
        if (repository.inGebrauch(code)) {
            throw new FachFehler(Fehlercode.KONFIGURATION_IN_GEBRAUCH);
        }
        aendernGesperrt(wer, grund, aktuell -> mitEntfernterKlassifikation(aktuell, code),
                () -> repository.loeschen(code));
    }

    public KlassifikationWert wertAnlegen(String code, KlassifikationWert wert, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        Klassifikation alt = finden(code);
        List<KlassifikationWert> werte = new ArrayList<>(alt.werte());
        werte.add(wert);
        Klassifikation neu = mitWerten(alt, werte);
        aendernGesperrt(wer, grund, aktuell -> mitErsetzterKlassifikation(aktuell, neu),
                () -> repository.speichern(neu));
        return finden(code).werte().stream().filter(w -> w.code().equals(wert.code())).findFirst().orElseThrow();
    }

    public KlassifikationWert wertAendern(String code, String wertCode, KlassifikationWertAenderung aenderung,
                                          int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        Klassifikation alt = finden(code);
        List<KlassifikationWert> werte = alt.werte().stream().map(wert -> {
            if (!wert.code().equals(wertCode)) {
                return wert;
            }
            return new KlassifikationWert(wertCode, wert(aenderung, "name", aenderung.name(), wert.name()),
                    wert(aenderung, "beschreibung", aenderung.beschreibung(), wert.beschreibung()),
                    wert(aenderung, "reihenfolge", aenderung.reihenfolge(), wert.reihenfolge()),
                    wert(aenderung, "aktiv", aenderung.aktiv(), wert.aktiv()));
        }).toList();
        if (werte.size() == alt.werte().size()
                && alt.werte().stream().noneMatch(wert -> wert.code().equals(wertCode))) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Klassifikation neu = mitWerten(alt, werte);
        aendernGesperrt(wer, grund, aktuell -> mitErsetzterKlassifikation(aktuell, neu),
                () -> repository.speichern(neu));
        return finden(code).werte().stream().filter(w -> w.code().equals(wertCode)).findFirst().orElseThrow();
    }

    public void wertLoeschen(String code, String wertCode, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        Klassifikation alt = finden(code);
        if (alt.werte().stream().noneMatch(wert -> wert.code().equals(wertCode))) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        if (repository.wertInGebrauch(code, wertCode)) {
            throw new FachFehler(Fehlercode.KONFIGURATION_IN_GEBRAUCH);
        }
        Klassifikation neu = mitWerten(alt, alt.werte().stream()
                .filter(wert -> !wert.code().equals(wertCode)).toList());
        aendernGesperrt(wer, grund, aktuell -> mitErsetzterKlassifikation(aktuell, neu),
                () -> repository.speichern(neu));
    }

    public List<ArtKlassifikation> artKlassifikationen(String art) {
        if (!dokumentarten.existiert(art)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return repository.zuordnungen(art);
    }

    public ArtKlassifikation artKlassifikationAnlegen(String art, ArtKlassifikation zuordnung, int version,
                                                       String wer, String grund) {
        konfiguration.aenderungStarten(version);
        if (!dokumentarten.existiert(art) || repository.zugeordnet(art, zuordnung.klassifikation())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        aendernGesperrt(wer, grund, aktuell -> mitArtZuordnung(aktuell, art, zuordnung),
                () -> repository.zuordnen(art, zuordnung));
        return repository.zuordnungen(art).stream()
                .filter(eintrag -> eintrag.klassifikation().equals(zuordnung.klassifikation()))
                .findFirst().orElseThrow();
    }

    public ArtKlassifikation artKlassifikationAendern(String art, String code, ArtKlassifikation zuordnung,
                                                       int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        if (!repository.zugeordnet(art, code) || !code.equals(zuordnung.klassifikation())) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        aendernGesperrt(wer, grund, aktuell -> mitArtZuordnungErsetzt(aktuell, art, zuordnung),
                () -> repository.zuordnungSpeichern(art, zuordnung));
        return repository.zuordnungen(art).stream()
                .filter(eintrag -> eintrag.klassifikation().equals(code)).findFirst().orElseThrow();
    }

    public ArtKlassifikation artKlassifikationAendern(String art, String code, boolean pflicht,
                                                       String standardWert, boolean standardWertGesetzt,
                                                       int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        ArtKlassifikation alt = repository.zuordnungen(art).stream()
                .filter(eintrag -> eintrag.klassifikation().equals(code))
                .findFirst().orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        ArtKlassifikation neu = new ArtKlassifikation(code, pflicht,
                standardWertGesetzt ? standardWert : alt.standardWert());
        aendernGesperrt(wer, grund, aktuell -> mitArtZuordnungErsetzt(aktuell, art, neu),
                () -> repository.zuordnungSpeichern(art, neu));
        return repository.zuordnungen(art).stream()
                .filter(eintrag -> eintrag.klassifikation().equals(code)).findFirst().orElseThrow();
    }

    public void artKlassifikationLoeschen(String art, String code, int version, String wer, String grund) {
        konfiguration.aenderungStarten(version);
        if (!repository.zugeordnet(art, code)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        aendernGesperrt(wer, grund, aktuell -> ohneArtZuordnung(aktuell, art, code),
                () -> repository.zuordnungEntfernen(art, code));
    }

    public void dokumentKlassifikationenPruefen(String art, Map<String, List<String>> werte,
                                                Map<String, List<String>> vorher) {
        List<String> ungueltig = ungueltigeDokumentKlassifikationen(art, werte, vorher);
        if (!ungueltig.isEmpty()) {
            throw new KlassifikationUngueltig(ungueltig);
        }
    }

    public List<String> ungueltigeDokumentKlassifikationen(String art, Map<String, List<String>> werte,
                                                           Map<String, List<String>> vorher) {
        return konfiguration.aktuell().ungueltigeDokumentKlassifikationen(art, werte, vorher);
    }

    public List<String> veralteteDokumentKlassifikationen(String art, Map<String, List<String>> werte,
                                                          Map<String, List<String>> vorher) {
        return konfiguration.aktuell().veralteteDokumentKlassifikationen(art, werte, vorher);
    }

    public Map<String, List<String>> beimArtwechselVorbelegen(String art, Map<String, List<String>> bisher) {
        return konfiguration.aktuell().beimArtwechselVorbelegen(art, bisher);
    }

    private void aendern(int version, String wer, String grund,
                         java.util.function.UnaryOperator<Konfiguration> abbilden, Runnable speichern) {
        konfiguration.aenderungStarten(version);
        aendernGesperrt(wer, grund, abbilden, speichern);
    }

    private void aendernGesperrt(String wer, String grund,
                                 java.util.function.UnaryOperator<Konfiguration> abbilden, Runnable speichern) {
        Konfiguration vorher = konfiguration.aktuell();
        Konfiguration neu = abbilden.apply(vorher);
        if (!neu.pruefen().isEmpty()) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        speichern.run();
        konfiguration.aenderungSpeichern(wer, grund);
    }

    private static Konfiguration mitKlassifikationen(Konfiguration aktuell, List<Klassifikation> klassifikationen) {
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), aktuell.arten(), klassifikationen);
    }

    private static Konfiguration mitErsetzterKlassifikation(Konfiguration aktuell, Klassifikation neu) {
        List<Klassifikation> klassifikationen = aktuell.klassifikationen().stream()
                .map(alt -> alt.code().equals(neu.code()) ? neu : alt).toList();
        return mitKlassifikationen(aktuell, klassifikationen);
    }

    private static Konfiguration mitEntfernterKlassifikation(Konfiguration aktuell, String code) {
        return mitKlassifikationen(aktuell, aktuell.klassifikationen().stream()
                .filter(klassifikation -> !klassifikation.code().equals(code)).toList());
    }

    private static Konfiguration mitArtZuordnung(Konfiguration aktuell, String artCode,
                                                 ArtKlassifikation zuordnung) {
        List<Konfiguration.Art> arten = aktuell.arten().stream().map(art -> {
            if (!art.code().equals(artCode)) {
                return art;
            }
            List<ArtKlassifikation> zuordnungen = new ArrayList<>(art.klassifikationen());
            zuordnungen.add(zuordnung);
            return mitArtKlassifikationen(art, zuordnungen);
        }).toList();
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), arten,
                aktuell.klassifikationen());
    }

    private static Konfiguration ohneArtZuordnung(Konfiguration aktuell, String artCode, String code) {
        List<Konfiguration.Art> arten = aktuell.arten().stream().map(art -> {
            if (!art.code().equals(artCode)) {
                return art;
            }
            return mitArtKlassifikationen(art, art.klassifikationen().stream()
                    .filter(zuordnung -> !zuordnung.klassifikation().equals(code)).toList());
        }).toList();
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), arten,
                aktuell.klassifikationen());
    }

    private static Konfiguration mitArtZuordnungErsetzt(Konfiguration aktuell, String artCode,
                                                         ArtKlassifikation neu) {
        List<Konfiguration.Art> arten = aktuell.arten().stream().map(art -> {
            if (!art.code().equals(artCode)) {
                return art;
            }
            return mitArtKlassifikationen(art, art.klassifikationen().stream()
                    .map(alt -> alt.klassifikation().equals(neu.klassifikation()) ? neu : alt).toList());
        }).toList();
        return new Konfiguration(aktuell.version() + 1, aktuell.einstellungen(), arten,
                aktuell.klassifikationen());
    }

    private static Konfiguration.Art mitArtKlassifikationen(Konfiguration.Art art,
                                                             List<ArtKlassifikation> zuordnungen) {
        return new Konfiguration.Art(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                art.zusatzSchema(), art.vertraulichkeit(), art.aufbewahrung(), art.gesetzlichePflicht(), art.aktiv(),
                art.selbstAusgestellt(), art.belegnummerMuster(), art.fremdbelegArt(), art.ohneZahlungsdatenArt(),
                art.kiVorschlag(), art.kiFelder(), art.kiHinweise(), art.reihenfolge(), zuordnungen);
    }

    private static List<Klassifikation> ergaenzen(List<Klassifikation> klassifikationen, Klassifikation neu) {
        List<Klassifikation> ergebnis = new ArrayList<>(klassifikationen);
        ergebnis.add(neu);
        return ergebnis;
    }

    private static Klassifikation mitWerten(Klassifikation klassifikation, List<KlassifikationWert> werte) {
        return new Klassifikation(klassifikation.code(), klassifikation.name(), klassifikation.beschreibung(), werte,
                klassifikation.mehrfach(), klassifikation.kiVorschlag(), klassifikation.suchfilter(),
                klassifikation.oeffentlichFilter(), klassifikation.aktiv());
    }

    private static <T> T wert(KlassifikationAenderung aenderung, String feld, T neu, T alt) {
        return aenderung.hat(feld) ? neu : alt;
    }

    private static <T> T wert(KlassifikationWertAenderung aenderung, String feld, T neu, T alt) {
        return aenderung.hat(feld) ? neu : alt;
    }
}
