package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.integration.application.mail.GmailLabelKonfiguration;
import ch.urinet.archiv.integration.application.mail.PostfachNichtErreichbar;
import ch.urinet.archiv.integration.application.mail.PostfachPort;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GmailImportHandlerTest {

    private static final String PASSWORT = "synthetisches-app-passwort";
    private static final UUID LAUF_ID = UUID.fromString("98052255-3836-45dc-9707-fd8805bbfb5c");

    @Test
    void falschesPasswortMeldetDenVorgesehenenQuelllaufcodeOhneGeheimnisImLog() {
        TestQuellen quellen = new TestQuellen(LaufArt.ECHT);
        PostfachPort postfach = new PostfachStub((zugang, passwort, label, seit, max, verarbeiter) -> {
            throw new PostfachNichtErreichbar("Anmeldung", new IllegalArgumentException(PASSWORT));
        });
        GmailImportHandler handler = new GmailImportHandler(quellen, new GmailNachrichtVerarbeiten(null, null),
                postfach);

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            handler.ausfuehren(auftrag(false));

            assertThat(quellen.meldungen).containsExactly("QUELLE_ANMELDUNG: Anmeldung bei Gmail fehlgeschlagen: "
                    + "App-Passwort ungültig oder Zwei-Faktor nicht aktiv.");
            assertThat(log.volltext()).doesNotContain(PASSWORT);
        }
    }

    @Test
    void abgebrochenerTrockenlaufMitGelesenenNachrichtenSchliesstMitAufgehendenZaehlernAb() {
        TestQuellen quellen = new TestQuellen(LaufArt.TROCKEN);
        PostfachPort postfach = new PostfachStub((zugang, passwort, label, seit, max, verarbeiter) -> {
            try {
                verarbeiter.verarbeiten(new PostfachPort.Nachricht("<synthetic@example.invalid>", Optional.empty(),
                        Optional.empty(), Optional.empty(), Path.of("synthetisch.eml"), List.of(), ""));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new PostfachNichtErreichbar("Verbindung", new IllegalStateException("unterbrochen"));
        });
        GmailImportHandler handler = new GmailImportHandler(quellen, new GmailNachrichtVerarbeiten(null, null),
                postfach);

        handler.ausfuehren(auftrag(true));

        assertThat(quellen.zaehler).contains(new Zaehler(1, 0, 0, 0, 1, 0));
        assertThat(quellen.uebersprungen).isZero();
        assertThat(quellen.zaehler.orElseThrow().gehtAuf()).isTrue();
        assertThat(quellen.abgebrochen).isTrue();
    }

    private static Auftrag auftrag(boolean trocken) {
        return new Auftrag(1, new MandantCode("urinet"), new AuftragTyp("QUELLLAUF_GMAIL"),
                new Nutzlast(Map.of("laufId", LAUF_ID, "trocken", trocken)), 0);
    }

    private static final class PostfachStub implements PostfachPort {
        private final LesenderAbruf lesenderAbruf;

        PostfachStub(LesenderAbruf lesenderAbruf) {
            this.lesenderAbruf = lesenderAbruf;
        }

        @Override
        public Abruf abrufen(Postfach postfach, String passwort, int max, Verarbeiter verarbeiter) {
            return new Abruf(0, 0);
        }

        @Override
        public Abruf abrufenNurLesend(Postfach postfach, String passwort, String label, LocalDate seit, int max,
                                      Verarbeiter verarbeiter) {
            return lesenderAbruf.abrufen(postfach, passwort, label, seit, max, verarbeiter);
        }
    }

    @FunctionalInterface
    private interface LesenderAbruf {
        PostfachPort.Abruf abrufen(Postfach postfach, String passwort, String label, LocalDate seit, int max,
                                  PostfachPort.Verarbeiter verarbeiter);
    }

    private static final class TestQuellen extends QuellenVerwaltung {
        private final GmailVerbindung verbindung;
        private Optional<Zaehler> zaehler;
        private List<String> meldungen = List.of();
        private int uebersprungen;
        private boolean abgebrochen;

        TestQuellen(LaufArt art) {
            super(null, null, null, json -> {
                throw new UnsupportedOperationException("Konfigurationsleser wird in diesem Test nicht verwendet");
            });
            GmailLabelKonfiguration konfiguration = new GmailLabelKonfiguration(
                    new Postfach("imap.gmail.com", 993, "synthetisch@example.invalid"), "Synthetisches Label",
                    LocalDate.of(2026, 1, 1), false, 500);
            verbindung = new GmailVerbindung("gmail", konfiguration, PASSWORT, art);
        }

        @Override
        public GmailVerbindung gmailVerbindung(UUID laufId) {
            return verbindung;
        }

        @Override
        public QuellLauf laufAbschliessen(UUID laufId, Optional<Zaehler> zaehler, int uebersprungen,
                                          List<String> meldungen, boolean abgebrochen) {
            this.zaehler = zaehler;
            this.uebersprungen = uebersprungen;
            this.meldungen = List.copyOf(meldungen);
            this.abgebrochen = abgebrochen;
            Zaehler stand = zaehler.orElse(Zaehler.NULL);
            QuellLaufStatus status = abgebrochen ? QuellLaufStatus.ABGEBROCHEN : QuellLaufStatus.FERTIG;
            return new QuellLauf(LAUF_ID, UUID.randomUUID(), "gmail", verbindung.art(), status, stand, meldungen,
                    Optional.empty(), Instant.EPOCH, Optional.of(Instant.EPOCH));
        }
    }
}
