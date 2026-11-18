package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.dokumentart.application.DokumentZaehlerPort;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Aenderung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Befund;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.KiTexte;
import ch.urinet.archiv.konfiguration.domain.Regel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@Transactional
public class KonfigurationsdateiVerwaltung {

    private static final String WISSENSQUELLE = "WISSENSQUELLE";
    private final KonfigurationsdateiCodec codec;
    private final KonfigurationVerwaltung konfiguration;
    private final DokumentartRepository dokumentarten;
    private final DokumentartPruefung dokumentartPruefung;
    private final DokumentZaehlerPort dokumentZaehler;
    private final KlassifikationRepository klassifikationen;
    private final RegelRepository regeln;
    private final KiTexteRepository kiTexte;
    private final KonfigurationsEinstellungenSpeichern einstellungenSpeichern;

    public KonfigurationsdateiVerwaltung(KonfigurationsdateiCodec codec, KonfigurationVerwaltung konfiguration,
                                         DokumentartRepository dokumentarten,
                                         DokumentartPruefung dokumentartPruefung,
                                         DokumentZaehlerPort dokumentZaehler,
                                         KlassifikationRepository klassifikationen, RegelRepository regeln,
                                         KiTexteRepository kiTexte,
                                         KonfigurationsEinstellungenSpeichern einstellungenSpeichern) {
        this.codec = codec;
        this.konfiguration = konfiguration;
        this.dokumentarten = dokumentarten;
        this.dokumentartPruefung = dokumentartPruefung;
        this.dokumentZaehler = dokumentZaehler;
        this.klassifikationen = klassifikationen;
        this.regeln = regeln;
        this.kiTexte = kiTexte;
        this.einstellungenSpeichern = einstellungenSpeichern;
    }

    public String exportieren() {
        return codec.schreiben(konfiguration.aktuell());
    }

    public int aktuelleVersion() {
        return konfiguration.aktuelleVersion();
    }

    public Analyse pruefen(String text) {
        KonfigurationsdateiCodec.Datei datei = codec.lesen(text);
        Konfiguration aktuell = konfiguration.aktuell();
        return analysieren(aktuell, datei);
    }

    public Analyse vergleichen(String text) {
        return pruefen(text);
    }

    public Ergebnis einspielen(String text, int erwarteteVersion, String wer, String grund) {
        KonfigurationsdateiCodec.Datei datei = codec.lesen(text);
        if (datei.basisVersion() == null) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        konfiguration.aenderungStarten(erwarteteVersion);
        konfiguration.dateiAenderungStarten(datei.basisVersion());
        Konfiguration aktuell = konfiguration.aktuell();
        Analyse analyse = analysieren(aktuell, datei);
        if (!analyse.befunde().isEmpty()) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        if (aktuell.gleicherInhalt(analyse.ziel())) {
            return new Ergebnis(aktuell.version(), analyse.aenderungen());
        }
        speichern(analyse.ziel());
        int version = konfiguration.aenderungSpeichern(wer, grund, KonfigurationQuelle.DATEI);
        return new Ergebnis(version, analyse.aenderungen());
    }

    public KiTexte kiTexte() {
        return kiTexte.lesen();
    }

    public int kiTexteAendern(String rolle, String feldregeln, int erwarteteVersion, String wer, String grund) {
        if (rolle == null || feldregeln == null || codepunkte(rolle) > 8000 || codepunkte(feldregeln) > 8000
                || grund == null || grund.isBlank() || codepunkte(grund) > 1000) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        konfiguration.aenderungStarten(erwarteteVersion);
        KiTexte alt = kiTexte.lesen();
        kiTexte.speichern(new KiTexte(rolle, feldregeln, alt.kiFreigegeben()));
        return konfiguration.aenderungSpeichern(wer, grund);
    }

