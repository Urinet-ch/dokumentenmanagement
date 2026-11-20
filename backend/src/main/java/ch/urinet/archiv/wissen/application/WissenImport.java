package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PaketBereitsImportiert;
import ch.urinet.archiv.gemeinsam.domain.PaketUngueltig;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.domain.paket.PaketKopf;
import ch.urinet.archiv.wissen.domain.paket.PaketQuelle;
import ch.urinet.archiv.wissen.domain.paket.PaketSeite;
import ch.urinet.archiv.wissen.domain.paket.WissensPaket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class WissenImport {
    private final WissenImportRepository repository;
    private final WissenIngestPort daten;
    private final WissenHuelleDienst huelle;
    private final WissenHuelleRepository graph;
    private final WissenSichtbarkeit sichtbarkeit;
    private final QuellenAufloeser quellen;
    private final FreigabeRepository freigabe;
    private final FreigabeKontext kontext;
    private final WissenEntwurfLesenRepository entwuerfe;
    private final Berechtigung berechtigung;
    private final ZugriffProtokoll protokoll;
    private final DiffDienst diff;
    private final String instanz;
    private final int maxOffen;

    public WissenImport(WissenImportRepository repository, WissenIngestPort daten, WissenHuelleDienst huelle,
                        WissenHuelleRepository graph, WissenSichtbarkeit sichtbarkeit, QuellenAufloeser quellen,
                        FreigabeRepository freigabe, FreigabeKontext kontext, WissenEntwurfLesenRepository entwuerfe,
                        Berechtigung berechtigung, ZugriffProtokoll protokoll, DiffDienst diff,
                        @ConfigProperty(name = "archiv.wissen.instanz-id") String instanz,
                        @ConfigProperty(name = "archiv.wissen.import.max-offen", defaultValue = "5") int maxOffen) {
        this.repository = repository;
        this.daten = daten;
        this.huelle = huelle;
        this.graph = graph;
        this.sichtbarkeit = sichtbarkeit;
        this.quellen = quellen;
        this.freigabe = freigabe;
        this.kontext = kontext;
        this.entwuerfe = entwuerfe;
        this.berechtigung = berechtigung;
        this.protokoll = protokoll;
        this.diff = diff;
        this.instanz = instanz;
        if (maxOffen < 1 || maxOffen > 5) {
            throw new IllegalArgumentException("Ungültige Grenze offener Wissensimporte");
        }
        this.maxOffen = maxOffen;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Antwort importieren(Benutzer benutzer, WissensPaket paket, String sha256, long deadline, String realIp) {
        berechtigung.aktion(benutzer, Aktion.A17);
        repository.sperren(restzeit(deadline));
        huelle.sperren();
        repository.vorhandenerImport(sha256).ifPresent(id -> { throw new PaketBereitsImportiert(id); });
        if (repository.offeneImporte() >= maxOffen) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        UUID id = UUID.randomUUID();
        Set<UUID> sichtbar = Set.copyOf(sichtbarkeit.sichtbareSeiten(benutzer));
        Map<String, WissenIngestPort.SeitenMetadaten> seiten = daten.seitenMetadatenLaden().stream()
                .collect(Collectors.toMap(WissenIngestPort.SeitenMetadaten::pfad, s -> s));
        List<Vorbereitet> vorbereitet = new ArrayList<>();
        int unveraendert = 0;
        for (PaketSeite seite : paket.seiten()) {
            restzeit(deadline);
            var metadaten = seiten.get(seite.pfad());
            var basis = metadaten == null || !sichtbar.contains(metadaten.id()) ? null
                    : daten.seiteLaden(metadaten.id()).orElseThrow();
            var aufgeloest = aufloesen(benutzer, paket.kopf(), seite, basis);
            if (basis != null && unveraendert(basis, aufgeloest.fassung())
                    && aufgeloest.befunde().isEmpty()) {
                unveraendert++;
                continue;
            }
            Integer version = null;
            List<WissensPruefung.Hinweis> hinweise = new ArrayList<>(aufgeloest.hinweise());
            if (basis != null) {
                if (instanz.equals(paket.kopf().instanz()) && seite.basisVersion() != null) {
                    if (seite.basisVersion() > Integer.MAX_VALUE || seite.basisVersion() < 1) {
                        throw new PaketUngueltig("FORMAT", seite.pfad());
                    }
                    version = seite.basisVersion().intValue();
                } else {
                    version = basis.metadaten().version();
                    hinweise.add(hinweis(WissensPruefung.Hinweiscode.BASIS_UNBEKANNT));
                }
            }
            vorbereitet.add(new Vorbereitet(seite, basis, aufgeloest, version, hinweise, metadaten != null && basis == null));
        }
        int ignoriert = (int) paket.hinweise().stream()
                .filter(h -> h.code().name().equals("DATEI_IGNORIERT")).count();
        repository.anlegen(id, benutzer.id(), sha256, paket.kopf(), unveraendert, ignoriert);
        List<Map<String, String>> befunde = new ArrayList<>();
        List<Map<String, String>> hinweise = new ArrayList<>();
        paket.hinweise().forEach(h -> hinweise.add(Map.of("pfad", h.pfad(), "code", h.code().name())));
        int neu = 0;
        int aendern = 0;
        int nr = 0;
        for (Vorbereitet seite : vorbereitet) {
            restzeit(deadline);
            var aenderung = aenderung(benutzer, id, ++nr, seite);
            daten.aenderungAnlegen(aenderung);
            boolean veraltet = seite.basis() != null && !Objects.equals(seite.version(), seite.basis().metadaten().version());
            repository.paketSeiteSpeichern(id, nr, seite.seite(), veraltet);
            repository.quellenPruefungSpeichern(id, nr, seite.aufgeloest().quellenPruefung());
            if (seite.basis() == null) { neu++; } else { aendern++; }
            aenderung.befunde().forEach(b -> befunde.add(Map.of("pfad", aenderung.pfad(), "code", b.code().name())));
            aenderung.hinweise().forEach(h -> hinweise.add(Map.of("pfad", aenderung.pfad(), "code", h.code().name())));
        }
        protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer.id()), Optional.of(benutzer.rolle().name()),
                ProtokollAktion.WISSEN_IMPORT, Optional.of(id), IpAdresse.aus(realIp),
                ProtokollDetails.wissensImport(sha256, paket.seiten().size())));
        restzeit(deadline);
        return new Antwort(id, "OFFEN", new Anzahl(neu, aendern, unveraendert, ignoriert), sha256, befunde, hinweise);
    }

    @Transactional
    public void neuPruefen(Benutzer benutzer, UUID id) {
        huelle.sperren();
        var gefunden = repository.kopfLaden(id);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A17, gefunden.isPresent());
        var kopf = gefunden.orElseThrow();
        var entwurf = entwuerfe.kopf(id).orElseThrow();
        if (!"OFFEN".equals(entwurf.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        for (var eintrag : entwuerfe.aenderungen(id)) {
            if (!Set.of("OFFEN", "VERALTET").contains(eintrag.status())) {
                continue;
            }
            var gesperrt = freigabe.sperren(id, eintrag.nr()).orElseThrow();
            var seite = repository.seiteLaden(id, eintrag.nr());
            var basis = gesperrt.seiteId() == null ? null : daten.seiteLaden(gesperrt.seiteId()).orElseThrow();
            var aufgeloest = aufloesen(benutzer, kopf, seite, basis);
            var stufe = aufgeloest.stufe().hoechste(WissensStufe.valueOf(eintrag.vertraulichkeitKontext()));
            aufgeloest = new Aufgeloest(aufgeloest.fassung(), stufe, aufgeloest.befunde(), aufgeloest.hinweise(),
                    aufgeloest.quellenPruefung());
            repository.pruefungSpeichern(aenderung(benutzer, id, eintrag.nr(),
                    new Vorbereitet(seite, basis, aufgeloest, gesperrt.basisVersion(), aufgeloest.hinweise(), false)));
            repository.quellenPruefungSpeichern(id, eintrag.nr(), aufgeloest.quellenPruefung());
        }
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Aufgeloest fuerAnnahme(Benutzer benutzer, UUID id, int nummer, WissenIngestPort.SeitenFassung basis,
                                  FreigabeAnnahme.Bearbeitung bearbeitung) {
        PaketKopf kopf = repository.kopfLaden(id).orElseThrow(
                () -> new IllegalStateException("Importkopf fehlt bei Freigabe"));
        PaketSeite seite = repository.seiteLaden(id, nummer);
        if (bearbeitung != null) {
            if (ManuellerWissensentwurf.enthaeltNull(bearbeitung.quellen())
                    || ManuellerWissensentwurf.enthaeltNull(bearbeitung.tags())) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            Map<String, PaketQuelle> bisher = seite.sources().stream()
                    .collect(Collectors.toMap(PaketQuelle::id, q -> q, (a, b) -> a));
            Set<UUID> sichtbar = Set.copyOf(sichtbarkeit.sichtbareSeiten(benutzer));
            Map<UUID, String> pfade = daten.seitenMetadatenLaden().stream().filter(s -> sichtbar.contains(s.id()))
                    .collect(Collectors.toMap(WissenIngestPort.SeitenMetadaten::id, s -> "/" + s.pfad() + ".md"));
            List<PaketQuelle> quellenNeu = new ArrayList<>();
            for (var q : bearbeitung.quellen() == null ? List.<Quelleneintrag>of() : bearbeitung.quellen()) {
                var alt = bisher.get(q.schluessel());
                String sha = q.sha256() != null ? q.sha256() : alt == null ? null : alt.sha256();
                if (sha != null && !sha.matches("[0-9a-fA-F]{64}")) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
                String resource = q.quellSeiteId() == null ? null : pfade.get(q.quellSeiteId());
                quellenNeu.add(new PaketQuelle(q.schluessel(), resource,
                        resource == null ? sha : null, q.seiteVon() == null ? null
                        : q.seiteBis() == null ? q.seiteVon().toString() : q.seiteVon() + "-" + q.seiteBis(),
                        q.auszug(), alt != null && alt.wissensquelle()));
            }
            seite = new PaketSeite(seite.pfad(), seite.typ(), bearbeitung.titel(), bearbeitung.beschreibung(),
                    bearbeitung.tags() == null ? List.of() : bearbeitung.tags(), bearbeitung.veraltetAb(),
                    seite.basisVersion(), seite.erzeugtVon(), quellenNeu, bearbeitung.inhalt());
        }
        return aufloesen(benutzer, kopf, seite, basis);
    }

    private Aufgeloest aufloesen(Benutzer benutzer, PaketKopf kopf, PaketSeite seite,
                                WissenIngestPort.SeitenFassung basis) {
        List<Quelleneintrag> eintraege = new ArrayList<>();
        List<WissensPruefung.Befund> befunde = new ArrayList<>();
        List<WissensPruefung.Hinweis> hinweise = new ArrayList<>();
        List<QuellenPruefung> pruefungen = new ArrayList<>();
        WissensStufe stufe = WissensStufe.valueOf(kopf.kontextStufe());
        UUID wissensquelleId = null;
        int markiert = 0;
        Set<UUID> sichtbar = Set.copyOf(sichtbarkeit.sichtbareSeiten(benutzer));
        var seiten = daten.seitenMetadatenLaden().stream().filter(s -> sichtbar.contains(s.id()))
                .collect(Collectors.toMap(WissenIngestPort.SeitenMetadaten::pfad, s -> s));
        var quellGraph = graph.graphLaden();
        for (PaketQuelle quelle : seite.sources()) {
            UUID dokumentId = null;
            UUID seitenId = null;
            Integer von = null;
            Integer bis = null;
            String zuordnung = "QUELLE_UNBEKANNT";
            WissensPruefung.DokumentQuelle dokument = null;
            if (quelle.sha256() != null) {
                var aufloesung = quellen.aufloesen(benutzer, quelle.sha256());
                zuordnung = switch (aufloesung.status()) {
                    case DOKUMENT -> "ZUGEORDNET";
                    case PERSONAL -> "QUELLE_PERSONAL";
                    case MEHRDEUTIG -> "QUELLE_UNGUELTIG";
                    case UNBEKANNT -> "QUELLE_UNBEKANNT";
                };
                switch (aufloesung.status()) {
                    case UNBEKANNT -> befunde.add(befund(WissensPruefung.Code.QUELLE_UNBEKANNT));
                    case PERSONAL -> befunde.add(befund(WissensPruefung.Code.QUELLE_PERSONAL));
                    case MEHRDEUTIG -> befunde.add(befund(WissensPruefung.Code.QUELLE_UNGUELTIG));
                    case DOKUMENT -> {
                        dokumentId = aufloesung.dokumentId();
                        dokument = aufloesung.dokument();
                        stufe = stufe.hoechste(aufloesung.stufe());
                        if (aufloesung.dokument().seitentext().isEmpty()) {
                            befunde.add(befund(WissensPruefung.Code.AUSZUG_NICHT_PRUEFBAR));
                        }
                    }
                }
                if (quelle.auszug() == null || quelle.auszug().isBlank()) {
                    befunde.add(befund(WissensPruefung.Code.AUSZUG_FEHLT));
                }
                if (quelle.seite() != null) {
                    try {
                        String[] bereich = quelle.seite().split("-", -1);
                        von = Integer.valueOf(bereich[0]);
                        bis = bereich.length == 1 ? null : Integer.valueOf(bereich[1]);
                    } catch (NumberFormatException e) {
                        befunde.add(befund(WissensPruefung.Code.QUELLE_UNGUELTIG));
                    }
                }
                if (quelle.wissensquelle()) {
                    wissensquelleId = dokumentId;
                    markiert++;
                }
            } else if (quelle.resource() != null) {
                String pfad = quelle.resource();
                if (pfad.startsWith("/") && pfad.endsWith(".md")) {
                    var ziel = seiten.get(pfad.substring(1, pfad.length() - 3));
                    if (ziel != null) {
                        seitenId = ziel.id();
                        zuordnung = "ZUGEORDNET";
                        stufe = stufe.hoechste(quellGraph.wirksameStufe(ziel.id(), quellGraph.dokumente(ziel.id())));
                    }
                }
            }
            var eintrag = new Quelleneintrag(quelle.id(), dokumentId, von, bis, quelle.auszug(), seitenId, quelle.sha256());
            eintraege.add(eintrag);
            pruefungen.add(new QuellenPruefung(quelle.id(), zuordnung,
                    dokument == null ? null : WissensPruefung.auszugPruefen(eintrag, dokument)));
        }
        if ("Quelle".equals(seite.typ()) && markiert != 1) {
            befunde.add(befund(WissensPruefung.Code.QUELLE_UNGUELTIG));
        }
        if (stufe != WissensStufe.valueOf(kopf.kontextStufe())) {
            hinweise.add(hinweis(WissensPruefung.Hinweiscode.STUFE_IGNORIERT));
        }
        if ("cloud".equals(kopf.erzeugtMit()) && stufe == WissensStufe.INTERN) {
            befunde.add(befund(WissensPruefung.Code.CLOUD_STUFE_UNZULAESSIG));
        }
        Seitentyp typ = Seitentyp.vonDatenbankwert(seite.typ())
                .orElseThrow(() -> new PaketUngueltig("FORMAT", seite.pfad()));
        String erzeugt = seite.erzeugtVon() == null ? kopf.erzeugtVon() : seite.erzeugtVon();
        var fassung = new Wissensseite(basis == null ? null : basis.metadaten().id(), seite.pfad(), typ, seite.titel(),
                seite.beschreibung(), seite.tags(), basis == null ? Wissensseite.Status.AKTIV
                : Wissensseite.Status.valueOf(basis.metadaten().status()), seite.staleAfter(), eintraege,
                Wissensseite.Vertraulichkeit.valueOf(stufe.name()), WissensMarkdown.ausOkf(seite.inhalt()),
                basis == null ? 0 : basis.metadaten().version(), "extern:" + erzeugt, null, null, null,
                false, false, wissensquelleId);
        return new Aufgeloest(fassung, stufe, befunde, hinweise, pruefungen);
    }

    private WissenIngestPort.NeueAenderung aenderung(Benutzer benutzer, UUID id, int nr, Vorbereitet seite) {
        var fassung = seite.aufgeloest().fassung();
        var basis = seite.basis();
        var aenderung = new FreigabeRepository.Aenderung(0, fassung.id(), "OFFEN", "IMPORT", "OFFEN", 0,
                basis == null ? "NEU" : "AENDERN", fassung.pfad(), seite.version());
        var vorschlag = new FreigabeRepository.Vorschlag(fassung, Set.of(), seite.aufgeloest().stufe().name(),
                null, null, benutzer.id());
        var ergebnis = kontext.pruefen(benutzer, id, aenderung, vorschlag, fassung, null);
        List<WissensPruefung.Befund> befunde = new ArrayList<>(ergebnis.pruefung().befunde());
        befunde.addAll(seite.aufgeloest().befunde());
        List<WissensPruefung.Hinweis> hinweise = new ArrayList<>(ergebnis.pruefung().hinweise());
        hinweise.addAll(seite.hinweise());
        if (seite.hoehereStufe()) {
            befunde.add(befund(WissensPruefung.Code.PFAD_UNGUELTIG));
            hinweise.add(hinweis(WissensPruefung.Hinweiscode.HOEHERE_STUFE_VORHANDEN));
        }
        var vorher = basis == null ? null : new Wissensseite(basis.metadaten().id(), basis.metadaten().pfad(),
                Seitentyp.vonDatenbankwert(basis.metadaten().typ()).orElseThrow(), basis.metadaten().titel(),
                basis.metadaten().beschreibung(), basis.metadaten().tags(),
                Wissensseite.Status.valueOf(basis.metadaten().status()), basis.veraltetAb(), basis.quellen(),
                Wissensseite.Vertraulichkeit.NORMAL, basis.inhalt(), basis.metadaten().version(),
                null, null, null, null, false, false, basis.wissensquelleId());
        return new WissenIngestPort.NeueAenderung(id, nr, aenderung.aktion(), fassung.id(), fassung.pfad(),
                seite.version(), fassung, null, List.of(), List.of(), befunde.stream().distinct().toList(),
                hinweise.stream().distinct().toList(), diff.vergleichen(vorher, fassung),
                seite.aufgeloest().stufe().name());
    }

    private static boolean unveraendert(WissenIngestPort.SeitenFassung basis, Wissensseite fassung) {
        var m = basis.metadaten();
        var mindest = WissensStufe.valueOf(m.vertraulichkeitMindest());
        return m.typ().equals(fassung.typ() == null ? null : fassung.typ().datenbankwert())
                && mindest.hoechste(WissensStufe.valueOf(fassung.vertraulichkeit().name())) == mindest
                && m.titel().equals(fassung.titel()) && m.beschreibung().equals(fassung.beschreibung())
                && m.tags().equals(fassung.tags()) && Objects.equals(basis.veraltetAb(), fassung.veraltetAb())
                && basis.inhalt().equals(fassung.inhalt()) && Objects.equals(basis.wissensquelleId(), fassung.wissensquelleId())
                && basis.quellen().stream().map(WissenImport::ohneSha).collect(Collectors.toSet())
                .equals(fassung.quellen().stream().map(WissenImport::ohneSha).collect(Collectors.toSet()));
    }

    private static Quelleneintrag ohneSha(Quelleneintrag quelle) {
        return new Quelleneintrag(quelle.schluessel(), quelle.dokumentId(), quelle.seiteVon(),
                quelle.seiteBis() == null ? quelle.seiteVon() : quelle.seiteBis(),
                quelle.auszug(), quelle.quellSeiteId());
    }

    static long restzeit(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new PaketUngueltig("ZEIT", "paket.yaml");
        }
        return Math.max(1, nanos / 1_000_000);
    }

    private static WissensPruefung.Befund befund(WissensPruefung.Code code) {
        return new WissensPruefung.Befund(code, "Die importierte Quelle muss geprüft werden.");
    }

    private static WissensPruefung.Hinweis hinweis(WissensPruefung.Hinweiscode code) {
        return new WissensPruefung.Hinweis(code, "Angaben des Wissenspakets wurden abgeglichen.");
    }

    private record Vorbereitet(PaketSeite seite, WissenIngestPort.SeitenFassung basis, Aufgeloest aufgeloest,
                              Integer version, List<WissensPruefung.Hinweis> hinweise, boolean hoehereStufe) {
    }

    public record Aufgeloest(Wissensseite fassung, WissensStufe stufe, List<WissensPruefung.Befund> befunde,
                             List<WissensPruefung.Hinweis> hinweise, List<QuellenPruefung> quellenPruefung) {
        public Aufgeloest {
            befunde = List.copyOf(befunde);
            hinweise = List.copyOf(hinweise);
            quellenPruefung = List.copyOf(quellenPruefung);
        }
    }

    public record QuellenPruefung(String schluessel, String zuordnung, WissensPruefung.AuszugsErgebnis auszug) { }

    public record Anzahl(int neu, int aendern, int unveraendert, int ignoriert) {
    }

    public record Antwort(UUID entwurfId, String status, Anzahl anzahl, String paketSha256,
                          List<Map<String, String>> befunde, List<Map<String, String>> hinweise) {
        public Antwort {
            befunde = List.copyOf(befunde);
            hinweise = List.copyOf(hinweise);
        }
    }
}
