package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.wissen.application.WissenIngestPort.BudgetReservierung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Kernaussage;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Lauf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeueAenderung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenFassung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenVorschlag;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern.Stand;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
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
    void erneuterIngestAktualisiertDieBestehendeQuelleSeite() throws InterruptedException {
        UUID seiteId = UUID.randomUUID();
        SeitenMetadaten metadaten = new SeitenMetadaten(seiteId, "quellen/2026-04-alt-12345678", "Quelle",
                "Alt", "Alt", List.of(), "AKTIV", 3, null);
        String inhalt = "## Zusammenfassung\n\nAlt. [^q-alt]\n\n## Kernaussagen\n\n- Alte Aussage. [^q-alt]\n"
                + "- Frühere Fassung. [^q-neu]\n\n## Berührte Seiten\n\n- [x](/x)\n";
        SeitenFassung fassung = new SeitenFassung(metadaten, inhalt, DOKUMENT_ID, List.of(
                new Quelleneintrag("q-alt", DOKUMENT_ID, 1, 1, "Alte Aussage", null),
                new Quelleneintrag("q-neu", DOKUMENT_ID, 1, 1, "Frühere Fassung", null),
                new Quelleneintrag("q-weg", DOKUMENT_ID, 1, 1, "Weg", null)));
        Speicher speichern = new Speicher(stand(lauf("QUELLE", false, mitAussage()), 0, WissensStufe.NORMAL,
                List.of(metadaten), Map.of(seiteId, fassung)));
        speichern.quelleSeite = Optional.of(seiteId);
        KiAntwort ki = new KiAntwort(new KiPort.WissensGueltig(new KiPort.Quelle("Testquelle", "Beschreibung",
                List.of(), List.of(new KiPort.Zusammenfassung("Die Frist ist bekannt.", List.of(1)))),
                new KiPort.Verbrauch("test-modell", 10, 10)));

        verarbeiten(speichern, ki, new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        SeitenVorschlag vorschlag = speichern.fortschritt.vorschlaege().getLast();
        assertThat(vorschlag.aktion()).isEqualTo("AENDERN");
        assertThat(vorschlag.seiteId()).isEqualTo(seiteId);
        assertThat(vorschlag.basisVersion()).isEqualTo(3);
        assertThat(vorschlag.pfad()).isEqualTo(metadaten.pfad());
        assertThat(vorschlag.daten().inhalt()).contains("- Alte Aussage. [^q-alt]", "- Die Frist endet am 30. April. [^q-neu]")
                .doesNotContain("Frühere Fassung");
        assertThat(vorschlag.daten().quellen()).extracting(Quelleneintrag::schluessel)
                .containsExactlyInAnyOrder("q-alt", "q-neu");
        assertThat(vorschlag.daten().quellen()).filteredOn(q -> q.schluessel().equals("q-neu"))
                .singleElement().extracting(Quelleneintrag::auszug).isEqualTo("Frist endet am 30. April.");
        assertThat(vorschlag.entfernteAussagen()).extracting(KiPort.EntfernteAussage::schluessel)
                .containsExactly("q-weg");

        Speicher abschluss = new Speicher(stand(lauf("ABSCHLUSS", false, speichern.fortschritt), 0,
                WissensStufe.NORMAL, List.of(metadaten), Map.of(seiteId, fassung)));
        verarbeiten(abschluss, new KiAntwort(null), new Verbrauch()).ausfuehren(null, ENTWURF_ID);

        assertThat(abschluss.aenderungen).singleElement().satisfies(aenderung ->
                assertThat(aenderung.befunde()).extracting(WissensPruefung.Befund::code).doesNotContain(
                        WissensPruefung.Code.QUELLE_NICHT_BELEGT, WissensPruefung.Code.AUSSAGE_ENTFERNT));
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
        return new WissenIngestVerarbeiten(speichern, konfiguration, ki, verbrauch);
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
                List.of(1), "Frist endet am 30. April.", true)), List.of(), List.of(), List.of());
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

        private Speicher(Stand stand) {
            super(null, null, null, null);
            this.stand = stand;
        }

        @Override
        public Optional<Stand> laden(UUID entwurfId, Set<UUID> seitenIds) {
            return Optional.of(stand);
        }

        @Override
        public Optional<UUID> quelleSeiteFinden(UUID dokumentId) {
            return quelleSeite;
        }

        @Override
        public List<SeitenMetadaten> indexLaden(UUID entwurfId, String suchtext) {
            return List.of();
        }

        @Override
        public BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens,
                                                    long monatsbudgetTokens, java.time.Instant von,
                                                    java.time.Instant bis) {
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
        public List<SeitenMetadaten> sichtbareSeiten(
                Stand geladenerStand) {
            return List.of();
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
