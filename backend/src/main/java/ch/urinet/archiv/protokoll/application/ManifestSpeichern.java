package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.application.ManifestDaten.Blatt;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Fristen;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;
import ch.urinet.archiv.protokoll.application.ManifestDaten.NeuesManifest;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kurze Transaktionen von {@link ManifestErzeugen} (design.md «Pflichten der Handler», (2)): Lesen ohne, Schreiben mit
 * gesichertem Anspruch auf den Auftrag. Eigene Klasse, weil {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class ManifestSpeichern {

    static final List<String> PROTOKOLLE = List.of(ProtokollHash.DOKUMENT_VERSION, ProtokollHash.ZUGRIFF_PROTOKOLL);

    private final ManifestDaten daten;
    private final AuftragService auftraege;

    public ManifestSpeichern(ManifestDaten daten, AuftragService auftraege) {
        this.daten = daten;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<ManifestStand> letztes() {
        return daten.letztes();
    }

    /** Einträge, Fristen und Blätter des Fensters. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Sammlung sammeln(ManifestFenster fenster) {
        Map<String, List<Blatt>> blaetter = new LinkedHashMap<>();
        for (String tabelle : PROTOKOLLE) {
            blaetter.put(tabelle, daten.blaetter(tabelle, fenster.von(), fenster.bis()));
        }
        return new Sammlung(daten.eintraege(fenster.von(), fenster.bis()), daten.fristen(fenster.von(), fenster.bis()),
                blaetter);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void festhalten(Auftrag auftrag, NeuesManifest manifest) {
        auftraege.anspruchSichern(auftrag);
        daten.festhalten(manifest);
    }

    /** Reiht die übrigen Tage als neuen Auftrag ein, wenn ein Lauf seine Höchstzahl erreicht hat. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long folgeauftrag(Auftrag auftrag, LocalDate bisTag) {
        auftraege.anspruchSichern(auftrag);
        return auftraege.einreihen(ManifestErzeugen.TYP, new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG,
                bisTag.toEpochDay())));
    }

    public record Sammlung(List<ManifestEintrag> eintraege, Fristen fristen, Map<String, List<Blatt>> blaetter) {
        public Sammlung {
            eintraege = List.copyOf(eintraege);
            blaetter = Map.copyOf(blaetter);
        }
    }
}
