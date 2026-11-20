package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class WissenIngestIndex {
    private final WissenIngestSpeichern speichern;
    private final WissensSuche suche;
    private final WissenKonfiguration konfiguration;

    public WissenIngestIndex(WissenIngestSpeichern speichern, WissensSuche suche, WissenKonfiguration konfiguration) {
        this.speichern = speichern;
        this.suche = suche;
        this.konfiguration = konfiguration;
    }

    public List<SeitenMetadaten> laden(UUID entwurfId, String frage) {
        int grenze = konfiguration.ingest().indexMaxZeichen();
        List<SeitenMetadaten> sichtbar = kontext(entwurfId);
        List<SeitenMetadaten> index = sichtbar;
        if (sichtbar.stream().mapToInt(WissenIngestSpeichern::indexZeichen).sum() > grenze) {
            var ergebnis = suche.rangieren(frage, () -> new WissensSuchePort.Filter(null, null, null, null,
                    kontext(entwurfId).stream().map(SeitenMetadaten::id).collect(Collectors.toUnmodifiableSet())),
                    30, true);
            Map<UUID, SeitenMetadaten> aktuell = kontext(entwurfId).stream()
                    .collect(Collectors.toMap(SeitenMetadaten::id, Function.identity()));
            var auswahl = new ArrayList<SeitenMetadaten>();
            sichtbar.stream().filter(s -> "ueberblick".equals(s.pfad()) && aktuell.containsKey(s.id()))
                    .findFirst().ifPresent(s -> auswahl.add(aktuell.get(s.id())));
            for (var seite : ergebnis.treffer()) {
                if (aktuell.containsKey(seite.id()) && auswahl.stream().noneMatch(s -> s.id().equals(seite.id()))) {
                    auswahl.add(aktuell.get(seite.id()));
                }
            }
            index = auswahl;
        }
        var begrenzt = new ArrayList<SeitenMetadaten>();
        int zeichen = 0;
        for (var seite : index) {
            int laenge = WissenIngestSpeichern.indexZeichen(seite);
            if (zeichen + laenge > grenze) break;
            begrenzt.add(seite);
            zeichen += laenge;
        }
        return List.copyOf(begrenzt);
    }

    private List<SeitenMetadaten> kontext(UUID entwurfId) {
        var stand = speichern.laden(entwurfId, java.util.Set.of())
                .orElseThrow(() -> new IllegalStateException("Ingest-Entwurf nicht gefunden"));
        if (!WissenIngestSpeichern.freigegeben(stand.lauf()) || stand.quellStufe() == null) return List.of();
        return speichern.sichtbareSeiten(stand).stream()
                .filter(s -> stand.quellStufe() != WissensStufe.INTERN || !"ueberblick".equals(s.pfad())).toList();
    }
}