    private Analyse analysieren(Konfiguration aktuell, KonfigurationsdateiCodec.Datei datei) {
        Konfiguration ziel = fehlendesAusBestandErhalten(aktuell, datei.inhalt());
        List<Befund> befunde = new ArrayList<>(ziel.pruefen());
        if (datei.inhalt().kiTexte().kiFreigegeben() != aktuell.kiTexte().kiFreigegeben()) {
            befunde.add(new Befund("kiTexte.kiFreigegeben", "KI_FREIGABE_UNGUELTIG",
                    "Die Freigabe der KI-Texte kann nicht über eine Konfigurationsdatei geändert werden."));
        }
        for (Art art : ziel.arten()) {
            try {
                dokumentartPruefung.pruefen(dokumentart(art), art.kiFelder());
            } catch (FachFehler e) {
                befunde.add(new Befund("arten." + art.code() + ".zusatzSchema", e.code().name(),
                        "Das Zusatz-Schema der Dokumentart ist ungültig."));
            }
        }
        return new Analyse(aktuell.version(), datei.basisVersion(), ziel, List.copyOf(befunde),
                diff(aktuell, ziel));
    }

    private Konfiguration fehlendesAusBestandErhalten(Konfiguration aktuell, Konfiguration datei) {
        Map<String, Art> arten = new HashMap<>();
        datei.arten().forEach(art -> arten.put(art.code(), art));
        for (Art alt : aktuell.arten()) {
            if (!arten.containsKey(alt.code())
                    && (WISSENSQUELLE.equals(alt.code()) || dokumentZaehler.anzahl(alt.code()) > 0)) {
                arten.put(alt.code(), WISSENSQUELLE.equals(alt.code()) ? alt : mitAktivitaet(alt, false));
            }
        }

        Map<String, Klassifikation> klassen = new HashMap<>();
        datei.klassifikationen().forEach(klasse -> klassen.put(klasse.code(), klasse));
        for (Klassifikation alt : aktuell.klassifikationen()) {
            Klassifikation neu = klassen.get(alt.code());
            if (neu == null) {
                if (klassifikationen.inGebrauch(alt.code())) {
                    klassen.put(alt.code(), mitAktivitaet(alt, false));
                }
            } else {
                klassen.put(alt.code(), verwendeteWerteErhalten(alt, neu));
            }
        }

        List<Art> artenMitKlassen = arten.values().stream().map(art -> {
            List<ArtKlassifikation> zuordnungen = art.klassifikationen().stream().map(zuordnung -> {
                Klassifikation klasse = klassen.get(zuordnung.klassifikation());
                if (klasse == null) {
                    return null;
                }
                return !klasse.aktiv()
                        ? new ArtKlassifikation(zuordnung.klassifikation(), false, null) : zuordnung;
            }).filter(java.util.Objects::nonNull).toList();
            return new Art(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                    art.zusatzSchema(), art.vertraulichkeit(), art.aufbewahrung(), art.gesetzlichePflicht(),
                    art.aktiv(), art.selbstAusgestellt(), art.belegnummerMuster(), art.fremdbelegArt(),
                    art.ohneZahlungsdatenArt(), art.kiVorschlag(), art.kiFelder(), art.kiHinweise(),
                    art.reihenfolge(), zuordnungen, art.zusatzFeldTypen());
        }).toList();
        return new Konfiguration(datei.version(), datei.einstellungen(), artenMitKlassen,
                klassen.values().stream().toList(), datei.regeln(), datei.kiTexte());
    }

    private Klassifikation verwendeteWerteErhalten(Klassifikation alt, Klassifikation neu) {
        Map<String, KlassifikationWert> werte = new HashMap<>();
        neu.werte().forEach(wert -> werte.put(wert.code(), wert));
        for (KlassifikationWert alterWert : alt.werte()) {
            if (!werte.containsKey(alterWert.code())
                    && klassifikationen.wertDokumentAnzahl(alt.code(), alterWert.code()) > 0) {
                werte.put(alterWert.code(), mitAktivitaet(alterWert, false));
            }
        }
        return new Klassifikation(neu.code(), neu.name(), neu.beschreibung(), werte.values().stream().toList(),
                neu.mehrfach(), neu.kiVorschlag(), neu.suchfilter(), neu.oeffentlichFilter(), neu.aktiv());
    }

