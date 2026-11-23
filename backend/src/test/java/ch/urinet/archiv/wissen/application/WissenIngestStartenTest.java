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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenIngestStartenTest {

    private static final UUID DOKUMENT_ID = UUID.randomUUID();
    /** Startwerte aus design.md, «Konfiguration und Aufträge». */
    private static final WissenKonfiguration STARTWERTE = new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
            20_000, 12_000, 600_000, 1_000_000);
    /** Wie die Startwerte, aber mit Budgets, unter denen eine kurze Quelle durchgeht (Req 13.4). */
    private static final WissenKonfiguration GROSSES_BUDGET = new WissenKonfiguration("test-modell", 30_000, 15,
            60_000, 20_000, 12_000, 1_000_000, 10_000_000);

    @Test
    void reihtDenErstenManuellenIngestAlsAuftragEin() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("IN_ARBEIT");
        assertThat(ergebnis.geschaetzteTokens()).isPositive();
        assertThat(ergebnis.hinweis()).isNull();
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
    void markierteWissensquelleStartetMitDemMarkierendenBenutzer() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).nachMarkierung(DOKUMENT_ID, "benutzer-id");

        assertThat(speicher.entwurf).satisfies(entwurf -> {
            assertThat(entwurf.erstelltVon()).isEqualTo("benutzer-id");
            assertThat(entwurf.status()).isEqualTo("IN_ARBEIT");
        });
        assertThat(auftraege.eingereiht).hasSize(1);
    }

    @Test
    void archivierungLoestKeinenAutomatischenIngestAus() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(true);

        new WissenDokumentEreignisse(null).archiviert(DOKUMENT_ID, "QUITTUNG");

        assertThat(speicher.geladen).isFalse();
        assertThat(speicher.entwurf).isNull();
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void startNachMarkierungPrueftDieAktiveMarkierung() {
        Speicher speicher = new Speicher(quelle(false, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                null, null));

        starten(speicher, new Auftraege(true)).nachMarkierung(DOKUMENT_ID, "benutzer-id");

        assertThat(speicher.entwurf).isNull();
    }

    @Test
    void ohneHandlerEntstehtEinFehlerentwurfMitStabilemHinweis() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(false);

        starten(speicher, auftraege).nachMarkierung(DOKUMENT_ID, "benutzer-id");

        assertThat(speicher.entwurf.status()).isEqualTo("FEHLER");
        assertThat(speicher.entwurf.schritt()).isNull();
        assertThat(speicher.entwurf.hinweis()).isEqualTo(WissenIngestStarten.HANDLER_FEHLT);
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void stelltZweitenIngestAlsWartendZurueck() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        speicher.warteschlangeBelegt = true;
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("WARTEND");
        assertThat(speicher.entwurf.status()).isEqualTo("WARTEND");
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void personalquelleWirdOhneAuftragMitGrundImEntwurfAbgeschlossen() {
        Speicher speicher = new Speicher(quelle("PERSONAL"));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("FEHLER");
        assertThat(ergebnis.hinweis()).isEqualTo("nicht in die Wissensbasis übernommen – Vertraulichkeit PERSONAL");
        assertThat(speicher.entwurf.hinweis()).isEqualTo(ergebnis.hinweis());
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void seitenzahlOhnePassendeSeitentrennerVerhindertDenIngest() {
        // zwei Seiten im Volltext, drei im Dokument (Req 5.6)
        Speicher speicher = new Speicher(quelle(true, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 3,
                true, null, null));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("FEHLER");
        assertThat(ergebnis.hinweis())
                .isEqualTo("nicht in die Wissensbasis übernommen – keine Seitenangaben möglich");
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void fehlendePdfaVerhindertDenIngest() {
        Speicher speicher = new Speicher(quelle(true, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2,
                false, null, null));

        WissenIngestStarten.Ergebnis ergebnis = starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.hinweis()).endsWith("keine Seitenangaben möglich");
    }

    @Test
    void normalisiertEinSeitenendeOhneStartseiteFuerDenEntwurf() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).manuell(DOKUMENT_ID, null, 1, "benutzer-id");

        assertThat(speicher.entwurf.seiteVon()).isEqualTo(1);
        assertThat(speicher.entwurf.seiteBis()).isEqualTo(1);
        assertThat(auftraege.eingereiht).hasSize(1);
    }

    @Test
    void lehntUmgekehrtenSeitenbereichAb() {
        Speicher speicher = new Speicher(quelle("NORMAL"));

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, 2, 1, "benutzer-id"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        assertThat(speicher.entwurf).isNull();
    }

    @Test
    void unbekannteQuelleIst404() {
        Speicher speicher = new Speicher(null);

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true)).manuell(DOKUMENT_ID, null, null, "b"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
    }

    @Test
    void statusKonfliktKommtVorFehlenderMarkierungUndUngueltigemSeitenbereich() {
        Speicher speicher = new Speicher(quelle(false, "EINGANGSKORB", "NORMAL", "eins\fzwei\f", 2, true,
                null, null));

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true)).manuell(DOKUMENT_ID, 2, 1, "b"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.STATUS_KONFLIKT));
    }

    @Test
    void laufenderIngestKommtVorFehlenderMarkierung() {
        Speicher speicher = new Speicher(quelle(false, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                null, null));
        speicher.laufend = true;

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true)).manuell(DOKUMENT_ID, null, null, "b"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.STATUS_KONFLIKT));
    }

    @Test
    void fehlendeMarkierungKommtVorUngueltigemSeitenbereich() {
        Speicher speicher = new Speicher(quelle(false, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                null, null));

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true)).manuell(DOKUMENT_ID, 2, 1, "b"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.KEINE_WISSENSQUELLE));
    }

    @Test
    void automatischerStartUebergehtLaufendenIngestOhneNeuenEntwurf() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        speicher.laufend = true;

        starten(speicher, new Auftraege(true)).nachMarkierung(DOKUMENT_ID, "benutzer-id");

        assertThat(speicher.entwurf).isNull();
    }

    @Test
    void schaetzungUeberDemBudgetDerKonfigurationVerhindertDenIngest() {
        WissenKonfiguration kleinesBudget = new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
                20_000, 12_000, 300_000, 1_000_000);
        Speicher speicher = new Speicher(quelle("NORMAL"));
        Auftraege auftraege = new Auftraege(true);

        WissenIngestStarten.Ergebnis ergebnis = new WissenIngestStarten(speicher, kleinesBudget, auftraege)
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(ergebnis.status()).isEqualTo("FEHLER");
        assertThat(ergebnis.geschaetzteTokens()).isGreaterThan(300_000);
        assertThat(ergebnis.hinweis()).matches("Die Unterlage ist für einen Durchgang zu gross \\(geschätzt "
                + "\\d{3}'\\d{3} Tokens\\)\\. Bitte einen Seitenbereich wählen\\.");
        assertThat(speicher.entwurf.hinweis()).isEqualTo(ergebnis.hinweis());
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void budgetDerEinstellungenGehtDerKonfigurationVor() {
        Speicher hoch = new Speicher(quelle(true, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                1_000_000, null));
        Speicher tief = new Speicher(quelle(true, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                1_000, null));

        WissenIngestStarten.Ergebnis durch = new WissenIngestStarten(hoch, STARTWERTE, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");
        WissenIngestStarten.Ergebnis zuGross = starten(tief, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id");

        assertThat(durch.status()).isEqualTo("IN_ARBEIT");
        assertThat(zuGross.status()).isEqualTo("FEHLER");
        assertThat(zuGross.hinweis()).contains("zu gross");
    }

    @Test
    void meldetErschoepftesMonatsbudgetBeimManuellenStartAls429() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        speicher.verbrauch = 10_000_000;

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.BUDGET));
        assertThat(speicher.entwurf).isNull();
    }

    @Test
    void restbudgetUnterDerSchaetzungGiltAlsErschoepft() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        speicher.verbrauch = 10_000_000 - 100_000;

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true))
                .manuell(DOKUMENT_ID, null, null, "benutzer-id"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.BUDGET));
    }

    private static Quelle quelle(boolean markiert, String status, String vertraulichkeit, String volltext, int seiten,
                                 boolean pdfaVorhanden, Integer budgetTokens, Integer monatsbudgetTokens) {
        return new Quelle(markiert, status, vertraulichkeit, true, volltext, seiten, pdfaVorhanden, budgetTokens,
                monatsbudgetTokens);
    }

    private static Quelle quelle(String vertraulichkeit, int seiten) {
        StringBuilder volltext = new StringBuilder();
        for (int seite = 1; seite <= seiten; seite++) {
            volltext.append("Seite ").append(seite).append('\f');
        }
        return new Quelle(true, "ARCHIVIERT", vertraulichkeit, true, volltext.toString(), seiten,
                true, null, null);
    }

    @Test
    void erschoepftesMonatsbudgetErgibtBeimAutomatischenStartEinenFehlerentwurf() {
        Speicher speicher = new Speicher(quelle("NORMAL"));
        speicher.verbrauch = 10_000_000;
        Auftraege auftraege = new Auftraege(true);

        starten(speicher, auftraege).nachMarkierung(DOKUMENT_ID, "benutzer-id");

        assertThat(speicher.entwurf.status()).isEqualTo("FEHLER");
        assertThat(speicher.entwurf.hinweis()).isEqualTo("Budget erschöpft");
        assertThat(auftraege.eingereiht).isEmpty();
    }

    @Test
    void monatsbudgetDerEinstellungenGehtDerKonfigurationVor() {
        Speicher speicher = new Speicher(quelle(true, "ARCHIVIERT", "NORMAL", "eins\fzwei\f", 2, true,
                null, 100));

        assertThatThrownBy(() -> starten(speicher, new Auftraege(true)).manuell(DOKUMENT_ID, null, null, "b"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.BUDGET));
    }

    @Test
    void monatsgrenzenFolgenDerZeitzoneZuerich() {
        // 31.10.2026 23:30 UTC ist in Zürich schon der 1. November (00:30 MEZ)
        WissenIngestStarten.Zeitraum monat = WissenIngestStarten.monat(Instant.parse("2026-10-31T23:30:00Z"));

        assertThat(monat.von()).isEqualTo(Instant.parse("2026-10-31T23:00:00Z"));
        assertThat(monat.bis()).isEqualTo(Instant.parse("2026-11-30T23:00:00Z"));
    }

    @Test
    void monatsgrenzenBeruecksichtigenDieSommerzeit() {
        WissenIngestStarten.Zeitraum monat = WissenIngestStarten.monat(Instant.parse("2026-07-15T12:00:00Z"));

        assertThat(monat.von()).isEqualTo(Instant.parse("2026-06-30T22:00:00Z"));
        assertThat(monat.bis()).isEqualTo(Instant.parse("2026-07-31T22:00:00Z"));
    }

    private static WissenIngestStarten starten(Speicher speicher, Auftraege auftraege) {
        return new WissenIngestStarten(speicher, GROSSES_BUDGET, auftraege);
    }

    private static Quelle quelle(String vertraulichkeit) {
        return quelle(vertraulichkeit, 2);
    }

    private static final class Speicher implements WissenIngestPort {
        private final Quelle quelle;
        private NeuerEntwurf entwurf;
        private boolean geladen;
        private boolean laufend;
        private boolean warteschlangeBelegt;
        private long verbrauch;

        private Speicher(Quelle quelle) {
            this.quelle = quelle;
        }

        @Override
        public Optional<Quelle> quelleLaden(UUID dokumentId) {
            geladen = true;
            return Optional.ofNullable(quelle);
        }

        @Override
        public boolean entwurfFuerDokumentLaufend(UUID dokumentId) {
            return laufend;
        }

        @Override
        public boolean warteschlangeBelegt() {
            return warteschlangeBelegt;
        }

        @Override
        public UUID entwurfAnlegen(NeuerEntwurf entwurf) {
            this.entwurf = entwurf;
            return entwurf.id();
        }

        @Override
        public long verbrauchImZeitraum(Instant von, Instant bis) {
            return verbrauch;
        }

        @Override
        public BudgetEinstellungen budgetEinstellungenLaden() {
            throw new UnsupportedOperationException();
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
        public java.util.Optional<UUID> quelleSeiteFinden(UUID dokumentId) {
            return java.util.Optional.empty();
        }

        @Override
        public long hoechsteQuellenNummer(UUID dokumentId) {
            return 0;
        }

        @Override
        public Optional<SeitenFassung> seiteLaden(UUID seiteId) {
            return Optional.empty();
        }

        @Override
        public void aenderungAnlegen(NeueAenderung aenderung) {
        }

        @Override
        public void erneuerteAenderungSpeichern(NeueAenderung aenderung, Fortschritt fortschritt, long tokensEin,
                                                long tokensAus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void neuerzeugungAbbrechen(UUID entwurfId, String hinweis) {
            throw new UnsupportedOperationException();
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
        public int handlerFehltNachholen(String hinweis) {
            return 0;
        }

        @Override
        public Optional<UUID> wartendenAktivieren(long monatsbudgetTokens, Instant von, Instant bis) {
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
