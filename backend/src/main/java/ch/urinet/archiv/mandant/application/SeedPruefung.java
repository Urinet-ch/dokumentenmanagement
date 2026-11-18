package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Prüft den Seed eines Mandanten, bevor die Provisionierung etwas anlegt (Review PR #40): Jede Vorlage muss eine
 * gültige {@link Dokumentart} ergeben (Domain-Regeln) und durch die {@link DokumentartPruefung} gehen (Zusatz-Schema,
 * Pflichtfelder). Sonst {@code SEED_UNGUELTIG}, statt dass die Art erst beim Lesen zur Laufzeit 500 ergibt. Der Seed
 * stammt aus dem Repo und läuft im Admin-Kommando, darum mit der Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}).
 */
@ApplicationScoped
public class SeedPruefung {

    private final DokumentartPruefung pruefung;

    public SeedPruefung(DokumentartPruefung pruefung) {
        this.pruefung = pruefung;
    }

    public void pruefen(MandantCode mandant, Konfiguration konfiguration) {
        if (!konfiguration.pruefen().isEmpty()) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG,
                    "Konfigurationsseed für Mandant " + mandant.wert() + " ist ungültig");
        }
        for (Art art : konfiguration.arten()) {
            try {
                pruefung.seedPruefen(art(art));
            } catch (FachFehler e) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed für Mandant " + mandant.wert()
                        + ": Dokumentart " + art.code() + " ist ungültig (" + e.code().name() + ")", e);
            }
        }
    }

    public void pruefen(MandantCode mandant, List<DokumentartVorlage> vorlagen) {
        for (DokumentartVorlage vorlage : vorlagen) {
            if (!wissensquelleFest(vorlage)) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed für Mandant " + mandant.wert()
                        + ": Dokumentart WISSENSQUELLE weicht von den festen Eigenschaften ab");
            }
            try {
                pruefung.seedPruefen(art(vorlage));
            } catch (FachFehler e) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed für Mandant " + mandant.wert()
                        + ": Dokumentart " + vorlage.code() + " ist ungültig (" + e.code().name() + ")", e);
            }
        }
    }

    private static Dokumentart art(Art v) {
        AufbewahrungAb ab = v.aufbewahrung().ab() == null ? null
                : AufbewahrungAb.aus(v.aufbewahrung().ab()).orElseThrow(SeedPruefung::ungueltig);
        return new Dokumentart(v.code(), v.name(), v.beschreibung(), v.gruppen(), v.pflichtfelder(),
                v.zusatzSchema(), Vertraulichkeit.aus(v.vertraulichkeit()).orElseThrow(SeedPruefung::ungueltig),
                new Aufbewahrungsregel(v.aufbewahrung().jahre(), ab, v.aufbewahrung().feld()),
                v.gesetzlichePflicht(), v.aktiv(), v.selbstAusgestellt(), v.belegnummerMuster(), v.fremdbelegArt(),
                v.ohneZahlungsdatenArt(), v.kiVorschlag(), v.kiFelder(), v.kiHinweise(), v.reihenfolge());
    }

    /**
     * Feste Eigenschaften von {@code WISSENSQUELLE} in jeder Konfiguration (Spec 04, Req 1.1 und 1.5, ergänzt durch
     * Spec 05, Req 2.6): Gruppe nur {@code frei}, mindestens die Pflichtfelder {@code titel} und {@code datum},
     * unbefristet, ohne gesetzliche Pflicht und ohne KI-Vorschlag. Gleiche Regel wie
     * {@code Konfiguration.wissensquellePruefen}, hier schon beim Anlegen des Mandanten. Andere Arten gelten als fest.
     */
    static boolean wissensquelleFest(DokumentartVorlage v) {
        if (!"WISSENSQUELLE".equals(v.code())) {
            return true;
        }
        return v.gruppen().equals(List.of("frei")) && v.pflichtfelder().containsAll(List.of("titel", "datum"))
                && v.aufbewahrungJahre() == null && v.aufbewahrungAb() == null && v.aufbewahrungFeld() == null
                && !v.gesetzlichePflicht() && !v.kiVorschlag();
    }

    /** Wie das Repository eine gespeicherte Art liest: ohne Schema gilt {@link Dokumentart#OHNE_ZUSATZ}. */
    static Dokumentart art(DokumentartVorlage v) {
        AufbewahrungAb ab = v.aufbewahrungAb() == null ? null
                : AufbewahrungAb.aus(v.aufbewahrungAb()).orElseThrow(SeedPruefung::ungueltig);
        Aufbewahrungsregel regel = new Aufbewahrungsregel(v.aufbewahrungJahre(), ab, v.aufbewahrungFeld());
        return new Dokumentart(v.code(), v.name(), v.beschreibung(), v.gruppen(), v.pflichtfelder(),
                v.zusatzSchemaJson() == null ? Dokumentart.OHNE_ZUSATZ : v.zusatzSchemaJson(),
                Vertraulichkeit.aus(v.vertraulichkeit()).orElseThrow(SeedPruefung::ungueltig), regel,
                v.gesetzlichePflicht(), v.aktiv());
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
