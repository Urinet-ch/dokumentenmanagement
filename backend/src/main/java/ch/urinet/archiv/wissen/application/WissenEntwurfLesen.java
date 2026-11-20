package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class WissenEntwurfLesen {
    private static final Set<String> STATUS = Set.of("WARTEND", "IN_ARBEIT", "OFFEN", "ERLEDIGT", "FEHLER",
            "ABGELEHNT");
    private final WissenEntwurfLesenRepository repository;
    private final Berechtigung berechtigung;

    public WissenEntwurfLesen(WissenEntwurfLesenRepository repository, Berechtigung berechtigung) {
        this.repository = repository;
        this.berechtigung = berechtigung;
    }

    @Transactional
    public List<ListenEintrag> liste(Benutzer benutzer, String status) {
        boolean voll = Rechtematrix.ergebnis(benutzer.rolle(), Aktion.A17).zugaenglich();
        berechtigung.aktion(benutzer, voll ? Aktion.A17 : Aktion.A18);
        if (status != null && !STATUS.contains(status)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return repository.liste(status, voll ? null : benutzer.id()).stream()
                .<ListenEintrag>map(k -> voll ? new VollerListenEintrag(k) : new StatusListenEintrag(k.id(), k.status()))
                .toList();
    }

    @Transactional
    public Ansicht lesen(Benutzer benutzer, UUID id) {
        Optional<WissenEntwurfLesenRepository.Kopf> kopf = repository.kopf(id);
        Optional<String> vorschlagender = kopf.filter(k -> "ANALYSE".equals(k.anlass()))
                .map(WissenEntwurfLesenRepository.Kopf::erstelltVon);
        Berechtigung.Wissensentwurfszugriff zugriff = berechtigung.wissensentwurfLesen(benutzer, vorschlagender);
        WissenEntwurfLesenRepository.Kopf entwurf = kopf.orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (zugriff == Berechtigung.Wissensentwurfszugriff.NUR_STATUS_ENTSCHEID_GRUND) {
            return new StatusAnsicht(entwurf.id(), entwurf.status(), repository.entscheidungen(id));
        }
        List<WissenEntwurfLesenRepository.Aenderung> aenderungen = repository.aenderungen(id);
        Set<String> genutzteSchluessel = aenderungen.stream().flatMap(a -> genutzteQuellen(a).stream())
                .collect(Collectors.toSet());
        List<Aussage> aussagen = repository.kernaussagen(id).stream()
                .map(a -> new Aussage(a.nr(), a.schluessel(), a.text(), a.seiten(), a.auszug(), a.belegt(),
                        genutzteSchluessel.contains(a.schluessel()))).toList();
        return new Vollansicht(entwurf, aussagen, aenderungen);
    }

    private static Set<String> quellenSchluessel(java.util.Map<String, Object> daten) {
        Object quellen = daten.get("quellen");
        if (!(quellen instanceof List<?> liste)) {
            return Set.of();
        }
        return liste.stream().map(quelle -> quelle instanceof java.util.Map<?, ?> map ? map.get("schluessel") : null)
                .filter(String.class::isInstance)
                .map(String.class::cast).collect(Collectors.toSet());
    }

    private static Set<String> genutzteQuellen(WissenEntwurfLesenRepository.Aenderung aenderung) {
        Set<String> fussnoten = WissensMarkdown.analysieren(aenderung.inhalt(),
                WissensMarkdown.MAX_EINGABE_ZEICHEN).fussnoten();
        return quellenSchluessel(aenderung.daten()).stream().filter(fussnoten::contains)
                .collect(Collectors.toSet());
    }

    public sealed interface Ansicht permits Vollansicht, StatusAnsicht {
    }

    public sealed interface ListenEintrag permits VollerListenEintrag, StatusListenEintrag {
    }

    public record VollerListenEintrag(WissenEntwurfLesenRepository.Kopf entwurf) implements ListenEintrag {
    }

    public record StatusListenEintrag(UUID id, String status) implements ListenEintrag {
    }

    public record Vollansicht(WissenEntwurfLesenRepository.Kopf entwurf, List<Aussage> kernaussagen,
                             List<WissenEntwurfLesenRepository.Aenderung> aenderungen) implements Ansicht {
    }

    public record StatusAnsicht(UUID id, String status, List<Entscheidung> entscheidungen) implements Ansicht {
    }

    public record Entscheidung(int nr, String status, String grund) {
    }

    public record Aussage(int nr, String schluessel, String text, List<Integer> seiten, String auszug,
                          boolean belegt, boolean zugeordnet) {
    }
}
