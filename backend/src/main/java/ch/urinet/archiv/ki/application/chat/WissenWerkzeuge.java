package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.wissen.application.WissenKonfiguration;
import ch.urinet.archiv.wissen.application.WissenLesen;
import ch.urinet.archiv.wissen.application.WissenLesenRepository;
import ch.urinet.archiv.wissen.application.WissensSuche;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissenWerkzeuge {
    public record IndexZeile(String pfad, String titel, String typ, String beschreibung, List<String> hinweise,
                             String link, Wissensseite.Vertraulichkeit vertraulichkeit) { }
    public record Index(List<IndexZeile> treffer, boolean degradiert, String hinweis) { }
    public record Quelle(String schluessel, UUID dokumentId, String titel, String seiten, String seite, String link,
                         int anzahl) { }
    public record Seite(String pfad, String titel, String typ, String inhalt, List<Quelle> quellen, String stand,
                        String freigegebenVon, List<String> hinweise, String link,
                        Wissensseite.Vertraulichkeit vertraulichkeit) { }

    static final int INDEX_MAX = 200;

    private final WissenLesen lesen;
    private final WissensSuche suche;
    private final WissenKonfiguration konfiguration;
    private final ZugriffProtokoll protokoll;
    private final WerkzeugTransaktion transaktion;

    public WissenWerkzeuge(WissenLesen lesen, WissensSuche suche, WissenKonfiguration konfiguration,
                           ZugriffProtokoll protokoll, WerkzeugTransaktion transaktion) {
        this.lesen = lesen;
        this.suche = suche;
        this.konfiguration = konfiguration;
        this.protokoll = protokoll;
        this.transaktion = transaktion;
    }

    public Index index(WerkzeugKontext kontext, String frage, String typ, Integer anzahl) {
        String text = Werkzeugparameter.text("frage", frage, 200, false);
        Seitentyp filter = typ == null || typ.isBlank() ? null
                : Werkzeugparameter.wert("typ", typ, Seitentyp.class, null);
        if (text == null) {
            if (anzahl != null) {
                throw WerkzeugFehler.ungueltig("anzahl");
            }
            var index = lesen.index(kontext.benutzer(), filter, null, null, null, 0, INDEX_MAX);
            List<IndexZeile> zeilen = index.gruppen().stream().flatMap(g -> g.seiten().stream())
                    .map(WissenWerkzeuge::zeile).toList();
            return new Index(zeilen, false, index.gesamt() > zeilen.size()
                    ? "Der Index ist auf " + INDEX_MAX + " Seiten gekürzt, mit frage oder typ eingrenzen." : null);
        }
        int max = Werkzeugparameter.zahl("anzahl", anzahl, 1, 50, 5);
        var ergebnis = suche.suchen(kontext.benutzer(), text, filter, null, null,
                Math.min(max, HybridSuche.RERANKER), true);
        return new Index(ergebnis.treffer().stream().map(WissenWerkzeuge::zeile).toList(), ergebnis.degradiert(),
                max > HybridSuche.RERANKER ? "Die Suche liefert höchstens 30 rerangierte Treffer." : null);
    }

    public Seite zeigen(WerkzeugKontext kontext, String pfad) {
        String wert = Werkzeugparameter.text("pfad", pfad, 200, true);
        try {
            return zeigenInTransaktion(kontext, wert);
        } catch (Verweigerung e) {
            // unbekannt, unsichtbar oder nicht erlaubt: für das Modell dasselbe; VERWEIGERT ist bereits protokolliert
            throw new WerkzeugFehler(WerkzeugFehler.SEITE_NICHT_GEFUNDEN);
        }
    }

    private Seite zeigenInTransaktion(WerkzeugKontext kontext, String wert) {
        return transaktion.neu(() -> {
            Wissensseite seite = lesen.seite(kontext.benutzer(), wert);
            Map<String, WissenLesen.QuellenAnzeige> anzeigen = lesen.quellenAnzeige(kontext.benutzer(), seite);
            var analyse = WissensMarkdown.analysieren(seite.inhalt(), WissensMarkdown.MAX_EINGABE_ZEICHEN);
            if (analyse.zuGross() || analyse.zuTief()) {
                throw new IllegalStateException("Wissensseite nicht analysierbar");
            }
            List<Quelle> quellen = seite.quellen().stream()
                    .map(q -> quelle(q, anzeigen.get(q.schluessel()), analyse.fussnotenHaeufigkeit()))
                    .toList();
            int grenze = konfiguration.ingest().werkzeugMaxZeichen();
            String inhalt = lesen.inhaltDarstellen(kontext.benutzer(), seite.inhalt());
            boolean gekuerzt = inhalt.codePointCount(0, inhalt.length()) > grenze;
            if (gekuerzt) {
                inhalt = inhalt.substring(0, inhalt.offsetByCodePoints(0, grenze));
            }
            List<String> hinweise = hinweise(seite);
            if (gekuerzt) hinweise.add("GEKUERZT");
            protokoll.schreiben(new ZugriffEintrag(Optional.of(kontext.benutzer().id()),
                    Optional.of(kontext.rolle().name()), ProtokollAktion.WISSEN_ANZEIGEN, Optional.of(seite.id()),
                    kontext.ip(), ProtokollDetails.KEINE.mitKanal(ProtokollDetails.KANAL_CHAT)));
            return new Seite(seite.pfad(), seite.titel(), seite.typ().datenbankwert(), inhalt, quellen,
                    seite.freigegebenAm().toString(), seite.freigegebenVon(), List.copyOf(hinweise),
                    link(seite.pfad()), seite.vertraulichkeit());
        });
    }

    private static Quelle quelle(Quelleneintrag quelle, WissenLesen.QuellenAnzeige anzeige,
                                 Map<String, Integer> haeufigkeit) {
        if (anzeige == null) {
            throw new IllegalStateException("Quelle der sichtbaren Wissensseite nicht sichtbar");
        }
        int anzahl = haeufigkeit.getOrDefault(quelle.schluessel(), 0);
        if (quelle.dokumentId() != null) {
            String seiten = quelle.seiteVon() == null ? null : quelle.seiteBis() == null
                    || quelle.seiteVon().equals(quelle.seiteBis()) ? quelle.seiteVon().toString()
                    : quelle.seiteVon() + "-" + quelle.seiteBis();
            return new Quelle(quelle.schluessel(), quelle.dokumentId(), anzeige.dokumentTitel(), seiten, null,
                    "/dokument/" + quelle.dokumentId(), anzahl);
        }
        return new Quelle(quelle.schluessel(), null, anzeige.seiteTitel(), null, anzeige.seitePfad(),
                link(anzeige.seitePfad()), anzahl);
    }

    private static IndexZeile zeile(WissenLesenRepository.IndexZeile seite) {
        List<String> hinweise = new ArrayList<>();
        if ("VERALTET".equalsIgnoreCase(seite.status())) hinweise.add("VERALTET");
        if (seite.quellePruefen()) hinweise.add("QUELLE_PRUEFEN");
        return new IndexZeile(seite.pfad(), seite.titel(), seite.typ().datenbankwert(), seite.beschreibung(),
                List.copyOf(hinweise), link(seite.pfad()), seite.vertraulichkeit());
    }

    private static List<String> hinweise(Wissensseite seite) {
        List<String> hinweise = new ArrayList<>();
        if (seite.status() == Wissensseite.Status.VERALTET) hinweise.add("VERALTET");
        if (seite.veraltetAb() != null && !seite.veraltetAb().isAfter(LocalDate.now(ZoneId.of("Europe/Zurich"))))
            hinweise.add("ABGELAUFEN");
        if (seite.widerspruchOffen()) hinweise.add("WIDERSPRUCH");
        if (seite.quellePruefen()) hinweise.add("QUELLE_PRUEFEN");
        return hinweise;
    }

    private static String link(String pfad) {
        return "/wissen/" + pfad;
    }
}
