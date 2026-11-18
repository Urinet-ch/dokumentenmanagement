package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.WissenIngestPort.BudgetReservierung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeuerEntwurf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Quelle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenIngestStartenTest {

    private static final UUID DOKUMENT_ID = UUID.randomUUID();

    @Test
    void reihtDenErstenManuellenIngestAlsAuftragEin() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("IN_ARBEIT");
        assertThat(ergebnis.geschaetzteTokens()).isPositive();
        assertThat(speicher.entwurf).satisfies(entwurf -> {
            assertThat(entwurf.erstelltVon()).isEqualTo("benutzer-id");
            assertThat(entwurf.status()).isEqualTo("IN_ARBEIT");
            assertThat(entwurf.schritt()).isEqualTo("KERNAUSSAGEN");
        });
        assertThat(auftraege.eingereiht).singleElement().satisfies(auftrag -> {
            assertThat(auftrag.typ()).isEqualTo(WissenIngestStarten.TYP);
            assertThat(auftrag.nutzlast().uuid(WissenIngestStarten.NUTZLAST_ENTWURF_ID))
                    .contains(ergebnis.entwurfId());
        });
    }

    @Test
    void archivierteWissensquelleStartetAutomatisch() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).archiviert(DOKUMENT_ID, "WISSENSQUELLE");

        assertThat(speicher.entwurf).satisfies(entwurf -> {
            assertThat(entwurf.erstelltVon()).isEqualTo("system:archivierung");
            assertThat(entwurf.status()).isEqualTo("IN_ARBEIT");
        });
        assertThat(auftraege.eingereiht).hasSize(1);
    }

    @Test
    void andereDokumentartLoestKeinenAutomatischenIngestAus() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).archiviert(DOKUMENT_ID, "QUITTUNG");

        assertThat(speicher.entwurf).isNull();
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void automatischerStartOhneHandlerSpeichertFehlerhinweisStattOffenemAuftrag() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        Auftraege auftraege = new Auftraege(false);

        starten(speicher, auftraege).archiviert(DOKUMENT_ID, "WISSENSQUELLE");

        assertThat(speicher.entwurf.status()).isEqualTo("FEHLER");
        assertThat(speicher.entwurf.hinweis()).contains("noch nicht verfügbar");
        assertThat(speicher.hinweis).contains("noch nicht verfügbar");
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void stelltZweitenIngestAlsWartendZurueck() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        speicher.aktiverIngest = true;
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("WARTEND");
        assertThat(speicher.entwurf.status()).isEqualTo("WARTEND");
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void schliesstPersonalquelleOhneAuftragUndMitDokumenthinweisAb() {
        Speicher speicher = new Speicher(quelle("PERSONAL", 2));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("FEHLER");
        assertThat(ergebnis.hinweis()).contains("Personalunterlagen");
        assertThat(speicher.hinweis).isEqualTo(ergebnis.hinweis());
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void normalisiertEinSeitenendeOhneStartseiteFuerDenEntwurf() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).manuell(DOKUMENT_ID, null, 1, "benutzer-id");

        assertThat(speicher.entwurf.seiteVon()).isEqualTo(1);
        assertThat(speicher.entwurf.seiteBis()).isEqualTo(1);
        assertThat(auftraege.eingereiht).hasSize(1);
    }

    @Test
    void lehntUmgekehrtenSeitenbereichAb() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, 2, 1, "benutzer-id"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        assertThat(speicher.entwurf).isNull();
    }

    @Test
    void meldetErschoepftesMonatsbudgetBeimManuellenStartAls429() {
        Speicher speicher = new Speicher(quelle("NORMAL", 2));
        speicher.verbrauch = 1_000_000;

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.BUDGET));
        assertThat(speicher.entwurf).isNull();
    }

    private static WissenIngestStarten starten(Speicher speicher, Auftraege auftraege) {
        WissenKonfiguration konfiguration = new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
                20_000, 12_000, 300_000, 1_000_000);
        return new WissenIngestStarten(speicher, konfiguration, auftraege);
    }

    private static Quelle quelle(String vertraulichkeit, int seiten) {
        String volltext = "Seite eins\fSeite zwei";
        return new Quelle("WISSENSQUELLE", "ARCHIVIERT", vertraulichkeit, true, volltext, seiten, true, null, null);
    }

    private static final class Speicher implements WissenIngestPort {
        private final Quelle quelle;
        private NeuerEntwurf entwurf;
        private String hinweis;
        private boolean aktiverIngest;
        private long verbrauch;

        private Speicher(Quelle quelle) {
            this.quelle = quelle;
        }

        @Override
        public Optional<Quelle> quelleLaden(UUID dokumentId) {
            return Optional.of(quelle);
        }

        @Override
        public boolean entwurfFuerDokumentOffen(UUID dokumentId) {
            return false;
        }

        @Override
        public boolean hatAktivenIngest() {
            return aktiverIngest;
        }

        @Override
        public UUID entwurfAnlegen(NeuerEntwurf entwurf) {
            this.entwurf = entwurf;
            return entwurf.id();
        }

        @Override
        public void dokumentHinweisSetzen(UUID dokumentId, String hinweis) {
            this.hinweis = hinweis;
        }

        @Override
        public long verbrauchImZeitraum(java.time.Instant von, java.time.Instant bis) {
            return verbrauch;
        }

        @Override
        public BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens,
                                                    long monatsbudgetTokens, java.time.Instant von,
                                                    java.time.Instant bis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void budgetFreigeben(UUID reservierungId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Lauf> laufLaden(UUID entwurfId) {
            return Optional.empty();
        }

        @Override
        public List<SeitenMetadaten> seitenMetadatenLaden() {
            return List.of();
        }

        @Override
        public java.util.Set<UUID> personalDokumenteLaden(java.util.Set<UUID> dokumentIds) {
            return java.util.Set.of();
        }

        @Override
        public java.util.Map<UUID, ch.urinet.archiv.wissen.domain.WissensPruefung.DokumentQuelle>
        dokumentQuellenLaden(java.util.Set<UUID> dokumentIds) {
            return java.util.Map.of();
        }

        @Override
        public List<SeitenMetadaten> seitenIndexLaden(java.util.Set<UUID> seiteIds, String suchtext) {
            return List.of();
        }

        @Override
        public Optional<SeitenFassung> seiteLaden(UUID seiteId) {
            return Optional.empty();
        }

        @Override
        public void aenderungAnlegen(NeueAenderung aenderung) {
        }

        @Override
        public void fortschrittSichern(UUID entwurfId, String erwarteterSchritt, String neuerSchritt,
                                       Fortschritt fortschritt, long tokensEin, long tokensAus,
                                       boolean unvollstaendig, String hinweis) {
        }

        @Override
        public void entwurfSchliessen(UUID entwurfId, String erwarteterSchritt, String status,
                                      Fortschritt fortschritt, long tokensEin, long tokensAus,
                                      boolean unvollstaendig, String hinweis) {
        }

        @Override
        public Optional<UUID> wartendenAktivieren() {
            return Optional.empty();
        }
    }

    private static final class Auftraege extends AuftragService {
        private final boolean handler;
        private final List<Auftrag> eingereiht = new ArrayList<>();

        private Auftraege(boolean handler) {
            super(null, null, null, null);
            this.handler = handler;
        }

        @Override
        public long einreihen(AuftragTyp typ, Nutzlast nutzlast) {
            eingereiht.add(new Auftrag(typ, nutzlast));
            return eingereiht.size();
        }

        @Override
        public boolean hatHandler(AuftragTyp typ) {
            return handler;
        }

        @Override
        public OptionalLong offen(AuftragTyp typ, Optional<String> schluessel, boolean sperren) {
            return OptionalLong.empty();
        }

        private record Auftrag(AuftragTyp typ, Nutzlast nutzlast) {
        }
    }
}
