package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.dokumentart.application.DokumentZaehlerPort;
import ch.urinet.archiv.dokumentart.application.RegelProtokollPort;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
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

    private final KonfigurationsdateiCodec codec;
    private final KonfigurationVerwaltung konfiguration;
    private final DokumentartRepository dokumentarten;
    private final DokumentartPruefung dokumentartPruefung;
    private final DokumentZaehlerPort dokumentZaehler;
    private final KlassifikationRepository klassifikationen;
    private final RegelRepository regeln;
    private final KiTexteRepository kiTexte;
    private final KonfigurationsEinstellungenSpeichern einstellungenSpeichern;
    private final RegelProtokollPort regelProtokoll;
    private final EinstellungsAenderungsPruefung einstellungsPruefung;
    private final VerwaltungGrenzen grenzen;

    public KonfigurationsdateiVerwaltung(KonfigurationsdateiCodec codec, KonfigurationVerwaltung konfiguration,
                                         DokumentartRepository dokumentarten,
                                         DokumentartPruefung dokumentartPruefung,
                                         DokumentZaehlerPort dokumentZaehler,
                                         KlassifikationRepository klassifikationen, RegelRepository regeln,
                                         KiTexteRepository kiTexte,
                                         KonfigurationsEinstellungenSpeichern einstellungenSpeichern,
                                         RegelProtokollPort regelProtokoll,
                                         EinstellungsAenderungsPruefung einstellungsPruefung,
                                         VerwaltungGrenzen grenzen) {
        this.codec = codec;
        this.konfiguration = konfiguration;
        this.dokumentarten = dokumentarten;
        this.dokumentartPruefung = dokumentartPruefung;
        this.dokumentZaehler = dokumentZaehler;
        this.klassifikationen = klassifikationen;
        this.regeln = regeln;
        this.kiTexte = kiTexte;
        this.einstellungenSpeichern = einstellungenSpeichern;
        this.regelProtokoll = regelProtokoll;
        this.einstellungsPruefung = einstellungsPruefung;
        this.grenzen = grenzen;
    }

    /** Datei und Version stammen aus demselben Stand, damit der ETag zur {@code basisVersion} passt. */
    public Export exportieren() {
        Konfiguration aktuell = aktuellMitLiveKiTexte();
        return new Export(codec.schreiben(aktuell), aktuell.version());
    }

    public int aktuelleVersion() {
        return konfiguration.aktuelleVersion();
    }

    /** Schemaverletzungen erscheinen wie die übrigen Prüfungen als Befunde (Spec 05, Req 6.2). */
    public Analyse pruefen(String text) {
        Konfiguration aktuell = aktuellMitLiveKiTexte();
        KonfigurationsdateiCodec.Datei datei;
        try {
            datei = codec.lesen(text);
        } catch (KonfigurationUngueltig e) {
            return new Analyse(aktuell.version(), null, null, e.befunde().stream()
                    .map(befund -> new Befund(befund.pfad(), befund.code(), befund.meldung())).toList(), List.of());
        }
        return analysieren(aktuell, datei);
    }

    public Analyse vergleichen(String text) {
        return pruefen(text);
    }

    /**
     * Massgeblich für den Stand ist allein {@code basisVersion} der Datei (Spec 05, Req 7.6); ein {@code If-Match}
     * spielt hier keine Rolle.
     */
    public Ergebnis einspielen(String text, String wer, String grund, String bestaetigt,
                              EinstellungsAenderungsPruefung.Ursprung ursprung) {
        KonfigurationsdateiCodec.Datei datei;
        try {
            datei = codec.lesen(text);
        } catch (KonfigurationUngueltig schemafehler) {
            var vorpruefung = codec.vorpruefen(text);
            if (vorpruefung.basisVersion() != null) {
                konfiguration.dateiAenderungStarten(vorpruefung.basisVersion());
                if (vorpruefung.einstellungen() != null) {
                    var folgen = einstellungsPruefung.folgen(
                            aktuellMitLiveKiTexte().einstellungen(), vorpruefung.einstellungen());
                    var gelesen = BestaetigteEinstellungen.lesen(bestaetigt);
                    einstellungsPruefung.bestaetigt(folgen, gelesen.schluessel(), ursprung);
                }
            }
            throw schemafehler;
        }
        if (datei.basisVersion() == null) {
            throw new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund("/basisVersion",
                    "BASISVERSION_FEHLT", "Die Datei nennt keine basisVersion; bitte zuerst exportieren.")));
        }
        konfiguration.dateiAenderungStarten(datei.basisVersion());
        Konfiguration aktuell = aktuellMitLiveKiTexte();
        Analyse analyse = analysieren(aktuell, datei);
        var folgen = einstellungsPruefung.folgen(aktuell.einstellungen(), analyse.ziel().einstellungen());
        var gelesen = BestaetigteEinstellungen.lesen(bestaetigt);
        einstellungsPruefung.bestaetigt(folgen, gelesen.schluessel(), ursprung);
        gelesen.pruefen();
        if (!analyse.befunde().isEmpty()) {
            throw new KonfigurationUngueltig(analyse.befunde().stream()
                    .map(befund -> new KonfigurationUngueltig.Befund(befund.pfad(), befund.code(), befund.meldung()))
                    .toList());
        }
        if (aktuell.gleicherInhalt(analyse.ziel())) {
            return new Ergebnis(aktuell.version(), analyse.aenderungen());
        }
        if (!aktuell.einstellungen().equals(analyse.ziel().einstellungen())) {
            grenzen.pruefen(wer, VerwaltungGrenzen.Art.EINSTELLUNG, null);
        }
        speichern(analyse.ziel());
        int version = konfiguration.aenderungSpeichern(wer, grund, KonfigurationQuelle.DATEI, aktuell,
                analyse.ziel().einstellungen());
        return new Ergebnis(version, analyse.aenderungen());
    }

    private Konfiguration aktuellMitLiveKiTexte() {
        Konfiguration aktuell = konfiguration.aktuell();
        KiTexte texte = kiTexte.lesen();
        return new Konfiguration(aktuell.version(), aktuell.einstellungen(), aktuell.arten(),
                aktuell.klassifikationen(), aktuell.regeln(), texte);
    }

    /**
     * Spielt den Seed eines neuen Mandanten als eine Version mit Quelle {@code SEED} ein (Req 1.5). Idempotent: Hat der
     * Mandant schon eine solche Version (abgebrochene Provisionierung nach dem Seed), bleibt alles unverändert.
     */
    public int seedEinspielen(Konfiguration seed) {
        List<Befund> befunde = new ArrayList<>(seed.pruefen());
        // Die KI-Texte gibt erst der Inhaber frei (Req 6.6, 8.4); ein Seed mit Freigabe ergäbe ein Abbild, das nicht
        // zur Tabelle ki_texte passt.
        if (seed.kiTexte() != null && seed.kiTexte().kiFreigegeben()) {
            befunde.add(new Befund("kiTexte.kiFreigegeben", "KI_FREIGABE_UNGUELTIG",
                    "Ein Seed darf die KI-Texte nicht freigeben."));
        }
        if (!befunde.isEmpty()) {
            throw new KonfigurationUngueltig(befunde.stream()
                    .map(befund -> new KonfigurationUngueltig.Befund(befund.pfad(), befund.code(), befund.meldung()))
                    .toList());
        }
        konfiguration.aenderungStarten(konfiguration.aktuelleVersion());
        if (konfiguration.versionen().stream().anyMatch(v -> v.quelle() == KonfigurationQuelle.SEED)) {
            return konfiguration.aktuelleVersion();
        }
        Konfiguration vorher = konfiguration.aktuell();
        speichern(seed);
        return konfiguration.aenderungSpeichern("system:seed", null, KonfigurationQuelle.SEED, vorher,
                seed.einstellungen());
    }

    public KiTexte kiTexte() {
        return kiTexte.lesen();
    }

    public int kiTexteAendern(String rolle, String feldregeln, int erwarteteVersion, String wer, String grund) {
        List<KonfigurationUngueltig.Befund> befunde = new ArrayList<>();
        if (rolle == null || codepunkte(rolle) > 8000) {
            befunde.add(new KonfigurationUngueltig.Befund("rolle", "TEXT_ZU_LANG",
                    "Die Rolle fehlt oder überschreitet 8'000 Zeichen."));
        }
        if (feldregeln == null || codepunkte(feldregeln) > 8000) {
            befunde.add(new KonfigurationUngueltig.Befund("feldregeln", "TEXT_ZU_LANG",
                    "Die Feldregeln fehlen oder überschreiten 8'000 Zeichen."));
        }
        if (grund == null || grund.isBlank() || codepunkte(grund) > 1000) {
            befunde.add(new KonfigurationUngueltig.Befund("grund", "GRUND_UNGUELTIG",
                    "Der Änderungsgrund fehlt oder überschreitet 1'000 Zeichen."));
        }
        if (!befunde.isEmpty()) {
            throw new KonfigurationUngueltig(befunde);
        }
        konfiguration.aenderungStarten(erwarteteVersion);
        Konfiguration vorher = aktuellMitLiveKiTexte();
        KiTexte alt = vorher.kiTexte();
        kiTexte.speichern(new KiTexte(rolle, feldregeln, alt.kiFreigegeben()));
        return konfiguration.aenderungSpeichern(wer, grund, KonfigurationQuelle.APP, vorher);
    }

    public int kiFreigeben(String wer, String abnahme) {
        List<KonfigurationUngueltig.Befund> befunde = new ArrayList<>();
        if (wer == null || wer.isBlank() || codepunkte(wer) > 80 || steuerzeichen(wer)) {
            befunde.add(new KonfigurationUngueltig.Befund("wer", "WER_UNGUELTIG",
                    "Der Betreibername fehlt oder ist ungültig."));
        }
        if (abnahme == null || abnahme.isBlank() || codepunkte(abnahme) > 1000 || steuerzeichen(abnahme)) {
            befunde.add(new KonfigurationUngueltig.Befund("abnahme", "ABNAHME_UNGUELTIG",
                    "Der Verweis auf die Abnahme fehlt oder ist ungültig."));
        }
        if (!befunde.isEmpty()) {
            throw new KonfigurationUngueltig(befunde);
        }
        konfiguration.aenderungStarten(konfiguration.aktuelleVersion());
        KiTexte texte = kiTexte.lesen();
        if (texte.kiFreigegeben()) {
            return konfiguration.aktuelleVersion();
        }
        kiTexte.speichern(new KiTexte(texte.rolle(), texte.feldregeln(), true));
        return konfiguration.aenderungSpeichern(wer, abnahme, KonfigurationQuelle.APP);
    }

    private static boolean steuerzeichen(String text) {
        return text.codePoints().anyMatch(Character::isISOControl);
    }

    private Analyse analysieren(Konfiguration aktuell, KonfigurationsdateiCodec.Datei datei) {
        Konfiguration ziel = fehlendesAusBestandErhalten(aktuell, datei.inhalt());
        List<Befund> befunde = new ArrayList<>(ziel.pruefen());
        // Massgeblich ist die Tabelle ki_texte, nicht das gespeicherte Abbild (Req 6.6, 8.4).
        if (datei.inhalt().kiTexte().kiFreigegeben() != kiTexte.lesen().kiFreigegeben()) {
            befunde.add(new Befund("kiTexte.kiFreigegeben", "KI_FREIGABE_UNGUELTIG",
                    "Die Freigabe der KI-Texte kann nicht über eine Konfigurationsdatei geändert werden."));
        }
        Map<String, Klassifikation> bisherigeKlassen = nachCodeKlasse(aktuell.klassifikationen());
        for (Klassifikation klasse : ziel.klassifikationen()) {
            Klassifikation alt = bisherigeKlassen.get(klasse.code());
            if (alt != null && alt.mehrfach() && !klasse.mehrfach()
                    && klassifikationen.mehrfachWirdVerletzt(klasse.code())) {
                befunde.add(new Befund("klassifikationen." + klasse.code() + ".mehrfach", "MEHRFACH_IN_GEBRAUCH",
                        "Dokumente tragen mehrere Werte dieser Klassifikation; sie kann nicht einfach werden."));
            }
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
        Set<String> erhalteneArten = new HashSet<>();
        for (Art alt : aktuell.arten()) {
            if (!arten.containsKey(alt.code()) && dokumentZaehler.anzahl(alt.code()) > 0) {
                arten.put(alt.code(), mitAktivitaet(alt, false));
                erhalteneArten.add(alt.code());
            }
        }

        Map<String, Klassifikation> klassen = new HashMap<>();
        datei.klassifikationen().forEach(klasse -> klassen.put(klasse.code(), klasse));
        Set<String> erhalteneKlassen = new HashSet<>();
        for (Klassifikation alt : aktuell.klassifikationen()) {
            Klassifikation neu = klassen.get(alt.code());
            if (neu == null) {
                // Req 6.3: nur Dokumente halten eine fehlende Klassifikation; Zuordnungen bestimmt die Datei.
                if (klassifikationen.dokumentAnzahl(alt.code()) > 0) {
                    klassen.put(alt.code(), mitAktivitaet(alt, false));
                    erhalteneKlassen.add(alt.code());
                }
            } else {
                klassen.put(alt.code(), verwendeteWerteErhalten(alt, neu));
            }
        }

        // Nur was der Bestand erhält, wird angepasst; Fehler der Datei selbst meldet pruefen() mit Pfad.
        List<Art> artenMitKlassen = arten.values().stream().map(art -> {
            boolean ausBestand = erhalteneArten.contains(art.code());
            List<ArtKlassifikation> zuordnungen = art.klassifikationen().stream().map(zuordnung -> {
                Klassifikation klasse = klassen.get(zuordnung.klassifikation());
                if (klasse == null) {
                    return ausBestand ? null : zuordnung;
                }
                boolean entschaerfen = erhalteneKlassen.contains(klasse.code()) || ausBestand && !klasse.aktiv();
                return entschaerfen
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
        standardwerteOhneZielLoesen(zielKlassen);
        for (Klassifikation klasse : ziel.klassifikationen()) {
            if (klassifikationen.finden(klasse.code()).isPresent()) {
                klassifikationen.speichern(klasse);
            } else {
                klassifikationen.anlegen(klasse);
            }
        }
        Map<String, Dokumentart> alteArten = new HashMap<>();
        dokumentarten.alle().forEach(art -> alteArten.put(art.code(), art));
        // Neue Arten zuerst ohne Verweise anlegen, damit Verweise unter neuen Arten keinen Fremdschlüssel verletzen.
        for (Art art : ziel.arten()) {
            if (!alteArten.containsKey(art.code())) {
                dokumentarten.anlegen(ohneArtVerweise(dokumentart(art)));
            }
        }
        for (Art art : ziel.arten()) {
            Dokumentart neu = dokumentart(art);
            Dokumentart bisher = alteArten.get(art.code());
            if (bisher != null && (!neu.aufbewahrung().equals(bisher.aufbewahrung())
                    || neu.gesetzlichePflicht() != bisher.gesetzlichePflicht())) {
                regelProtokoll.regelGeaendert(bisher, neu);
            }
            dokumentarten.speichern(neu);
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
        KiTexte texte = ziel.kiTexte();
        kiTexte.speichern(new KiTexte(texte.rolle(), texte.feldregeln(), kiTexte.lesen().kiFreigegeben()));
    }

    /**
     * Ein Standardwert, dessen Wert die Datei entfernt, wird vor dem Löschen der Werte geleert; den neuen Standardwert
     * setzt danach {@link #zuordnungenSpeichern}. Sonst verletzt das Löschen den Fremdschlüssel auf den Wert.
     */
    private void standardwerteOhneZielLoesen(Map<String, Klassifikation> zielKlassen) {
        for (Dokumentart art : dokumentarten.alle()) {
            for (ArtKlassifikation zuordnung : klassifikationen.zuordnungen(art.code())) {
                Klassifikation ziel = zielKlassen.get(zuordnung.klassifikation());
                if (zuordnung.standardWert() != null && (ziel == null || ziel.werte().stream()
                        .noneMatch(wert -> wert.code().equals(zuordnung.standardWert())))) {
                    klassifikationen.zuordnungSpeichern(art.code(),
                            new ArtKlassifikation(zuordnung.klassifikation(), zuordnung.pflicht(), null));
                }
            }
        }
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
                    throw new IllegalStateException("Zuordnung auf eine Klassifikation ausserhalb des Ziels");
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

    private static Dokumentart ohneArtVerweise(Dokumentart art) {
        return new Dokumentart(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                art.zusatzSchema(), art.vertraulichkeit(), art.aufbewahrung(), art.gesetzlichePflicht(), art.aktiv(),
                art.selbstAusgestellt(), art.belegnummerMuster(), null, null, art.kiVorschlag(), art.kiFelder(),
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

    public record Export(String datei, int version) {
    }
}