    private void speichern(Konfiguration ziel) {
        Map<String, Klassifikation> zielKlassen = nachCodeKlasse(ziel.klassifikationen());
        for (Klassifikation klasse : ziel.klassifikationen()) {
            if (klassifikationen.finden(klasse.code()).isPresent()) {
                klassifikationen.speichern(klasse);
            } else {
                klassifikationen.anlegen(klasse);
            }
        }
        Map<String, Dokumentart> alteArten = new HashMap<>();
        dokumentarten.alle().forEach(art -> alteArten.put(art.code(), art));
        for (Art art : ziel.arten()) {
            Dokumentart neu = dokumentart(art);
            if (alteArten.containsKey(art.code())) {
                dokumentarten.speichern(neu);
            } else {
                dokumentarten.anlegen(neu);
            }
        }
        zuordnungenSpeichern(ziel, alteArten.keySet(), zielKlassen);
        regeln.ersetzen(ziel.regeln());
        Set<String> zielArten = ziel.arten().stream().map(Art::code).collect(java.util.stream.Collectors.toSet());
        for (String code : alteArten.keySet()) {
            if (!zielArten.contains(code)) {
                dokumentarten.loeschen(code);
            }
        }
        Set<String> zielKlassenCodes = zielKlassen.keySet();
        for (Klassifikation klasse : klassifikationen.alle()) {
            if (!zielKlassenCodes.contains(klasse.code())) {
                klassifikationen.loeschen(klasse.code());
            }
        }
        einstellungenSpeichern.speichern(ziel.einstellungen());
        kiTexte.speichern(ziel.kiTexte());
    }

    private void zuordnungenSpeichern(Konfiguration ziel, Set<String> alteArten,
                                      Map<String, Klassifikation> zielKlassen) {
        Map<String, Art> zielArten = new HashMap<>();
        ziel.arten().forEach(art -> zielArten.put(art.code(), art));
        Set<String> alleArten = new HashSet<>(alteArten);
        alleArten.addAll(zielArten.keySet());
        for (String art : alleArten) {
            List<ArtKlassifikation> aktuell = klassifikationen.zuordnungen(art);
            Map<String, ArtKlassifikation> zielZuordnungen = zielArten.containsKey(art)
                    ? zielArten.get(art).klassifikationen().stream().collect(
                            java.util.stream.Collectors.toMap(ArtKlassifikation::klassifikation, z -> z))
                    : Map.of();
            for (ArtKlassifikation alt : aktuell) {
                if (!zielZuordnungen.containsKey(alt.klassifikation())) {
                    klassifikationen.zuordnungEntfernen(art, alt.klassifikation());
                }
            }
            for (ArtKlassifikation neu : zielZuordnungen.values()) {
                if (!zielKlassen.containsKey(neu.klassifikation())) {
                    throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
                }
                if (klassifikationen.zugeordnet(art, neu.klassifikation())) {
                    klassifikationen.zuordnungSpeichern(art, neu);
                } else {
                    klassifikationen.zuordnen(art, neu);
                }
            }
        }
    }

    private List<DiffEintrag> diff(Konfiguration aktuell, Konfiguration ziel) {
        Map<String, Regel> regelnNachCode = new HashMap<>();
        ziel.regeln().forEach(regel -> regelnNachCode.put(regel.code(), regel));
        return aktuell.aenderungen(ziel).stream().map(aenderung -> {
            long anzahl = betroffeneDokumente(aenderung.pfad(), regelnNachCode, aktuell, ziel);
            return new DiffEintrag(aenderung.pfad(), aenderung.ohneWerte() ? "geändert" : aenderung.alt(),
                    aenderung.ohneWerte() ? "geändert" : aenderung.neu(), anzahl,
                    aenderung.ohneWerte());
        }).toList();
    }

