package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.WissenIngestPort.BudgetReservierung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Kernaussage;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Lauf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeueAenderung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenFassung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenVorschlag;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern.Stand;
import ch.urinet.archiv.wissen.adapter.out.diff.JavaWissenZeilendiff;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenIngestVerarbeitenTest {

    private static final UUID ENTWURF_ID = UUID.randomUUID();
    private static final UUID DOKUMENT_ID = UUID.randomUUID();

    @Test
    void speichertGueltigeAntwortAuchWennSieDasIngestBudgetErreicht() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("KERNAUSSAGEN", false, Fortschritt.leer()), 0));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Kernaussagen(List.of(
                new KiPort.Aussage(1, "Die Frist endet am 30. April.", List.of(1), "Frist endet am 30. April.")
        )), new KiPort.Verbrauch("test-modell", 0, 300_000)));
        Verbrauch verbrauch = new Verbrauch();

        verarbeiten(speichern, ki, verbrauch).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.neuerSchritt).isEqualTo("ABSCHLUSS");
        assertThat(speichern.unvollstaendig).isTrue();
        assertThat(speichern.fortschritt.kernaussagen()).singleElement()
                .satisfies(aussage -> assertThat(aussage.belegt()).isTrue());
        assertThat(ki.aufrufe).isEqualTo(1);
        assertThat(verbrauch.entwurfId).isEqualTo(ENTWURF_ID);
        assertThat(speichern.freigegebeneReservierung).isEqualTo(speichern.reservierungId);
        assertThat(speichern.fortschritt.kontextStufe()).isEqualTo(WissensStufe.NORMAL);
    }

    @Test
    void begrenztMaxTokensUndReserviertNurEingabeUndAusgabeStattDesRestbudgets() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("KERNAUSSAGEN", false, Fortschritt.leer()), 0));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Kernaussagen(List.of()),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        // seite-max-zeichen 20'000: 2 × 20'000 Zeichen × 13 / 30 Tokens je Zeichen, aufgerundet
        assertThat(ki.maxAusgabe).isEqualTo(17_334);
        assertThat(speichern.reservierteTokens).isGreaterThan(17_334).isLessThan(17_334 + 20_000);
    }

    @Test
    void schliesstMitFehlerAbWennDieVertraulichkeitWaehrendDesIngestsSinkt() throws InterruptedException {
        for (String schritt : List.of("KERNAUSSAGEN", "PLANUNG", "SEITE", "QUELLE", "ABSCHLUSS",
                "NEU_ERZEUGEN")) {
            Speicher speichern = new Speicher(stand(lauf(schritt, false,
                    mitKontextStufe(mitAussage(), WissensStufe.INTERN)), 0, WissensStufe.NORMAL));
            KiAntwort ki = new KiAntwort(null);

            verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

            assertThat(speichern.fehler).as(schritt).isEqualTo(WissenIngestVerarbeiten.STUFE_GESENKT);
            assertThat(ki.aufrufe).as(schritt).isZero();
            assertThat(speichern.aenderungenGesichert).as(schritt).isFalse();
            assertThat(speichern.neuerSchritt).as(schritt).isNull();
        }
    }

    @Test
    void merktSichEineGestiegeneVertraulichkeitFuerDieFolgendenSchritte() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("KERNAUSSAGEN", false,
                mitKontextStufe(Fortschritt.leer(), WissensStufe.NORMAL)), 0, WissensStufe.INTERN));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Kernaussagen(List.of()),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.fehler).isNull();
        assertThat(speichern.fortschritt.kontextStufe()).isEqualTo(WissensStufe.INTERN);
    }

    @Test
    void meldetErschoepftesMonatsbudgetAlsFehlerOhneKIAufruf() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("KERNAUSSAGEN", false, Fortschritt.leer()), 1_000_000));
        KiAntwort ki = new KiAntwort(null);

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(ki.aufrufe).isZero();
        assertThat(speichern.fehler).isEqualTo("Budget erschöpft");
    }

    @Test
    void traegtUnvollstaendigkeitBisZumAbschlussWeiter() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("ABSCHLUSS", true, Fortschritt.leer()), 0));

        verarbeiten(speichern, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.aenderungenGesichert).isTrue();
        assertThat(speichern.unvollstaendig).isTrue();
    }

    @Test
    void zeitueberschreitungIstEinFehlversuchUndDerLetzteSchliesstMitFehlerAb() {
        Speicher speichern = new Speicher(stand(lauf("KERNAUSSAGEN", false, Fortschritt.leer()), 0));
        KiAntwort ki = new KiAntwort(new KiPort.WissensAusfall("Zeitüberschreitung",
                new KiPort.Verbrauch("test-modell", 0, 0)));

        assertThatThrownBy(() -> verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(speichern.fehler).isNull();
        assertThat(speichern.freigegebeneReservierung).isEqualTo(speichern.reservierungId);
    }

    @Test
    void schliesstMitFehlerAbWennDieQuelleNichtMehrFreigegebenIst() throws InterruptedException {
        for (Lauf lauf : List.of(
                laufMit("KERNAUSSAGEN", "ARCHIVIERT", "WISSENSQUELLE", "PERSONAL", true, Fortschritt.leer()),
                laufMit("KERNAUSSAGEN", "ARCHIVIERT", "WISSENSQUELLE", "NORMAL", false, Fortschritt.leer()),
                laufMit("KERNAUSSAGEN", "ARCHIVIERT", "RECHNUNG", "NORMAL", true, Fortschritt.leer()),
                laufMit("KERNAUSSAGEN", "VERWORFEN", "WISSENSQUELLE", "NORMAL", true, Fortschritt.leer()))) {
            Speicher speichern = new Speicher(stand(lauf, 0));
            KiAntwort ki = new KiAntwort(null);

            verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

            assertThat(speichern.fehler).isEqualTo(WissenIngestVerarbeiten.NICHT_FREIGEGEBEN);
            assertThat(ki.aufrufe).isZero();
        }
    }

    @Test
    void verwendetDasStandardbudgetWennDasBudgetNullIst() throws InterruptedException {
        Lauf lauf = new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT", "WISSENSQUELLE", "NORMAL",
                true, "Testquelle", LocalDate.of(2026, 4, 30), "Die Frist endet am 30. April.\f", 1, true,
                null, null, "KERNAUSSAGEN", 0, 0, "test-modell", false, 0, 0, Fortschritt.leer());
        Speicher speichern = new Speicher(stand(lauf, 0));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Kernaussagen(List.of()),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(ki.aufrufe).isEqualTo(1);
        assertThat(speichern.neuerSchritt).isEqualTo("KERNAUSSAGEN");
    }

    @Test
    void leereSeitenplanungFuehrtZurQuelleSeiteOhneFehler() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("PLANUNG", false, mitAussage()), 0, WissensStufe.INTERN));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Planung(List.of()),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.fehler).isNull();
        assertThat(speichern.neuerSchritt).isEqualTo("QUELLE");
        assertThat(speichern.fortschritt.hinweise()).contains("Die Seitenplanung nennt keine betroffenen Seiten.");
    }

    @Test
    void planungOhneBestehendeSeiteAendertNichtUndVeraltetNichtsUndUebergehtVeralteteSeiten()
            throws InterruptedException {
        SeitenMetadaten veraltet = new SeitenMetadaten(UUID.randomUUID(), "alt", "Analyse", "Alt", "Alt",
                List.of(), "VERALTET", 2, null);
        Speicher speichern = new Speicher(stand(lauf("PLANUNG", false, mitAussage()), 0, WissensStufe.INTERN,
                List.of(veraltet), Map.of()));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Planung(List.of(
                new KiPort.Plan("fehlt", "AENDERN", "Analyse", "Grund", List.of(1)),
                new KiPort.Plan("weg", "VERALTEN", "Analyse", "Grund", List.of(1)),
                new KiPort.Plan("alt", "AENDERN", "Analyse", "Grund", List.of(1)))),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.fortschritt.planung()).singleElement().satisfies(plan -> {
            assertThat(plan.pfad()).isEqualTo("fehlt");
            assertThat(plan.aktion()).isEqualTo("NEU");
            assertThat(plan.seiteId()).isNull();
            assertThat(plan.pfadHoehereStufe()).isFalse();
        });
        assertThat(speichern.fortschritt.hinweise()).contains(
                "Die zu ändernde Seite «fehlt» fehlt; sie wird neu vorgeschlagen.",
                "Eine zu veraltende Seite fehlt oder ist nicht sichtbar; der Vorschlag wurde verworfen.",
                "Eine veraltete Seite belegt den Pfad «alt»; der Vorschlag wurde verworfen.");
    }

    @Test
    void ohneBelegdatumEntfaelltNurDieQuelleSeite() throws InterruptedException {
        Lauf ohneDatum = new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT", "WISSENSQUELLE", "NORMAL",
                true, "Testquelle", null, "Die Frist endet am 30. April.\f", 1, true,
                null, null, "QUELLE", 0, 0, "test-modell", false, 300_000, 1_000_000, mitAussage());
        Speicher speichern = new Speicher(stand(ohneDatum, 0));
        KiAntwort ki = new KiAntwort(null);

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(ki.aufrufe).isZero();
        assertThat(speichern.fehler).isNull();
        assertThat(speichern.neuerSchritt).isEqualTo("ABSCHLUSS");
        assertThat(speichern.fortschritt.hinweise()).contains("Ohne Belegdatum wurde keine Quelle-Seite erstellt.");
    }

    @Test
    void erneuterIngestBehaeltFruehereAussagenUndErsetztNurDieselbeTextstelle() throws Exception {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten metadaten = new SeitenMetadaten(seiteId, "quellen/2026-04-alt-12345678", "Quelle",
                "Alt", "Alt", List.of(), "AKTIV", 3, null);
        String inhalt = "## Zusammenfassung\n\nAlt. [^q-alt]\n\n## Kernaussagen\n\n- Alte Aussage. [^q-alt]\n"
                + "- Frühere Fassung. [^q-gleich]\n\n## Berührte Seiten\n\n- [x](/x)\n";
        SeitenFassung fassung = new SeitenFassung(metadaten, inhalt, DOKUMENT_ID, List.of(
                new Quelleneintrag("q-alt", DOKUMENT_ID, 1, 1, "Alte Aussage", null),
                new Quelleneintrag("q-gleich", DOKUMENT_ID, 1, 1, "Frist  endet am 30. April.", null),
                new Quelleneintrag("q-weg", DOKUMENT_ID, 1, 1, "Weg", null)));
        Speicher speichern = new Speicher(stand(lauf("QUELLE", false, mitAussage()), 0, WissensStufe.NORMAL,
                List.of(metadaten), Map.of(seiteId, fassung)));
        speichern.quelleSeite = Optional.of(seiteId);

        verarbeiten(speichern, quelleAntwort(), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenVorschlag vorschlag = speichern.fortschritt.vorschlaege().getLast();
        assertThat(vorschlag.aktion()).isEqualTo("AENDERN");
        assertThat(vorschlag.seiteId()).isEqualTo(seiteId);
        assertThat(vorschlag.basisVersion()).isEqualTo(3);
        assertThat(vorschlag.pfad()).isEqualTo(metadaten.pfad());
        assertThat(vorschlag.befunde()).isEmpty();
        assertThat(vorschlag.daten().inhalt()).contains("- Alte Aussage. [^q-alt]", "- Die Frist endet am 30. April. [^q-neu]")
                .doesNotContain("Frühere Fassung");
        assertThat(vorschlag.daten().inhalt().substring(vorschlag.daten().inhalt().indexOf("## Berührte Seiten")))
                .contains("- [x](/x)");
        assertThat(vorschlag.daten().quellen()).extracting(Quelleneintrag::schluessel)
                .containsExactlyInAnyOrder("q-alt", "q-neu");
        assertThat(vorschlag.daten().quellen()).filteredOn(q -> q.schluessel().equals("q-alt"))
                .singleElement().extracting(Quelleneintrag::auszug).isEqualTo("Alte Aussage");
        assertThat(vorschlag.entfernteAussagen()).extracting(KiPort.EntfernteAussage::schluessel)
                .containsExactlyInAnyOrder("q-gleich", "q-weg");
        assertThat(vorschlag.entfernteAussagen()).filteredOn(a -> a.schluessel().equals("q-gleich"))
                .singleElement().extracting(KiPort.EntfernteAussage::begruendung)
                .isEqualTo("Durch [^q-neu] mit derselben Textstelle ersetzt.");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Fortschritt gespeichert = mapper.readValue(mapper.writeValueAsString(speichern.fortschritt), Fortschritt.class);
        assertThat(gespeichert).isEqualTo(speichern.fortschritt);
        SeitenMetadaten spaetereMetadaten = new SeitenMetadaten(seiteId, metadaten.pfad(), "Quelle",
                "Später geändert", "Später geändert", List.of(), "AKTIV", 4, null);
        SeitenFassung spaetereFassung = new SeitenFassung(spaetereMetadaten, "Späterer Inhalt",
                DOKUMENT_ID, List.of());
        var erneuterPlan = WissenIngestVerarbeiten.neuerzeugungsPlan(vorschlag, gespeichert, spaetereFassung);
        assertThat(erneuterPlan.basisVersion()).isEqualTo(4);
        assertThat(erneuterPlan.aussagen()).containsExactly(1);
        Speicher abschluss = new Speicher(stand(lauf("ABSCHLUSS", false, gespeichert), 0,
                WissensStufe.NORMAL, List.of(spaetereMetadaten), Map.of(seiteId, spaetereFassung)));
        verarbeiten(abschluss, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(abschluss.aenderungen).singleElement().satisfies(aenderung -> {
            assertThat(aenderung.befunde()).extracting(WissensPruefung.Befund::code).doesNotContain(
                    WissensPruefung.Code.QUELLE_NICHT_BELEGT, WissensPruefung.Code.AUSSAGE_ENTFERNT);
            assertThat(aenderung.basisVersion()).isEqualTo(3);
            assertThat(aenderung.diff().titel().alt()).isEqualTo("Alt");
            assertThat(aenderung.diff().zeilen()).flatExtracting(WissenZeilendiff.Aenderung::alt)
                    .contains("- Frühere Fassung. [^q-gleich]");
        });

        ObjectNode alteDaten = mapper.valueToTree(gespeichert);
        ((ObjectNode) alteDaten.path("vorschlaege").get(0)).remove("diff");
        Fortschritt alterFortschritt = mapper.treeToValue(alteDaten, Fortschritt.class);
        Speicher unveraenderterAbschluss = new Speicher(stand(lauf("ABSCHLUSS", false, alterFortschritt), 0,
                WissensStufe.NORMAL, List.of(metadaten), Map.of(seiteId, fassung)));
        verarbeiten(unveraenderterAbschluss, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);
        assertThat(unveraenderterAbschluss.aenderungen).singleElement().satisfies(aenderung ->
                assertThat(aenderung.diff().titel().alt()).isEqualTo("Alt"));

        Speicher alterAbschluss = new Speicher(stand(lauf("ABSCHLUSS", false, alterFortschritt), 0,
                WissensStufe.NORMAL, List.of(spaetereMetadaten), Map.of(seiteId, spaetereFassung)));
        verarbeiten(alterAbschluss, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);
        assertThat(alterAbschluss.fehler).contains("Basisversion", "Ingest neu starten");
        assertThat(alterAbschluss.aenderungenGesichert).isFalse();
    }

    @Test
    void gleicherSchluesselMitAndererTextstelleUeberschreibtDieQuelleSeiteNicht() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten metadaten = new SeitenMetadaten(seiteId, "quellen/2026-04-alt-12345678", "Quelle",
                "Alt", "Alt", List.of(), "AKTIV", 3, null);
        String inhalt = "## Zusammenfassung\n\nAlt. [^q-neu]\n\n## Kernaussagen\n\n- Frühere Fassung. [^q-neu]\n";
        SeitenFassung fassung = new SeitenFassung(metadaten, inhalt, DOKUMENT_ID, List.of(
                new Quelleneintrag("q-neu", DOKUMENT_ID, 1, 1, "Frühere Fassung", null)));
        Speicher speichern = new Speicher(stand(lauf("QUELLE", false, mitAussage()), 0, WissensStufe.NORMAL,
                List.of(metadaten), Map.of(seiteId, fassung)));
        speichern.quelleSeite = Optional.of(seiteId);

        verarbeiten(speichern, quelleAntwort(), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenVorschlag vorschlag = speichern.fortschritt.vorschlaege().getLast();
        assertThat(vorschlag.daten().quellen()).singleElement().isEqualTo(fassung.quellen().getFirst());
        assertThat(vorschlag.daten().inhalt()).contains("- Frühere Fassung. [^q-neu]")
                .doesNotContain("Die Frist endet am 30. April.");
        assertThat(vorschlag.entfernteAussagen()).isEmpty();
        assertThat(vorschlag.befunde()).extracting(WissensPruefung.Befund::code)
                .containsExactly(WissensPruefung.Code.QUELLE_UNGUELTIG);
    }

    @Test
    void gleicherSchluesselMitAndererTextstelleUeberschreibtDieFachseiteNicht() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten metadaten = new SeitenMetadaten(seiteId, "konzepte/frist", "Konzept", "Frist", "Frist",
                List.of(), "AKTIV", 2, null);
        Quelleneintrag frueher = new Quelleneintrag("q-neu", DOKUMENT_ID, 1, 1, "Frühere Fassung", null);
        SeitenFassung fassung = new SeitenFassung(metadaten, "# Frist\n\nFrüher. [^q-neu]", null,
                List.of(frueher));
        Fortschritt geplant = new Fortschritt(1, 0, false, mitAussage().kernaussagen(), List.of(
                new WissenIngestPort.Planung("konzepte/frist", "AENDERN", "Konzept", "Grund", List.of(1), seiteId,
                        2, false)), List.of(), List.of(), null);
        Speicher speichern = new Speicher(stand(lauf("SEITE", false, geplant), 0, WissensStufe.NORMAL,
                List.of(metadaten), Map.of(seiteId, fassung)));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Frist", "Frist", List.of(),
                "# Frist\n\nDie Frist endet am 30. April. [^q-neu]", List.of(), List.of(), "Neue Frist"),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenVorschlag vorschlag = speichern.fortschritt.vorschlaege().getLast();
        assertThat(vorschlag.daten().quellen()).singleElement().isEqualTo(frueher);
        assertThat(vorschlag.befunde()).extracting(WissensPruefung.Befund::code)
                .containsExactly(WissensPruefung.Code.QUELLE_UNGUELTIG);
    }

    @Test
    void erneuterSeitenplanNimmtAktuelleBasisUndNurZugeordneteKernaussagen() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten alt = new SeitenMetadaten(seiteId, "konzepte/frist", "Konzept", "Frist", "Frist",
                List.of(), "AKTIV", 2, "NORMAL");
        SeitenFassung alteFassung = new SeitenFassung(alt, "# Frist", null, List.of());
        Fortschritt geplant = new Fortschritt(1, 0, false, mitAussage().kernaussagen(), List.of(
                new WissenIngestPort.Planung("konzepte/frist", "AENDERN", "Konzept", "Grund", List.of(1), seiteId,
                        2, false)), List.of(), List.of(), null);
        Speicher speichern = new Speicher(stand(lauf("SEITE", false, geplant), 0, WissensStufe.NORMAL,
                List.of(alt), Map.of(seiteId, alteFassung)));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Frist", "Frist", List.of(),
                "# Frist\n\nFrist. [^q-neu]", List.of(), List.of(), "Neue Frist"),
                new KiPort.Verbrauch("test-modell", 10, 10)));
        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenVorschlag vorschlag = speichern.fortschritt.vorschlaege().getFirst();
        SeitenMetadaten neu = new SeitenMetadaten(seiteId, alt.pfad(), alt.typ(), alt.titel(), alt.beschreibung(),
                alt.tags(), alt.status(), 4, alt.vertraulichkeitMindest());
        var plan = WissenIngestVerarbeiten.neuerzeugungsPlan(vorschlag, speichern.fortschritt,
                new SeitenFassung(neu, "# Neuer Stand", null, List.of()));
        assertThat(plan.basisVersion()).isEqualTo(4);
        assertThat(plan.aussagen()).containsExactly(1);
        assertThat(plan.seiteId()).isEqualTo(seiteId);
        assertThat(plan.aktion()).isEqualTo("AENDERN");

        SeitenMetadaten fremd = new SeitenMetadaten(UUID.randomUUID(), alt.pfad(), alt.typ(), alt.titel(),
                alt.beschreibung(), alt.tags(), alt.status(), 4, alt.vertraulichkeitMindest());
        assertThatThrownBy(() -> WissenIngestVerarbeiten.neuerzeugungsPlan(vorschlag, speichern.fortschritt,
                new SeitenFassung(fremd, "", null, List.of()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neuErzeugenRuftKiMitAktuellerFassungAufUndPrueftDenNeuenDiff() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten alt = new SeitenMetadaten(seiteId, "konzepte/frist", "Konzept", "Frist", "Frist",
                List.of(), "AKTIV", 2, "NORMAL");
        SeitenFassung alteFassung = new SeitenFassung(alt, "# Alte Fassung", null, List.of());
        Fortschritt geplant = new Fortschritt(1, 0, false, mitAussage().kernaussagen(), List.of(
                new WissenIngestPort.Planung(alt.pfad(), "AENDERN", alt.typ(), "Grund", List.of(1), seiteId,
                        2, false)), List.of(), List.of(), WissensStufe.NORMAL);
        Speicher erster = new Speicher(stand(lauf("SEITE", false, geplant), 0, WissensStufe.NORMAL,
                List.of(alt), Map.of(seiteId, alteFassung)));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Frist", "Frist", List.of(),
                "# Frist\n\nFrist. [^q-neu]", List.of(), List.of(), "Neue Frist"),
                new KiPort.Verbrauch("test-modell", 10, 10)));
        verarbeiten(erster, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenMetadaten neu = new SeitenMetadaten(seiteId, alt.pfad(), alt.typ(), alt.titel(), alt.beschreibung(),
                alt.tags(), alt.status(), 4, alt.vertraulichkeitMindest());
        SeitenFassung aktuelleFassung = new SeitenFassung(neu, "# Aktuelle Fassung", null, List.of());
        Speicher speichern = new Speicher(stand(lauf("NEU_ERZEUGEN", false, erster.fortschritt), 0,
                WissensStufe.NORMAL, List.of(neu), Map.of(seiteId, aktuelleFassung)));
        Auftrag auftrag = new Auftrag(1, new MandantCode("urinet"), WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", ENTWURF_ID, "aenderungNr", 1)), 0);
        KiAntwort erneuteKi = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Frist", "Frist", List.of(),
                "# Frist\n\nNeue Aussage. [^q-neu]", List.of(), List.of(), "Aktualisiert"),
                new KiPort.Verbrauch("test-modell", 12, 8)));

        verarbeiten(speichern, erneuteKi, new Verbrauch()).ausfuehren(auftrag, ENTWURF_ID);

        assertThat(erneuteKi.aufrufe).isEqualTo(1);
        assertThat(erneuteKi.anfrage.schritt()).isEqualTo(KiPort.WissensSchritt.SEITE);
        assertThat(erneuteKi.anfrage.nachricht()).contains("# Aktuelle Fassung", "# Frist\n\nFrist.");
        assertThat(speichern.aenderungen).singleElement().satisfies(aenderung -> {
            assertThat(aenderung.basisVersion()).isEqualTo(4);
            assertThat(aenderung.daten().inhalt()).contains("Neue Aussage");
            assertThat(aenderung.diff().zeilen()).flatExtracting(WissenZeilendiff.Aenderung::alt)
                    .contains("# Aktuelle Fassung");
        });

        Speicher nichtZulaessig = new Speicher(stand(lauf("NEU_ERZEUGEN", false, erster.fortschritt), 0,
                WissensStufe.NORMAL, List.of(neu), Map.of(seiteId, aktuelleFassung)));
        nichtZulaessig.vorschlagZulaessig = false;
        KiAntwort darfNichtAufgerufenWerden = new KiAntwort(null);
        verarbeiten(nichtZulaessig, darfNichtAufgerufenWerden, new Verbrauch()).ausfuehren(auftrag, ENTWURF_ID);
        assertThat(darfNichtAufgerufenWerden.aufrufe).isZero();
        assertThat(nichtZulaessig.fehler).contains("Quellen", "nicht mehr zulässig");
        assertThat(nichtZulaessig.aenderungen).isNull();
    }

    @Test
    void erneuertQuelleSeiteNurMitVollstaendigerStruktur() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten metadaten = new SeitenMetadaten(seiteId, "quellen/2026-04-probe-12345678", "Quelle",
                "Probe", "Probe", List.of(), "AKTIV", 2, "NORMAL");
        SeitenFassung fassung = new SeitenFassung(metadaten,
                "## Zusammenfassung\n\nProbe. [^q-alt]\n\n## Kernaussagen\n\n"
                        + "- Alte Aussage. [^q-alt]\n\n## Berührte Seiten\n\n- [Frist](/konzepte/frist)\n",
                DOKUMENT_ID, List.of(new Quelleneintrag("q-alt", DOKUMENT_ID, 1, 1, "Alt", null)));
        Speicher erster = new Speicher(stand(lauf("QUELLE", false, mitAussage()), 0, WissensStufe.NORMAL,
                List.of(metadaten), Map.of(seiteId, fassung)));
        erster.quelleSeite = Optional.of(seiteId);
        verarbeiten(erster, quelleAntwort(), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        Speicher speichern = new Speicher(stand(lauf("NEU_ERZEUGEN", false, erster.fortschritt), 0,
                WissensStufe.NORMAL, List.of(metadaten), Map.of(seiteId, fassung)));
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Quelle", "Beschreibung", List.of(),
                "## Zusammenfassung\n\nProbe.\n\n## Kernaussagen\n\n## Berührte Seiten\n",
                List.of(), List.of(), "Erneuert"), new KiPort.Verbrauch("test-modell", 10, 10)));
        Auftrag auftrag = new Auftrag(1, new MandantCode("urinet"), WissenIngestStarten.TYP,
                new Nutzlast(Map.of("entwurfId", ENTWURF_ID, "aenderungNr", 1)), 0);

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(auftrag, ENTWURF_ID);

        assertThat(ki.aufrufe).isEqualTo(1);
        assertThat(ki.anfrage.schritt()).isEqualTo(KiPort.WissensSchritt.SEITE);
        assertThat(speichern.fehler).contains("Quelle-Seite", "nicht vollständig");
        assertThat(speichern.aenderungen).isNull();

        Speicher vollstaendig = new Speicher(stand(lauf("NEU_ERZEUGEN", false, erster.fortschritt), 0,
                WissensStufe.NORMAL, List.of(metadaten), Map.of(seiteId, fassung)));
        String gueltig = "## Zusammenfassung\n\nProbe. [^q-alt]\n\n## Kernaussagen\n\n"
                + "- Alte Aussage. [^q-alt]\n- Die Frist endet am 30. April. [^q-neu]\n\n"
                + "## Berührte Seiten\n\n- [Frist](/konzepte/frist)\n";
        KiAntwort passendeKi = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite("Quelle", "Beschreibung",
                List.of(), gueltig,
                List.of(), List.of(), "Erneuert"), new KiPort.Verbrauch("test-modell", 10, 10)));
        verarbeiten(vollstaendig, passendeKi, new Verbrauch()).ausfuehren(auftrag, ENTWURF_ID);

        assertThat(vollstaendig.fehler).isNull();
        assertThat(vollstaendig.aenderungen).singleElement().satisfies(aenderung -> {
            assertThat(aenderung.daten().quellen()).extracting(Quelleneintrag::schluessel)
                    .containsExactlyInAnyOrder("q-alt", "q-neu");
            assertThat(aenderung.daten().inhalt()).contains("- Alte Aussage. [^q-alt]",
                    "- Die Frist endet am 30. April. [^q-neu]", "[Frist](/konzepte/frist)");
        });
        for (String ungueltig : List.of(
                gueltig + "\n## Kernaussagen\n- Weitere Aussage. [^q-alt]\n",
                gueltig.replace("- Alte Aussage. [^q-alt]", "- Alte Aussage. [^q-alt]\nNicht erkannte Aussage"),
                gueltig.replace("- [Frist](/konzepte/frist)", ""),
                gueltig.replace("- Die Frist endet am 30. April. [^q-neu]", ""))) {
            Speicher abgelehnt = new Speicher(stand(lauf("NEU_ERZEUGEN", false, erster.fortschritt), 0,
                    WissensStufe.NORMAL, List.of(metadaten), Map.of(seiteId, fassung)));
            KiAntwort ungueltigeKi = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Seite(
                    "Quelle", "Beschreibung", List.of(), ungueltig, List.of(), List.of(), "Erneuert"),
                    new KiPort.Verbrauch("test-modell", 10, 10)));
            verarbeiten(abgelehnt, ungueltigeKi, new Verbrauch()).ausfuehren(auftrag, ENTWURF_ID);
            assertThat(abgelehnt.fehler).contains("nicht vollständig");
            assertThat(abgelehnt.aenderungen).isNull();
        }
    }

    @Test
    void neueKernaussagenZaehlenNachDenSchluesselnFruehererDurchgaengeWeiter() throws InterruptedException {
        String kurz = DOKUMENT_ID.toString().substring(0, 8);
        Fortschritt bisher = new Fortschritt(0, 0, false, List.of(new Kernaussage(1, "q-" + kurz + "-45",
                "Frühere Aussage im selben Lauf.", List.of(1), "Frist", true)), List.of(), List.of(), List.of(), null);
        Lauf lauf = new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT", "WISSENSQUELLE", "NORMAL",
                true, "Testquelle", LocalDate.of(2026, 4, 30), "Erste Seite.\fDie Frist endet am 30. April.\f", 2,
                true, 2, 2, "KERNAUSSAGEN", 0, 0, "test-modell", false, 300_000, 1_000_000, Fortschritt.leer());
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Kernaussagen(List.of(
                new KiPort.Aussage(1, "Die Frist endet am 30. April.", List.of(2), "Die Frist endet am 30. April."),
                new KiPort.Aussage(2, "Die Frist ist im April.", List.of(2), "30. April"))),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        Speicher ausDerDatenbank = new Speicher(stand(lauf, 0));
        ausDerDatenbank.hoechsteNummer = 7;
        verarbeiten(ausDerDatenbank, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(ausDerDatenbank.geprueftesDokument).isEqualTo(DOKUMENT_ID);
        assertThat(ausDerDatenbank.fortschritt.kernaussagen()).extracting(Kernaussage::nr, Kernaussage::schluessel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "q-" + kurz + "-8"),
                        org.assertj.core.groups.Tuple.tuple(2, "q-" + kurz + "-9"));

        Speicher imLauf = new Speicher(stand(new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT",
                "WISSENSQUELLE", "NORMAL", true, "Testquelle", LocalDate.of(2026, 4, 30),
                "Erste Seite.\fDie Frist endet am 30. April.\f", 2, true, 1, 2, "KERNAUSSAGEN", 0, 0,
                "test-modell", false, 300_000, 1_000_000, bisher), 0));
        imLauf.hoechsteNummer = 3;
        verarbeiten(imLauf, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(imLauf.fortschritt.kernaussagen()).extracting(Kernaussage::schluessel)
                .containsExactly("q-" + kurz + "-45", "q-" + kurz + "-46", "q-" + kurz + "-47");
    }

    @Test
    void planungSchlaegtKeineQuelleSeiteAlsFachseiteVor() throws InterruptedException {
        SeitenMetadaten quelleSeite = new SeitenMetadaten(UUID.randomUUID(), "quellen/2026-04-frist-12345678",
                "Quelle", "Frist", "Frist", List.of(), "AKTIV", 2, null);
        Speicher speichern = new Speicher(stand(lauf("PLANUNG", false, mitAussage()), 0, WissensStufe.NORMAL,
                List.of(quelleSeite), Map.of()));
        speichern.sichtbar = List.of(quelleSeite);
        String langerPfad = "quellen/" + "x".repeat(300);
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Planung(List.of(
                new KiPort.Plan(quelleSeite.pfad(), "AENDERN", "Analyse", "Grund", List.of(1)),
                new KiPort.Plan(langerPfad, "NEU", "Quelle", "Grund", List.of(1)),
                new KiPort.Plan("konzepte/frist", "NEU", "Konzept", "Grund", List.of(1)))),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.fortschritt.planung()).extracting(WissenIngestPort.Planung::pfad)
                .containsExactlyInAnyOrder("ueberblick", "konzepte/frist");
        assertThat(speichern.fortschritt.hinweise()).contains("Quelle-Seiten entstehen nur im Schritt QUELLE; "
                + "der Vorschlag für «" + quelleSeite.pfad() + "» wurde verworfen.");
        assertThat(speichern.fortschritt.hinweise()).anySatisfy(hinweis -> assertThat(hinweis)
                .contains("«quellen/" + "x".repeat(112) + "…»").hasSizeLessThan(220));
    }

    private static KiAntwort quelleAntwort() {
        return new KiAntwort(new KiPort.WissensGueltig(new KiPort.Quelle("Testquelle", "Beschreibung",
                List.of(), List.of(new KiPort.Zusammenfassung("Die Frist ist bekannt.", List.of(1)))),
                new KiPort.Verbrauch("test-modell", 10, 10)));
    }

    @Test
    void meldetBeiInternerQuelleDassDerUeberblickNichtAktualisiertWird() throws InterruptedException {
        Speicher speichern = new Speicher(stand(lauf("ABSCHLUSS", false, mitAussage()), 0, WissensStufe.INTERN));

        verarbeiten(speichern, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(speichern.hinweis).contains(WissenIngestVerarbeiten.UEBERBLICK_HOEHER);
    }

    private static WissenIngestVerarbeiten verarbeiten(Speicher speichern, KiAntwort ki, Verbrauch verbrauch) {
        WissenKonfiguration konfiguration = new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
                20_000, 12_000, 300_000, 1_000_000);
        return new WissenIngestVerarbeiten(speichern, konfiguration, ki, verbrauch,
                new DiffDienst(new JavaWissenZeilendiff()), new WissenIngestIndex(speichern, null, konfiguration));
    }

    private static Stand stand(Lauf lauf, long monatsverbrauch) {
        return stand(lauf, monatsverbrauch, WissensStufe.NORMAL);
    }

    private static Stand stand(Lauf lauf, long monatsverbrauch, WissensStufe stufe) {
        return stand(lauf, monatsverbrauch, stufe, List.of(), Map.of());
    }

    private static Stand stand(Lauf lauf, long monatsverbrauch, WissensStufe stufe, List<SeitenMetadaten> seiten,
                               Map<UUID, SeitenFassung> fassungen) {
        return new Stand(lauf, new WissensGraph(Map.of(), Map.of(), Map.of(), Map.of()), seiten, Set.of(),
                fassungen, Map.of(), monatsverbrauch, stufe);
    }

    private static Fortschritt mitAussage() {
        return new Fortschritt(1, 0, false, List.of(new Kernaussage(1, "q-neu", "Die Frist endet am 30. April.",
                List.of(1), "Frist endet am 30. April.", true)), List.of(), List.of(), List.of(), null);
    }

    private static Fortschritt mitKontextStufe(Fortschritt fortschritt, WissensStufe stufe) {
        return new Fortschritt(fortschritt.abschnitt(), fortschritt.seite(), fortschritt.quelleErzeugt(),
                fortschritt.kernaussagen(), fortschritt.planung(), fortschritt.vorschlaege(), fortschritt.hinweise(),
                stufe);
    }

    private static Lauf laufMit(String schritt, String dokumentStatus, String art, String vertraulichkeit,
                                boolean bestaetigt, Fortschritt fortschritt) {
        return new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", dokumentStatus, art, vertraulichkeit,
                bestaetigt, "Testquelle", LocalDate.of(2026, 4, 30), "Die Frist endet am 30. April.\f", 1, true,
                null, null, schritt, 0, 0, "test-modell", false, 300_000, 1_000_000, fortschritt);
    }

    private static Lauf lauf(String schritt, boolean unvollstaendig, Fortschritt fortschritt) {
        return new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT", "WISSENSQUELLE", "NORMAL",
                true, "Testquelle", LocalDate.of(2026, 4, 30), "Die Frist endet am 30. April.\f", 1, true,
                null, null, schritt, 0, 0, "test-modell", unvollstaendig, 300_000, 1_000_000, fortschritt);
    }

    private static final class Speicher extends WissenIngestSpeichern {

        private final Stand stand;
        private final UUID reservierungId = UUID.randomUUID();
        private String neuerSchritt;
        private Fortschritt fortschritt;
        private boolean unvollstaendig;
        private String fehler;
        private UUID freigegebeneReservierung;
        private boolean aenderungenGesichert;
        private List<NeueAenderung> aenderungen;
        private String hinweis;
        private Optional<UUID> quelleSeite = Optional.empty();
        private List<SeitenMetadaten> sichtbar = List.of();
        private long hoechsteNummer;
        private UUID geprueftesDokument;
        private long reservierteTokens;
        private boolean vorschlagZulaessig = true;

        private Speicher(Stand stand) {
            super(null, null, null, null);
            this.stand = stand;
        }

        @Override
        public Optional<Stand> laden(UUID entwurfId, Set<UUID> seitenIds) {
            return Optional.of(stand);
        }

        @Override
        public boolean vorschlagFuerKiZulaessig(Wissensseite vorschlag, WissensStufe quellStufe) {
            return vorschlagZulaessig;
        }

        @Override
        public Optional<UUID> quelleSeiteFinden(UUID dokumentId) {
            return quelleSeite;
        }

        @Override
        public long hoechsteQuellenNummer(UUID dokumentId) {
            geprueftesDokument = dokumentId;
            return hoechsteNummer;
        }

        @Override
        public BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens,
                                                    long monatsbudgetTokens, java.time.Instant von,
                                                    java.time.Instant bis) {
            reservierteTokens = tokens;
            return new BudgetReservierung(reservierungId, false);
        }

        @Override
        public void budgetFreigeben(UUID id) {
            freigegebeneReservierung = id;
        }

        @Override
        public void fortschrittSichern(ch.urinet.archiv.verarbeitung.application.Auftrag auftrag, UUID entwurfId,
                                       String erwartet, String neu, Fortschritt neuerFortschritt, long tokensEin,
                                       long tokensAus, boolean unvollstaendig, String hinweis) {
            neuerSchritt = neu;
            fortschritt = neuerFortschritt;
            this.unvollstaendig = unvollstaendig;
        }

        @Override
        public void fehlerSichern(ch.urinet.archiv.verarbeitung.application.Auftrag auftrag, UUID entwurfId,
                                  String erwartet, Fortschritt bisher, String hinweis) {
            fehler = hinweis;
        }

        @Override
        public void neuerzeugungFehler(Auftrag auftrag, UUID entwurfId, String hinweis) {
            fehler = hinweis;
        }

        @Override
        public void neuerzeugungSichern(Auftrag auftrag, UUID entwurfId, UUID seiteId, int basisVersion,
                                        WissensStufe quellStufe, Fortschritt fortschritt, long tokensEin,
                                        long tokensAus, java.util.function.Function<Stand, NeueAenderung> pruefen) {
            this.fortschritt = fortschritt;
            this.aenderungen = List.of(pruefen.apply(stand));
        }

        @Override
        public List<SeitenMetadaten> sichtbareSeiten(
                Stand geladenerStand) {
            return sichtbar;
        }

        @Override
        public void aenderungenSichern(ch.urinet.archiv.verarbeitung.application.Auftrag auftrag, UUID entwurfId,
                                       String erwartet, List<NeueAenderung> aenderungen, Fortschritt fortschritt,
                                       long tokensEin, long tokensAus, boolean unvollstaendig, String hinweis) {
            aenderungenGesichert = true;
            this.aenderungen = aenderungen;
            this.hinweis = hinweis;
            this.unvollstaendig = unvollstaendig;
        }
    }

    private static final class KiAntwort implements KiPort {
        private final WissensAntwort antwort;
        private int aufrufe;
        private int maxAusgabe;
        private WissensAnfrage anfrage;

        private KiAntwort(WissensAntwort antwort) {
            this.antwort = antwort;
        }

        @Override
        public Antwort metadaten(Anfrage anfrage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WissensAntwort wissensIngest(WissensAnfrage anfrage) {
            aufrufe++;
            this.anfrage = anfrage;
            maxAusgabe = anfrage.maxAusgabeTokens();
            return antwort;
        }

        @Override
        public String antwortschema() {
            return "";
        }

        @Override
        public List<String> artCodes() {
            return List.of();
        }
    }

    private static final class Verbrauch implements KiVerbrauchPort {
        private UUID entwurfId;

        @Override
        public void erfassen(String zweck, KiPort.Verbrauch verbrauch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void erfassen(String zweck, KiPort.Verbrauch verbrauch, UUID ingestEntwurfId) {
            entwurfId = ingestEntwurfId;
        }
    }
}
