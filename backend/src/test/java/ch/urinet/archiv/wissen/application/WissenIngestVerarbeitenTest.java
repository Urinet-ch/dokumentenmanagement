package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.wissen.application.WissenIngestPort.BudgetReservierung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Lauf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeueAenderung;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern.Stand;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static WissenIngestVerarbeiten verarbeiten(Speicher speichern, KiAntwort ki, Verbrauch verbrauch) {
        WissenKonfiguration konfiguration = new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
                20_000, 12_000, 300_000, 1_000_000);
        return new WissenIngestVerarbeiten(speichern, konfiguration, ki, verbrauch);
    }

    private static Stand stand(Lauf lauf, long monatsverbrauch) {
        return new Stand(lauf, new WissensGraph(Map.of(), Map.of(), Map.of(), Map.of()), List.of(), Set.of(),
                Map.of(), Map.of(), monatsverbrauch, WissensStufe.NORMAL);
    }

    private static Lauf lauf(String schritt, boolean unvollstaendig, Fortschritt fortschritt) {
        return new Lauf(ENTWURF_ID, DOKUMENT_ID, "IN_ARBEIT", "ARCHIVIERT", "NORMAL",
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

        private Speicher(Stand stand) {
            super(null, null, null, null);
            this.stand = stand;
        }

        @Override
        public Optional<Stand> laden(UUID entwurfId, Set<UUID> seitenIds) {
            return Optional.of(stand);
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
        public List<ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten> sichtbareSeiten(
                Stand geladenerStand) {
            return List.of();
        }

        @Override
        public void aenderungenSichern(ch.urinet.archiv.verarbeitung.application.Auftrag auftrag, UUID entwurfId,
                                       String erwartet, List<NeueAenderung> aenderungen, Fortschritt fortschritt,
                                       long tokensEin, long tokensAus, boolean unvollstaendig, String hinweis) {
            aenderungenGesichert = true;
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
