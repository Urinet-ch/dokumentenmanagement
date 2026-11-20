package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ManuelleNeuerzeugungSpeichern {
    private final FreigabeRepository freigabe;
    private final WissenIngestPort daten;
    private final WissenHuelleRepository huellen;
    private final WissenIngestSpeichern ingest;
    private final WissenKonfiguration konfiguration;
    private final AuftragService auftraege;
    private final DiffDienst diff;
    private final FreigabePruefung pruefung;

    public ManuelleNeuerzeugungSpeichern(FreigabeRepository freigabe, WissenIngestPort daten,
                                         WissenHuelleRepository huellen, WissenIngestSpeichern ingest,
                                         WissenKonfiguration konfiguration, AuftragService auftraege,
                                         DiffDienst diff, FreigabePruefung pruefung) {
        this.freigabe = freigabe;
        this.daten = daten;
        this.huellen = huellen;
        this.ingest = ingest;
        this.konfiguration = konfiguration;
        this.auftraege = auftraege;
        this.diff = diff;
        this.pruefung = pruefung;
    }

    @Transactional
    public boolean istManuell(UUID entwurfId) {
        return freigabe.neuerzeugungsDaten(entwurfId).map(d -> "MANUELL".equals(d.anlass())).orElse(false);
    }

    @Transactional
    public Optional<Stand> laden(Auftrag auftrag, UUID entwurfId, int nummer) {
        auftraege.anspruchSichern(auftrag);
        huellen.sperren();
        return stand(entwurfId, nummer);
    }

    private Optional<Stand> stand(UUID entwurfId, int nummer) {
        var lauf = freigabe.neuerzeugungsDaten(entwurfId);
        var aenderung = freigabe.sperren(entwurfId, nummer);
        if (lauf.isEmpty() || !"MANUELL".equals(lauf.get().anlass()) || aenderung.isEmpty()
                || !"VERALTET".equals(aenderung.get().status()) || aenderung.get().seiteId() == null) {
            return Optional.empty();
        }
        var alt = freigabe.vorschlagLaden(aenderung.get());
        var fassung = daten.seiteLaden(aenderung.get().seiteId());
        if (fassung.isEmpty() || !fassung.get().metadaten().pfad().equals(aenderung.get().pfad())) {
            daten.neuerzeugungAbbrechen(entwurfId, "Die aktuelle Seite fehlt. Bitte die Änderung verwerfen.");
            return Optional.empty();
        }
        Wissensseite basis = seite(fassung.get());
        if (!ingest.vorschlagFuerKiZulaessig(alt.fassung(), WissensStufe.INTERN)
                || !ingest.vorschlagFuerKiZulaessig(basis, WissensStufe.INTERN)) {
            daten.neuerzeugungAbbrechen(entwurfId,
                    "Die Quellen der Änderung sind für die KI nicht zulässig. Bitte die Änderung von Hand überarbeiten.");
            return Optional.empty();
        }
        Set<UUID> dokumente = dokumente(alt.fassung(), basis);
        Set<UUID> quellseiten = quellseiten(alt.fassung(), basis);
        WissensGraph graph = huellen.graphLaden(dokumente);
        WissensStufe stufe = graph.wirksameStufeNachAnnahme(dokumente, quellseiten,
                WissensStufe.valueOf(fassung.get().metadaten().vertraulichkeitMindest()),
                WissensStufe.valueOf(alt.kontextstufe()));
        var monat = WissenIngestStarten.monat(Instant.now());
        var einstellungen = daten.budgetEinstellungenLaden();
        return Optional.of(new Stand(aenderung.get(), alt, fassung.get(), graph, stufe, lauf.get(),
                daten.verbrauchImZeitraum(monat.von(), monat.bis()), einstellungen));
    }

    @Transactional
    public void sichern(Auftrag auftrag, UUID entwurfId, int nummer, Stand erwartet, Wissensseite neu,
                         List<ch.urinet.archiv.ki.application.KiPort.Widerspruch> widersprueche,
                         List<ch.urinet.archiv.ki.application.KiPort.EntfernteAussage> entfernt,
                         String begruendung, long tokensEin, long tokensAus) {
        auftraege.anspruchSichern(auftrag);
        huellen.sperren();
        Optional<Stand> geladen = stand(entwurfId, nummer);
        if (geladen.isEmpty()) {
            return;
        }
        Stand aktuell = geladen.get();
        if (!aktuell.aenderung().equals(erwartet.aenderung()) || !aktuell.basis().equals(erwartet.basis())
                || !aktuell.vorschlag().equals(erwartet.vorschlag()) || !aktuell.graph().equals(erwartet.graph())
                || aktuell.stufe() != erwartet.stufe()) {
            daten.neuerzeugungAbbrechen(entwurfId,
                    "Die Seite oder ihre Quellen haben sich geändert. Bitte die Änderung erneut erzeugen.");
            return;
        }
        Set<UUID> dokumente = dokumente(neu);
        Set<UUID> quellseiten = quellseiten(neu);
        quellseiten.forEach(id -> dokumente.addAll(aktuell.graph().dokumente(id)));
        List<WissenIngestPort.SeitenMetadaten> seiten = daten.seitenMetadatenLaden();
        Set<String> begruendet = entfernt.stream().filter(e -> e.schluessel() != null
                        && e.begruendung() != null && !e.begruendung().isBlank())
                .map(ch.urinet.archiv.ki.application.KiPort.EntfernteAussage::schluessel)
                .collect(java.util.stream.Collectors.toSet());
        WissensPruefung.Kontext kontext = new WissensPruefung.Kontext(
                konfiguration.ingest().seiteMaxZeichen(), aktuell.basis().metadaten().pfad(),
                seiten.stream().map(WissenIngestPort.SeitenMetadaten::pfad)
                        .collect(java.util.stream.Collectors.toSet()),
                daten.dokumentQuellenLaden(dokumente), aktuell.graph().mindeststufen().keySet(),
                aktuell.basis().quellen().stream().map(Quelleneintrag::schluessel)
                        .collect(java.util.stream.Collectors.toSet()),
                begruendet, Set.of(), false, "VERALTEN".equals(aktuell.aenderung().aktion()), false,
                Wissensseite.Vertraulichkeit.valueOf(aktuell.graph().wirksameStufe(neu.id(),
                        aktuell.graph().dokumente(neu.id())).name()), null);
        var ergebnis = pruefung.pruefen(neu, aktuell.graph(),
                WissensStufe.valueOf(aktuell.basis().metadaten().vertraulichkeitMindest()), aktuell.stufe(),
                null, kontext);
        var aenderung = new WissenIngestPort.NeueAenderung(entwurfId, nummer, aktuell.aenderung().aktion(), neu.id(),
                neu.pfad(), aktuell.basis().metadaten().version(), neu, begruendung, widersprueche, entfernt,
                ergebnis.pruefung().befunde(), ergebnis.pruefung().hinweise(), diff.vergleichen(seite(aktuell.basis()),
                neu), aktuell.stufe().name());
        freigabe.neuerzeugungsModellSetzen(entwurfId, konfiguration.modell());
        daten.erneuerteAenderungSpeichern(aenderung, WissenIngestPort.Fortschritt.leer(),
                (long) aktuell.lauf().tokensEin() + tokensEin, (long) aktuell.lauf().tokensAus() + tokensAus);
    }

    @Transactional
    public void abbrechen(Auftrag auftrag, UUID entwurfId, String hinweis) {
        auftraege.anspruchSichern(auftrag);
        if (freigabe.neuerzeugungsDaten(entwurfId).filter(d -> "MANUELL".equals(d.anlass())).isPresent()) {
            daten.neuerzeugungAbbrechen(entwurfId, hinweis);
        }
    }

    @Transactional
    public void nachFehlversuch(Auftrag auftrag, UUID entwurfId, int nummer) {
        huellen.sperren();
        var aenderung = freigabe.sperren(entwurfId, nummer);
        if (aenderung.isPresent() && freigabe.neuerzeugungsDaten(entwurfId).isPresent()
                && !freigabe.neuererNeuerzeugungsauftrag(entwurfId, auftrag.mandant().wert(), auftrag.id())) {
            if ("MANUELL".equals(aenderung.get().anlass())) {
                daten.neuerzeugungAbbrechen(entwurfId,
                        "Die Änderung konnte nach drei Versuchen nicht neu erzeugt werden. Bitte erneut versuchen.");
            } else if ("INGEST".equals(aenderung.get().anlass())) {
                ingest.nachFehlversuch(entwurfId);
            }
        }
    }

    static Wissensseite seite(WissenIngestPort.SeitenFassung fassung) {
        var m = fassung.metadaten();
        return new Wissensseite(m.id(), m.pfad(), Seitentyp.vonDatenbankwert(m.typ()).orElseThrow(), m.titel(),
                m.beschreibung(), m.tags(), Wissensseite.Status.valueOf(m.status()), null, fassung.quellen(),
                Wissensseite.Vertraulichkeit.valueOf(m.vertraulichkeitMindest()), fassung.inhalt(), m.version(),
                null, null, null, null, false, false, fassung.wissensquelleId());
    }

    static Set<UUID> dokumente(Wissensseite... seiten) {
        Set<UUID> ids = new HashSet<>();
        for (Wissensseite seite : seiten) {
            if (seite.wissensquelleId() != null) {
                ids.add(seite.wissensquelleId());
            }
            seite.quellen().stream().map(Quelleneintrag::dokumentId).filter(java.util.Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    static Set<UUID> quellseiten(Wissensseite... seiten) {
        Set<UUID> ids = new HashSet<>();
        for (Wissensseite seite : seiten) {
            seite.quellen().stream().map(Quelleneintrag::quellSeiteId).filter(java.util.Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    public record Stand(FreigabeRepository.Aenderung aenderung, FreigabeRepository.Vorschlag vorschlag,
                        WissenIngestPort.SeitenFassung basis, WissensGraph graph, WissensStufe stufe,
                        FreigabeRepository.NeuerzeugungsDaten lauf, long monatsverbrauch,
                        WissenIngestPort.BudgetEinstellungen budget) {
    }
}
