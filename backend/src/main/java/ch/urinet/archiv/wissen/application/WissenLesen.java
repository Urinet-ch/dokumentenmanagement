package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class WissenLesen {

    private final WissenLesenRepository repository;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;
    private final WissenAnzeigeProtokoll anzeigeProtokoll;

    public WissenLesen(WissenLesenRepository repository, WissenSichtbarkeit sichtbarkeit,
                       Berechtigung berechtigung, WissenAnzeigeProtokoll anzeigeProtokoll) {
        this.repository = repository;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
        this.anzeigeProtokoll = anzeigeProtokoll;
    }

    public IndexErgebnis index(Benutzer benutzer, Seitentyp typ, String tag, String q, int offset, int groesse) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, true);
        WissenLesenRepository.IndexErgebnis treffer = repository.index(Sichtregel.fuer(benutzer),
                new WissenLesenRepository.IndexAnfrage(typ, tag, q, offset, groesse));
        return IndexErgebnis.gruppieren(treffer, offset, groesse);
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
        // Der Interceptor protokolliert nur Abrufe mit der Seiten-ID im Pfad; ohne Seite gibt es keinen Eintrag.
        anzeigeProtokoll.seiteAngezeigt(seite.orElseThrow().id());
        return seite.orElseThrow();
    }

    @Transactional
    public List<WissenLesenRepository.VersionZeile> versionen(Benutzer benutzer, UUID seiteId) {
        boolean sichtbar = sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, sichtbar);
        return repository.versionen(seiteId, Sichtregel.fuer(benutzer)).stream()
                .map(zeile -> new WissenLesenRepository.VersionZeile(zeile.nummer(),
                        zeile.version().map(WissenLesenRepository.WissensVersion::ohneInterneDaten)))
                .toList();
    }

    @Transactional
    public WissenLesenRepository.WissensVersion version(Benutzer benutzer, UUID seiteId, int nummer) {
        boolean sichtbar = sichtbarkeit.versionSichtbar(benutzer, seiteId, nummer);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, sichtbar);
        return repository.version(seiteId, nummer, Sichtregel.fuer(benutzer)).orElseThrow().ohneInterneDaten();
    }

    @Transactional
    public Diff diff(Benutzer benutzer, UUID seiteId, int von, int bis) {
        boolean sichtbar = sichtbarkeit.versionSichtbar(benutzer, seiteId, von)
                && sichtbarkeit.versionSichtbar(benutzer, seiteId, bis);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, sichtbar);
        Sichtregel regel = Sichtregel.fuer(benutzer);
        WissenLesenRepository.WissensVersion alt = repository.version(seiteId, von, regel).orElseThrow();
        WissenLesenRepository.WissensVersion neu = repository.version(seiteId, bis, regel).orElseThrow();
        return Diff.aus(von, maskieren(regel, alt.inhalt()), bis, maskieren(regel, neu.inhalt()));
    }

    /** Der Diff zeigt Links so wie die Darstellung: unsichtbare und fehlende Ziele nur als «Seite nicht vorhanden». */
    private String maskieren(Sichtregel regel, String markdown) {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(markdown, WissensMarkdown.MAX_EINGABE_ZEICHEN);
        if (analyse.zuGross() || analyse.zuTief()) {
            throw new IllegalArgumentException("Das Markdown ueberschreitet die zulaessige Groesse oder Verschachtelung.");
        }
        return WissensMarkdown.maskieren(markdown, repository.sichtbarePfade(regel, analyse.links()));
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

        static Diff aus(int von, String alterInhalt, int bis, String neuerInhalt) {
            List<String> alteZeilen = alterInhalt.lines().toList();
            List<String> neueZeilen = neuerInhalt.lines().toList();
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
            return new Diff(von, bis, alteZeilen.subList(0, anfang),
                    alteZeilen.subList(anfang, endeAlt), neueZeilen.subList(anfang, endeNeu),
                    alteZeilen.subList(endeAlt, endeAlt + gemeinsameZeilen));
        }
    }
}
