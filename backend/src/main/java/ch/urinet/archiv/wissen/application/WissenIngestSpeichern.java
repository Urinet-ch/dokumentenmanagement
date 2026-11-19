package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Lauf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeueAenderung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.BudgetReservierung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenFassung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.function.Function;

@ApplicationScoped
public class WissenIngestSpeichern {

    public static final String VORGAENGER_ID = "vorgaengerId";

    private final WissenIngestPort port;
    private final WissenHuelleRepository huellen;
    private final WissenKonfiguration konfiguration;
    private final AuftragService auftraege;

    public WissenIngestSpeichern(WissenIngestPort port, WissenHuelleRepository huellen,
                                 WissenKonfiguration konfiguration, AuftragService auftraege) {
        this.port = port;
        this.huellen = huellen;
        this.konfiguration = konfiguration;
        this.auftraege = auftraege;
    }

    @Transactional
    public Optional<Stand> laden(UUID entwurfId, Set<UUID> seitenIds) {
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isEmpty()) {
            return Optional.empty();
        }
        Lauf daten = lauf.get();
        List<SeitenMetadaten> seiten = port.seitenMetadatenLaden();
        WissensGraph graph = huellen.graphLaden();
        WissensStufe quelleStufe = quellStufe(daten);
        Set<UUID> zuLadendeSeiten = new HashSet<>(seitenIds);
        if (WissensStufe.NORMAL == quelleStufe) {
            seiten.stream().filter(seite -> "ueberblick".equals(seite.pfad()))
                    .map(SeitenMetadaten::id).findFirst().ifPresent(zuLadendeSeiten::add);
        }
        Set<UUID> dokumentIds = new HashSet<>();
        dokumentIds.add(daten.dokumentId());
        seiten.forEach(seite -> dokumentIds.addAll(graph.dokumente(seite.id())));
        Set<UUID> personalDokumente = port.personalDokumenteLaden(dokumentIds);

