package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class FreigabeKontext {
    private final WissenIngestPort daten;
    private final WissenEntwurfLesenRepository entwuerfe;
    private final WissenHuelleRepository huellen;
    private final WissenSichtbarkeit sichtbarkeit;
    private final WissenKonfiguration konfiguration;
    private final FreigabePruefung pruefung;

    public FreigabeKontext(WissenIngestPort daten, WissenEntwurfLesenRepository entwuerfe,
                           WissenHuelleRepository huellen, WissenSichtbarkeit sichtbarkeit,
                           WissenKonfiguration konfiguration, FreigabePruefung pruefung) {
        this.daten = daten;
        this.entwuerfe = entwuerfe;
        this.huellen = huellen;
        this.sichtbarkeit = sichtbarkeit;
        this.konfiguration = konfiguration;
        this.pruefung = pruefung;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public FreigabePruefung.Ergebnis pruefen(Benutzer benutzer, UUID entwurfId,
                                             FreigabeRepository.Aenderung aenderung,
                                             FreigabeRepository.Vorschlag vorschlag, Wissensseite fassung,
                                             String gewuenschteStufe) {
        Set<UUID> dokumente = fassung.quellen().stream().map(Quelleneintrag::dokumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> zitate = fassung.quellen().stream().map(Quelleneintrag::quellSeiteId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (fassung.wissensquelleId() != null) {
            dokumente.add(fassung.wissensquelleId());
        }
        WissensGraph graph = huellen.graphLaden(dokumente);
        Set<UUID> quellenDokumente = new HashSet<>(dokumente);
        zitate.forEach(id -> quellenDokumente.addAll(graph.dokumente(id)));
        List<WissenIngestPort.SeitenMetadaten> seiten = daten.seitenMetadatenLaden();
        WissenIngestPort.SeitenFassung basis = aenderung.seiteId() == null ? null
                : daten.seiteLaden(aenderung.seiteId()).orElseThrow(
                        () -> new IllegalStateException("Gesperrte Wissensseite nicht vorhanden"));
        Set<String> basisSchluessel = basis == null ? Set.of() : basis.quellen().stream()
                .map(Quelleneintrag::schluessel).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> kernaussagen = entwuerfe.kernaussagen(entwurfId).stream()
                .filter(WissenIngestPort.Kernaussage::belegt).map(WissenIngestPort.Kernaussage::schluessel)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        WissensStufe mindeststufe = basis == null ? WissensStufe.NORMAL
                : WissensStufe.valueOf(basis.metadaten().vertraulichkeitMindest());
        Wissensseite.Vertraulichkeit bisher = basis == null ? null
                : Wissensseite.Vertraulichkeit.valueOf(graph.wirksameStufe(aenderung.seiteId(),
                        graph.dokumente(aenderung.seiteId())).name());
        WissensPruefung.Kontext kontext = new WissensPruefung.Kontext(
                konfiguration.ingest().seiteMaxZeichen(), basis == null ? null : basis.metadaten().pfad(),
                seiten.stream().map(WissenIngestPort.SeitenMetadaten::pfad).collect(Collectors.toSet()),
                daten.dokumentQuellenLaden(quellenDokumente), Set.copyOf(sichtbarkeit.sichtbareSeiten(benutzer)),
                basisSchluessel, vorschlag.entfernteAussagen(), kernaussagen, "INGEST".equals(aenderung.anlass()),
                "VERALTEN".equals(aenderung.aktion()), false, bisher, null);
        return pruefung.pruefen(fassung, graph, mindeststufe, WissensStufe.valueOf(vorschlag.kontextstufe()),
                gewuenschteStufe, kontext);
    }
}
