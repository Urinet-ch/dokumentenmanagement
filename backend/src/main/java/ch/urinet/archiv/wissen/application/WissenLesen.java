package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class WissenLesen {

    private final WissenLesenRepository repository;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;

    public WissenLesen(WissenLesenRepository repository, WissenSichtbarkeit sichtbarkeit,
                       Berechtigung berechtigung) {
        this.repository = repository;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
    }

    @Transactional
    public IndexErgebnis index(Benutzer benutzer, Seitentyp typ, String tag, String q, UUID dokumentId,
                               int offset, int groesse) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, true);
        WissenLesenRepository.IndexErgebnis treffer = repository.index(Sichtregel.fuer(benutzer),
                new WissenLesenRepository.IndexAnfrage(typ, tag, q, dokumentId, offset, groesse));
        return IndexErgebnis.gruppieren(treffer, offset, groesse);
    }

    public List<WissenLesenRepository.SeiteVerweis> seitenFuerDokument(Benutzer benutzer, UUID dokumentId) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, true);
        return repository.seitenFuerDokument(Sichtregel.fuer(benutzer), dokumentId);
    }

    /** Eine ungültige Seiten-ID gilt wie eine unsichtbare Seite als nicht vorhanden (404, {@code VERWEIGERT}). */
    public void ungueltigeSeitenId(Benutzer benutzer) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, false);
    }

    @Transactional
    public Wissensseite seite(Benutzer benutzer, UUID id) {
        Optional<Wissensseite> seite = repository.seite(id, Sichtregel.fuer(benutzer));
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, seite.isPresent());
        return seite.orElseThrow();
    }

    @Transactional
    public Wissensseite seite(Benutzer benutzer, String pfad) {
        Optional<Wissensseite> seite = repository.seite(pfad, Sichtregel.fuer(benutzer));
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, seite.isPresent());
        return seite.orElseThrow();
    }

    @Transactional
    public List<WissenLesenRepository.VersionZeile> versionen(Benutzer benutzer, UUID seiteId) {
        boolean sichtbar = sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, sichtbar);
        return repository.versionen(seiteId, Sichtregel.fuer(benutzer));
    }

    @Transactional
    public WissenLesenRepository.WissensVersion version(Benutzer benutzer, UUID seiteId, int nummer) {
        Optional<WissenLesenRepository.WissensVersion> version = sichtbareVersion(benutzer, seiteId, nummer);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, version.isPresent());
        return version.orElseThrow();
    }

    @Transactional
    public Diff diff(Benutzer benutzer, UUID seiteId, int von, int bis) {
        Optional<WissenLesenRepository.WissensVersion> alt = sichtbareVersion(benutzer, seiteId, von);
        Optional<WissenLesenRepository.WissensVersion> neu = alt.isPresent()
                ? sichtbareVersion(benutzer, seiteId, bis) : Optional.empty();
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, neu.isPresent());
        return Diff.aus(alt.orElseThrow(), neu.orElseThrow());
    }

    /** Die Abfrage enthält das Sichtprädikat; ihr Ergebnis entscheidet, damit eine Änderung dazwischen kein 500 ergibt. */
    private Optional<WissenLesenRepository.WissensVersion> sichtbareVersion(Benutzer benutzer, UUID seiteId,
                                                                            int nummer) {
        if (!sichtbarkeit.versionSichtbar(benutzer, seiteId, nummer)) {
            return Optional.empty();
        }
        return repository.version(seiteId, nummer, Sichtregel.fuer(benutzer));
    }

    @Transactional
    public List<WissenLesenRepository.IndexZeile> eingehend(Benutzer benutzer, UUID seiteId) {
        boolean sichtbar = sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, sichtbar);
        return repository.eingehend(seiteId, Sichtregel.fuer(benutzer));
    }

    public WissenLesenRepository.LogSeite log(Benutzer benutzer, WissenLesenRepository.LogCursor vor, int groesse) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, true);
        return repository.log(Sichtregel.fuer(benutzer), vor, groesse);
    }

    /**
     * Anzeigedaten der Quelleneinträge einer Seite nach Schlüssel, unter derselben Sichtregel wie die Seite. Nicht
     * sichtbare Ziele fehlen im Ergebnis; so entstehen im Frontend weder Nachladeaufrufe noch fremde Protokolleinträge.
     */
    public Map<String, QuellenAnzeige> quellenAnzeige(Benutzer benutzer, Wissensseite seite) {
        Set<UUID> dokumentIds = new java.util.HashSet<>();
        Set<UUID> seitenIds = new java.util.HashSet<>();
        for (Quelleneintrag quelle : seite.quellen()) {
            if (quelle.dokumentId() != null) {
                dokumentIds.add(quelle.dokumentId());
            } else if (quelle.quellSeiteId() != null) {
                seitenIds.add(quelle.quellSeiteId());
            }
        }
        Sichtregel regel = Sichtregel.fuer(benutzer);
        Map<UUID, WissenLesenRepository.DokumentKopf> dokumente = repository.dokumentKoepfe(regel, dokumentIds);
        Map<UUID, WissenLesenRepository.SeiteVerweis> seiten = repository.sichtbareSeiten(regel, seitenIds);
        Map<String, QuellenAnzeige> ergebnis = new java.util.HashMap<>();
        for (Quelleneintrag quelle : seite.quellen()) {
            WissenLesenRepository.DokumentKopf dokument = quelle.dokumentId() == null ? null : dokumente.get(quelle.dokumentId());
            WissenLesenRepository.SeiteVerweis ziel = quelle.quellSeiteId() == null ? null : seiten.get(quelle.quellSeiteId());
            if (dokument != null) {
                ergebnis.put(quelle.schluessel(), new QuellenAnzeige(dokument.titel(), dokument.dateiname(), null, null));
            } else if (ziel != null) {
                ergebnis.put(quelle.schluessel(), new QuellenAnzeige(null, null, ziel.pfad(), ziel.titel()));
            }
        }
        return ergebnis;
    }

    public record QuellenAnzeige(String dokumentTitel, String dokumentDateiname, String seitePfad, String seiteTitel) {
    }

    public String inhaltDarstellen(Benutzer benutzer, String markdown) {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(markdown, WissensMarkdown.MAX_EINGABE_ZEICHEN);
        if (analyse.zuGross() || analyse.zuTief()) {
            throw new IllegalArgumentException("Das Markdown ueberschreitet die zulaessige Groesse oder Verschachtelung.");
        }
        Set<String> sichtbarePfade = repository.sichtbarePfade(Sichtregel.fuer(benutzer), analyse.links());
        return WissensMarkdown.darstellen(markdown, sichtbarePfade);
    }

    public record IndexGruppe(Seitentyp typ, List<WissenLesenRepository.IndexZeile> seiten) {
        public IndexGruppe {
            seiten = List.copyOf(seiten);
        }
    }

    public record IndexErgebnis(List<IndexGruppe> gruppen, long gesamt, int offset, int groesse) {
        public IndexErgebnis {
            gruppen = List.copyOf(gruppen);
        }

        static IndexErgebnis gruppieren(WissenLesenRepository.IndexErgebnis treffer, int offset, int groesse) {
            List<IndexGruppe> gruppen = java.util.Arrays.stream(Seitentyp.values())
                    .map(typ -> new IndexGruppe(typ, treffer.seiten().stream()
                            .filter(seite -> seite.typ() == typ).toList()))
                    .toList();
            return new IndexErgebnis(gruppen, treffer.gesamt(), offset, groesse);
        }
    }

    public record Diff(int von, int bis, List<String> vorher, List<String> entfernt, List<String> hinzugefuegt,
                       List<String> nachher) {
        public Diff {
            vorher = List.copyOf(vorher);
            entfernt = List.copyOf(entfernt);
            hinzugefuegt = List.copyOf(hinzugefuegt);
            nachher = List.copyOf(nachher);
        }

        static Diff aus(WissenLesenRepository.WissensVersion alt, WissenLesenRepository.WissensVersion neu) {
            List<String> alteZeilen = alt.inhalt().lines().toList();
            List<String> neueZeilen = neu.inhalt().lines().toList();
            int anfang = 0;
            while (anfang < alteZeilen.size() && anfang < neueZeilen.size()
                    && alteZeilen.get(anfang).equals(neueZeilen.get(anfang))) {
                anfang++;
            }
            int endeAlt = alteZeilen.size();
            int endeNeu = neueZeilen.size();
            while (endeAlt > anfang && endeNeu > anfang
                    && alteZeilen.get(endeAlt - 1).equals(neueZeilen.get(endeNeu - 1))) {
                endeAlt--;
                endeNeu--;
            }
            int gemeinsameZeilen = alteZeilen.size() - endeAlt;
            return new Diff(alt.nummer(), neu.nummer(), alteZeilen.subList(0, anfang),
                    alteZeilen.subList(anfang, endeAlt), neueZeilen.subList(anfang, endeNeu),
                    alteZeilen.subList(endeAlt, endeAlt + gemeinsameZeilen));
        }
    }
}
