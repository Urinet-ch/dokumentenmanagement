package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Prüft den Seed eines Mandanten, bevor die Provisionierung etwas anlegt (Review PR #40): Jede Vorlage muss eine
 * gültige {@link Dokumentart} ergeben (Domain-Regeln) und durch die {@link DokumentartPruefung} gehen (Zusatz-Schema,
 * Pflichtfelder). Sonst {@code SEED_UNGUELTIG}, statt dass die Art erst beim Lesen zur Laufzeit 500 ergibt.
 */
@ApplicationScoped
public class SeedPruefung {

    private final DokumentartPruefung pruefung;

    public SeedPruefung(DokumentartPruefung pruefung) {
        this.pruefung = pruefung;
    }

    public void pruefen(MandantCode mandant, List<DokumentartVorlage> vorlagen) {
        for (DokumentartVorlage vorlage : vorlagen) {
            try {
                pruefung.pruefen(art(vorlage));
            } catch (FachFehler e) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed für Mandant " + mandant.wert()
                        + ": Dokumentart " + vorlage.code() + " ist ungültig (" + e.code().name() + ")", e);
            }
        }
    }

    /** Wie das Repository eine gespeicherte Art liest: ohne Schema gilt {@link Dokumentart#OHNE_ZUSATZ}. */
    static Dokumentart art(DokumentartVorlage v) {
        AufbewahrungAb ab = v.aufbewahrungAb() == null ? null
                : AufbewahrungAb.aus(v.aufbewahrungAb()).orElseThrow(SeedPruefung::ungueltig);
        Aufbewahrungsregel regel = new Aufbewahrungsregel(v.aufbewahrungJahre(), ab);
        return new Dokumentart(v.code(), v.name(), v.beschreibung(), v.gruppen(), v.pflichtfelder(),
                v.zusatzSchemaJson() == null ? Dokumentart.OHNE_ZUSATZ : v.zusatzSchemaJson(),
                Vertraulichkeit.aus(v.vertraulichkeit()).orElseThrow(SeedPruefung::ungueltig), regel,
                v.gesetzlichePflicht(), v.aktiv());
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
