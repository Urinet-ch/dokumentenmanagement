package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class FreigabePruefung {
    public record Ergebnis(WissensStufe mindeststufe, WissensStufe wirksameStufe, WissensPruefung.Ergebnis pruefung) {
    }

    public Ergebnis pruefen(Wissensseite fassung, WissensGraph graph, WissensStufe bisherigeMindeststufe,
                            WissensStufe kontextstufe, String gewuenschteStufe, WissensPruefung.Kontext kontext) {
        Objects.requireNonNull(fassung, "fassung");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(bisherigeMindeststufe, "bisherigeMindeststufe");
        Objects.requireNonNull(kontextstufe, "kontextstufe");
        Objects.requireNonNull(kontext, "kontext");

        Set<UUID> dokumente = fassung.quellen().stream().map(Quelleneintrag::dokumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> quellseiten = fassung.quellen().stream().map(Quelleneintrag::quellSeiteId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        WissensStufe berechnet = graph.wirksameStufeNachAnnahme(dokumente, quellseiten, bisherigeMindeststufe,
                kontextstufe);
        WissensStufe mindeststufe = graph.mindeststufeNachAnnahme(quellseiten, bisherigeMindeststufe, kontextstufe);
        if (gewuenschteStufe != null) {
            try {
                WissensStufe gewuenscht = WissensStufe.valueOf(gewuenschteStufe);
                if (gewuenscht.hoechste(berechnet) != gewuenscht
                        && !("ueberblick".equals(fassung.pfad()) && berechnet == WissensStufe.INTERN)) {
                    throw new FachFehler(Fehlercode.VERTRAULICHKEIT_ZU_TIEF);
                }
                mindeststufe = mindeststufe.hoechste(gewuenscht);
            } catch (IllegalArgumentException e) {
                throw new FachFehler(Fehlercode.VERTRAULICHKEIT_UNGUELTIG);
            }
        }
        WissensStufe wirksam = berechnet.hoechste(mindeststufe);
        WissensPruefung.Kontext aktuell = new WissensPruefung.Kontext(kontext.maxZeichen(), kontext.bisherigerPfad(),
                kontext.belegtePfade(), kontext.dokumente(), kontext.seitenIds(), kontext.basisQuellenSchluessel(),
                kontext.entfernteAussagen(), kontext.kernaussagenSchluessel(), kontext.ingest(), kontext.veralten(),
                kontext.pfadHoehereStufe(), kontext.bisherigeVertraulichkeit(),
                Wissensseite.Vertraulichkeit.valueOf(wirksam.name()));
        Wissensseite gepruefteFassung = new Wissensseite(fassung.id(), fassung.pfad(), fassung.typ(), fassung.titel(),
                fassung.beschreibung(), fassung.tags(), fassung.status(), fassung.veraltetAb(), fassung.quellen(),
                Wissensseite.Vertraulichkeit.valueOf(wirksam.name()), fassung.inhalt(), fassung.version(),
                fassung.erzeugtVon(), fassung.erzeugtAm(), fassung.freigegebenVon(), fassung.freigegebenAm(),
                fassung.quellePruefen(), fassung.widerspruchOffen(), fassung.wissensquelleId());
        return new Ergebnis(mindeststufe, wirksam, WissensPruefung.pruefen(gepruefteFassung, aktuell));
    }
}