    private long betroffeneDokumente(String pfad, Map<String, Regel> zielRegeln, Konfiguration aktuell,
                                     Konfiguration ziel) {
        if (pfad.startsWith("arten.")) {
            String code = pfad.substring("arten.".length()).split("\\.", 2)[0];
            return dokumentZaehler.anzahl(code);
        }
        if (pfad.startsWith("klassifikationen.")) {
            String code = pfad.substring("klassifikationen.".length()).split("\\.", 2)[0];
            return klassifikationen.dokumentAnzahl(code);
        }
        if (pfad.startsWith("regeln.")) {
            String code = pfad.substring("regeln.".length()).split("\\.", 2)[0];
            Regel regel = zielRegeln.get(code);
            if (regel == null) {
                regel = aktuell.regeln().stream().filter(r -> code.equals(r.code())).findFirst().orElse(null);
            }
            return regel == null ? 0 : dokumenteFuerRegel(regel, aktuell, ziel);
        }
        return 0;
    }

    private long dokumenteFuerRegel(Regel regel, Konfiguration aktuell, Konfiguration ziel) {
        if (regel.art() != null) {
            return dokumentZaehler.anzahl(regel.art());
        }
        Set<String> arten = new HashSet<>();
        aktuell.arten().forEach(art -> arten.add(art.code()));
        ziel.arten().forEach(art -> arten.add(art.code()));
        return arten.stream().mapToLong(dokumentZaehler::anzahl).sum();
    }

    private static Map<String, Klassifikation> nachCodeKlasse(List<Klassifikation> klassen) {
        Map<String, Klassifikation> ergebnis = new HashMap<>();
        klassen.forEach(klasse -> ergebnis.put(klasse.code(), klasse));
        return ergebnis;
    }

    private static Dokumentart dokumentart(Art art) {
        AufbewahrungAb ab = art.aufbewahrung().ab() == null ? null
                : AufbewahrungAb.aus(art.aufbewahrung().ab())
                        .orElseThrow(() -> new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG));
        Vertraulichkeit vertraulichkeit = Vertraulichkeit.aus(art.vertraulichkeit())
                .orElseThrow(() -> new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG));
        return new Dokumentart(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                art.zusatzSchema(), vertraulichkeit,
                new Aufbewahrungsregel(art.aufbewahrung().jahre(), ab, art.aufbewahrung().feld()),
                art.gesetzlichePflicht(), art.aktiv(), art.selbstAusgestellt(), art.belegnummerMuster(),
                art.fremdbelegArt(), art.ohneZahlungsdatenArt(), art.kiVorschlag(), art.kiFelder(),
                art.kiHinweise(), art.reihenfolge());
    }

    private static Art mitAktivitaet(Art art, boolean aktiv) {
        return new Art(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                art.zusatzSchema(), art.vertraulichkeit(), art.aufbewahrung(), art.gesetzlichePflicht(), aktiv,
                art.selbstAusgestellt(), art.belegnummerMuster(), art.fremdbelegArt(), art.ohneZahlungsdatenArt(),
                art.kiVorschlag(), art.kiFelder(), art.kiHinweise(), art.reihenfolge(), art.klassifikationen(),
                art.zusatzFeldTypen());
    }

    private static Klassifikation mitAktivitaet(Klassifikation klasse, boolean aktiv) {
        return new Klassifikation(klasse.code(), klasse.name(), klasse.beschreibung(), klasse.werte(),
                klasse.mehrfach(), klasse.kiVorschlag(), klasse.suchfilter(), klasse.oeffentlichFilter(), aktiv);
    }

    private static KlassifikationWert mitAktivitaet(KlassifikationWert wert, boolean aktiv) {
        return new KlassifikationWert(wert.code(), wert.name(), wert.beschreibung(), wert.reihenfolge(), aktiv);
    }

    private static int codepunkte(String wert) {
        return wert.codePointCount(0, wert.length());
    }

    public record Analyse(int aktuelleVersion, Integer basisVersion, Konfiguration ziel, List<Befund> befunde,
                         List<DiffEintrag> aenderungen) {
        public boolean gueltig() {
            return befunde.isEmpty();
        }
    }

    public record DiffEintrag(String pfad, String alt, String neu, long betroffeneDokumente, boolean werteVerdeckt) {
    }

    public record Ergebnis(int version, List<DiffEintrag> aenderungen) {
    }
}