        Map<UUID, SeitenMetadaten> nachId = seiten.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(SeitenMetadaten::id, seite -> seite));
        Set<UUID> quellenDokumente = new HashSet<>();
        quellenDokumente.add(daten.dokumentId());
        Map<UUID, SeitenFassung> fassungen = new java.util.HashMap<>();
        for (UUID seiteId : zuLadendeSeiten) {
            SeitenMetadaten metadaten = nachId.get(seiteId);
            if (metadaten == null || !sichtbar(metadaten, daten, graph, personalDokumente)) {
                continue;
            }
            Optional<SeitenFassung> fassung = port.seiteLaden(seiteId);
            fassung.ifPresent(seite -> {
                fassungen.put(seiteId, seite);
                quellenDokumente.addAll(graph.dokumente(seiteId));
                seite.quellen().stream().map(quelle -> quelle.dokumentId())
                        .filter(java.util.Objects::nonNull).forEach(quellenDokumente::add);
                seite.quellen().stream().map(quelle -> quelle.quellSeiteId())
                        .filter(java.util.Objects::nonNull)
                        .forEach(quellSeite -> quellenDokumente.addAll(graph.dokumente(quellSeite)));
            });
        }
        Map<UUID, WissensPruefung.DokumentQuelle> dokumentQuellen = port.dokumentQuellenLaden(quellenDokumente);
        WissenIngestStarten.Zeitraum monat = WissenIngestStarten.monat(Instant.now());
        long monatsverbrauch = port.verbrauchImZeitraum(monat.von(), monat.bis());
        return Optional.of(new Stand(daten, graph, seiten, personalDokumente, fassungen, dokumentQuellen,
                monatsverbrauch, quelleStufe));
    }

    @Transactional
    public List<SeitenMetadaten> indexLaden(UUID entwurfId, String suchtext) {
        Stand stand = laden(entwurfId, Set.of()).orElseThrow(
                () -> new IllegalStateException("Ingest-Entwurf nicht gefunden"));
        if (stand.quellStufe() == null) {
            return List.of();
        }
        List<SeitenMetadaten> sichtbar = sichtbareSeiten(stand).stream()
                .filter(seite -> stand.quellStufe() != WissensStufe.INTERN || !"ueberblick".equals(seite.pfad()))
                .toList();
        Set<UUID> ids = sichtbar.stream().map(SeitenMetadaten::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int maxZeichen = konfiguration.ingest().indexMaxZeichen();
        List<SeitenMetadaten> index = sichtbar;
        if (index.stream().mapToInt(WissenIngestSpeichern::indexZeichen).sum() > maxZeichen) {
            index = port.seitenIndexLaden(ids, suchtext).stream()
                    .filter(seite -> ids.contains(seite.id())).limit(40).toList();
        }
        List<SeitenMetadaten> begrenzt = new ArrayList<>();
        int zeichen = 0;
        for (SeitenMetadaten seite : index) {
            int laenge = indexZeichen(seite);
            if (zeichen + laenge > maxZeichen) {
                break;
            }
            begrenzt.add(seite);
            zeichen += laenge;
        }
        return List.copyOf(begrenzt);
    }

    @Transactional
    public Optional<UUID> quelleSeiteFinden(UUID dokumentId) {
        return port.quelleSeiteFinden(dokumentId);
    }

    @Transactional
    public long hoechsteQuellenNummer(UUID dokumentId) {
        return port.hoechsteQuellenNummer(dokumentId);
    }

    @Transactional
    public boolean vorschlagFuerKiZulaessig(Wissensseite vorschlag, WissensStufe quellStufe) {
        huellen.sperren();
        return vorschlagFuerKiZulaessigGesperrt(vorschlag, quellStufe);
    }

    private boolean vorschlagFuerKiZulaessigGesperrt(Wissensseite vorschlag, WissensStufe quellStufe) {
        Set<UUID> dokumente = new HashSet<>();
        Set<UUID> quellseiten = new HashSet<>();
        if (vorschlag.wissensquelleId() != null) {
            dokumente.add(vorschlag.wissensquelleId());
        }
        for (var quelle : vorschlag.quellen()) {
            if (quelle.schluessel() == null || quelle.schluessel().isBlank()
                    || (quelle.dokumentId() == null) == (quelle.quellSeiteId() == null)) {
                return false;
            }
            if (quelle.dokumentId() != null) {
                dokumente.add(quelle.dokumentId());
            } else {
                quellseiten.add(quelle.quellSeiteId());
            }
        }
        WissensGraph graph = huellen.graphLaden(dokumente);
        if (quellseiten.stream().anyMatch(id -> !graph.kenntSeite(id))) {
            return false;
        }
        quellseiten.forEach(id -> dokumente.addAll(graph.dokumente(id)));
        Map<UUID, WissensPruefung.DokumentQuelle> quellen = port.dokumentQuellenLaden(dokumente);
        if (!quellen.keySet().containsAll(dokumente)
                || quellen.values().stream().anyMatch(q -> !q.archiviert() || q.personal())
                || !port.personalDokumenteLaden(dokumente).isEmpty()) {
            return false;
        }
        return graph.wirksameStufeNachAnnahme(dokumente, quellseiten, WissensStufe.NORMAL,
                WissensStufe.NORMAL).ordinal() <= quellStufe.ordinal();
    }

    /**
     * Prüft bei jedem Teilauftrag erneut, ob die Quelle noch für den Ingest freigegeben ist (Req 5.6, 5.7): archiviert,
     * bestätigt, Art WISSENSQUELLE und nicht PERSONAL.
     */
    public static boolean freigegeben(Lauf lauf) {
        return lauf != null && "ARCHIVIERT".equals(lauf.dokumentStatus()) && "WISSENSQUELLE".equals(lauf.art())
                && quellStufe(lauf) != null;
    }

    @Transactional
    public BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens,
                                                long monatsbudgetTokens, Instant von, Instant bis) {
        return port.budgetReservieren(entwurfId, tokens, budgetTokens, monatsbudgetTokens, von, bis);
    }

    @Transactional
    public void budgetFreigeben(UUID reservierungId) {
        port.budgetFreigeben(reservierungId);
    }

    @Transactional
    public void fortschrittSichern(Auftrag auftrag, UUID entwurfId, String erwartet, String neu,
                                   Fortschritt fortschritt, long tokensEin, long tokensAus,
                                   boolean unvollstaendig, String hinweis) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER_ID)) {
            return;
        }
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isEmpty() || !"IN_ARBEIT".equals(lauf.get().entwurfStatus())
                || !erwartet.equals(lauf.get().schritt())) {
            return;
        }
        port.fortschrittSichern(entwurfId, erwartet, neu, fortschritt, tokensEin, tokensAus,
                unvollstaendig, hinweis);
        folgeauftragEinreihen(auftrag, entwurfId);
    }

    @Transactional
    public void aenderungenSichern(Auftrag auftrag, UUID entwurfId, String erwartet,
                                   List<NeueAenderung> aenderungen, Fortschritt fortschritt, long tokensEin,
                                   long tokensAus, boolean unvollstaendig, String hinweis) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER_ID)) {
            return;
        }
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isEmpty() || !"IN_ARBEIT".equals(lauf.get().entwurfStatus())
                || !erwartet.equals(lauf.get().schritt())) {
            return;
        }
        aenderungen.forEach(port::aenderungAnlegen);
        port.entwurfSchliessen(entwurfId, erwartet, "OFFEN", fortschritt, tokensEin, tokensAus,
                unvollstaendig, hinweis);
    }

    @Transactional
    public void neuerzeugungSichern(Auftrag auftrag, UUID entwurfId, UUID seiteId, int basisVersion,
                                    WissensStufe quellStufe, Fortschritt fortschritt, long tokensEin, long tokensAus,
                                    Function<Stand, NeueAenderung> pruefen) {
        auftraege.anspruchSichern(auftrag);
        huellen.sperren();
        Optional<Stand> geladen = laden(entwurfId, Set.of(seiteId));
        if (geladen.isEmpty() || !"IN_ARBEIT".equals(geladen.get().lauf().entwurfStatus())
                || !"NEU_ERZEUGEN".equals(geladen.get().lauf().schritt())) {
            return;
        }
        Stand aktuell = geladen.get();
        SeitenFassung fassung = aktuell.fassungen().get(seiteId);
        int nummer = Math.toIntExact(auftrag.nutzlast().zahl("aenderungNr")
                .orElseThrow(() -> new IllegalArgumentException("Auftragsnutzlast ohne aenderungNr")));
        WissenIngestPort.SeitenVorschlag vorschlag = aktuell.lauf().fortschritt().vorschlaege().stream()
                .filter(v -> nummer == v.nummer() && seiteId.equals(v.seiteId())).findFirst().orElse(null);
        if (!freigegeben(aktuell.lauf()) || aktuell.quellStufe() != quellStufe || fassung == null
                || fassung.metadaten().version() != basisVersion || vorschlag == null
                || !vorschlagFuerKiZulaessigGesperrt(vorschlag.daten(), quellStufe)) {
            port.neuerzeugungAbbrechen(entwurfId,
                    "Die Quelle oder Seite hat sich geändert. Bitte die Änderung erneut erzeugen.");
            return;
        }
        NeueAenderung aenderung = pruefen.apply(aktuell);
        if (aenderung == null) {
            throw new IllegalStateException("Die erneuerte Wissensänderung hat keinen gültigen Diff");
        }
        port.erneuerteAenderungSpeichern(aenderung, fortschritt, tokensEin, tokensAus);
    }

    @Transactional
    public void neuerzeugungFehler(Auftrag auftrag, UUID entwurfId, String hinweis) {
        auftraege.anspruchSichern(auftrag);
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isPresent() && "IN_ARBEIT".equals(lauf.get().entwurfStatus())
                && "NEU_ERZEUGEN".equals(lauf.get().schritt())) {
            port.neuerzeugungAbbrechen(entwurfId, hinweis);
        }
    }

    @Transactional
    public void fehlerSichern(Auftrag auftrag, UUID entwurfId, String erwartet, Fortschritt fortschritt,
                              String hinweis) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER_ID)) {
            return;
        }
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isEmpty() || !"IN_ARBEIT".equals(lauf.get().entwurfStatus())
                || !erwartet.equals(lauf.get().schritt())) {
            return;
        }
        port.entwurfSchliessen(entwurfId, erwartet, "FEHLER", fortschritt, lauf.get().tokensEin(),
                lauf.get().tokensAus(), false, hinweis);
        wartendenIngestEinreihen(auftrag);
    }

    @Transactional
    public void nachFehlversuch(UUID entwurfId) {
        Optional<Lauf> lauf = port.laufLaden(entwurfId);
        if (lauf.isEmpty() || !"IN_ARBEIT".equals(lauf.get().entwurfStatus())) {
            return;
        }
        if ("NEU_ERZEUGEN".equals(lauf.get().schritt())) {
            port.neuerzeugungAbbrechen(entwurfId,
                    "Die Änderung konnte nach drei Versuchen nicht neu erzeugt werden. Bitte erneut versuchen.");
            return;
        }
        port.entwurfSchliessen(entwurfId, lauf.get().schritt(), "FEHLER", lauf.get().fortschritt(),
                lauf.get().tokensEin(), lauf.get().tokensAus(), false,
                "Die Wissensquelle konnte nach drei Versuchen nicht verarbeitet werden.");
        wartendenIngestEinreihen(null);
    }

    @Transactional
    public int handlerFehltNachholen() {
        int anzahl = port.handlerFehltNachholen(WissenIngestStarten.HANDLER_FEHLT);
        if (anzahl > 0) {
            wartendenIngestEinreihen(null);
        }
        return anzahl;
    }

    @Transactional
    public void wartendenIngestStarten() {
        wartendenIngestEinreihen(null);
    }

    private void wartendenIngestEinreihen(Auftrag vorgaenger) {
        WissenIngestStarten.Zeitraum monat = WissenIngestStarten.monat(Instant.now());
        port.wartendenAktivieren(konfiguration.ingest().monatsbudgetTokens(), monat.von(), monat.bis())
                .ifPresent(id -> {
                    if (!auftraege.offenMit(WissenIngestStarten.TYP, WissenIngestStarten.NUTZLAST_ENTWURF_ID, id)) {
                        Map<String, Object> nutzlast = new LinkedHashMap<>();
                        nutzlast.put(WissenIngestStarten.NUTZLAST_ENTWURF_ID, id);
                        if (vorgaenger != null) {
                            nutzlast.put(VORGAENGER_ID, vorgaenger.id());
                        }
                        auftraege.einreihen(WissenIngestStarten.TYP, new Nutzlast(nutzlast));
                    }
                });
    }

    private void folgeauftragEinreihen(Auftrag vorgaenger, UUID entwurfId) {
        auftraege.einreihen(WissenIngestStarten.TYP,
                new Nutzlast(Map.of(WissenIngestStarten.NUTZLAST_ENTWURF_ID, entwurfId,
                        VORGAENGER_ID, vorgaenger.id())));
    }

    public boolean sichtbar(SeitenMetadaten seite, Lauf quelle, WissensGraph graph,
                            Set<UUID> personalDokumente) {
        return !"VERALTET".equals(seite.status()) && zugaenglich(seite, quelle, graph, personalDokumente);
    }

    /** Wie {@link #sichtbar}, aber ohne den Status: unterscheidet eine veraltete von einer höher eingestuften Seite. */
    public boolean zugaenglich(SeitenMetadaten seite, Lauf quelle, WissensGraph graph,
                               Set<UUID> personalDokumente) {
        WissensStufe stufe = quellStufe(quelle);
        if (stufe == null) {
            return false;
        }
        if ("ueberblick".equals(seite.pfad()) && stufe != WissensStufe.NORMAL) {
            return false;
        }
        if (personalDokumente.stream().anyMatch(graph.dokumente(seite.id())::contains)) {
            return false;
        }
        return rang(graph.wirksameStufe(seite.id(), graph.dokumente(seite.id()))) <= rang(stufe);
    }

    public List<SeitenMetadaten> sichtbareSeiten(Stand stand) {
        return stand.seiten().stream()
                .filter(seite -> sichtbar(seite, stand.lauf(), stand.graph(), stand.personalDokumente()))
                .toList();
    }

    /** {@code null}, wenn die Quelle nicht (mehr) freigegeben ist; der Aufrufer schliesst den Ingest dann ab. */
    private static WissensStufe quellStufe(Lauf lauf) {
        if (!lauf.bestaetigt() || lauf.quelleVertraulichkeit() == null) {
            return null;
        }
        return switch (lauf.quelleVertraulichkeit()) {
            case "NORMAL" -> WissensStufe.NORMAL;
            case "INTERN" -> WissensStufe.INTERN;
            default -> null;
        };
    }

    private static int rang(WissensStufe stufe) {
        return stufe == WissensStufe.NORMAL ? 0 : 1;
    }

    private static int indexZeichen(SeitenMetadaten seite) {
        return seite.pfad().length() + seite.typ().length() + seite.titel().length()
                + seite.beschreibung().length() + seite.tags().stream().mapToInt(String::length).sum() + 16;
    }

    public record Stand(Lauf lauf, WissensGraph graph, List<SeitenMetadaten> seiten,
                        Set<UUID> personalDokumente, Map<UUID, SeitenFassung> fassungen,
                        Map<UUID, WissensPruefung.DokumentQuelle> dokumentQuellen, long monatsverbrauch,
                        WissensStufe quellStufe) {
        public Stand {
            seiten = List.copyOf(seiten);
            personalDokumente = Set.copyOf(personalDokumente);
            fassungen = Map.copyOf(fassungen);
            dokumentQuellen = Map.copyOf(dokumentQuellen);
        }
    }
}
