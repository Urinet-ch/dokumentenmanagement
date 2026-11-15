package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportJahrHandlerTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID EXPORT_ID = UUID.fromString("6a62ca5a-f5ec-4e16-9423-e5fd19080de1");
    private static final UUID FEHLER_ID = UUID.fromString("a8a2c891-89a6-4dca-9fe5-2fa5c9e191d8");

    @Test
    void fehlendeExportIdUndUnbekannterExportBrechenAb() {
        SpeichernStub speichern = new SpeichernStub(Optional.empty());
        ExportJahrHandler handler = handler(speichern, ziel(new ExportzielStub(false)).port, Stufe.B,
                new AtomicInteger());

        assertThatThrownBy(() -> handler.ausfuehren(auftrag(Nutzlast.leer())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExportJahrHandler.EXPORT_ID);
        assertThatThrownBy(() -> handler.ausfuehren(auftrag(nutzlast(EXPORT_ID))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPORT_ID.toString());
    }

    @Test
    void exportDerNichtMehrOffenIstSchreibtUndMeldetNicht() throws Exception {
        for (ExportStatus status : List.of(ExportStatus.FERTIG, ExportStatus.FEHLER)) {
            SpeichernStub speichern = new SpeichernStub(Optional.of(export(status)));
            ExportzielStub ziel = new ExportzielStub(false);
            AtomicInteger mails = new AtomicInteger();
            AblageAufruf ablage = ziel(ziel);

            handler(speichern, ablage.port, Stufe.B, mails).ausfuehren(auftrag(nutzlast(EXPORT_ID)));

            assertThat(ablage.lebenszyklusAufrufe).as("Lebenszyklus bei %s", status).hasValue(0);
            assertThat(ziel.abgeschlossen).as("abgeschlossen bei %s", status).isFalse();
            assertThat(ziel.geschlossen).as("geschlossen bei %s", status).isFalse();
            assertThat(speichern.fertigAufrufe).as("fertig bei %s", status).isZero();
            assertThat(mails).as("Mails bei %s", status).hasValue(0);
        }
    }

    @Test
    void bereitsAbgeschlossenerExportWirdNichtGemeldet() throws Exception {
        SpeichernStub speichern = new SpeichernStub(Optional.of(export(ExportStatus.OFFEN)));
        speichern.fertigErgebnis = false;
        ExportzielStub ziel = new ExportzielStub(false);
        AtomicInteger mails = new AtomicInteger();

        handler(speichern, ziel(ziel).port, Stufe.B, mails).ausfuehren(auftrag(nutzlast(EXPORT_ID)));

        assertThat(speichern.fertigAufrufe).isEqualTo(1);
        assertThat(ziel.abgeschlossen).isTrue();
        assertThat(ziel.geschlossen).isTrue();
        assertThat(mails).hasValue(0);
    }

    @Test
    void stufePSchliesstExportAbAberVersendetKeineMail() throws Exception {
        SpeichernStub speichern = new SpeichernStub(Optional.of(export(ExportStatus.OFFEN)));
        ExportzielStub ziel = new ExportzielStub(false);
        AtomicInteger mails = new AtomicInteger();

        handler(speichern, ziel(ziel).port, Stufe.P, mails).ausfuehren(auftrag(nutzlast(EXPORT_ID)));

        assertThat(speichern.fertigAufrufe).isEqualTo(1);
        assertThat(mails).hasValue(0);
    }

    @Test
    void andereStufenMeldenDenAbgeschlossenenExportGenauEinmal() throws Exception {
        for (Stufe stufe : List.of(Stufe.A, Stufe.B)) {
            SpeichernStub speichern = new SpeichernStub(Optional.of(export(ExportStatus.OFFEN)));
            AtomicInteger mails = new AtomicInteger();

            handler(speichern, ziel(new ExportzielStub(false)).port, stufe, mails)
                    .ausfuehren(auftrag(nutzlast(EXPORT_ID)));

            assertThat(speichern.fertigAufrufe).isEqualTo(1);
            assertThat(mails).as("Mail für Stufe %s", stufe).hasValue(1);
        }
    }

    @Test
    void schreibfehlerSchliesstExportzielOhneAbschlussDerDatenbankzeile() {
        SpeichernStub speichern = new SpeichernStub(Optional.of(export(ExportStatus.OFFEN)));
        ExportzielStub ziel = new ExportzielStub(true);

        assertThatThrownBy(() -> handler(speichern, ziel(ziel).port, Stufe.B, new AtomicInteger())
                .ausfuehren(auftrag(nutzlast(EXPORT_ID))))
                .isInstanceOf(IOException.class)
                .hasMessage("synthetischer Schreibfehler");

        assertThat(ziel.geschlossen).isTrue();
        assertThat(ziel.strom.geschlossen).isTrue();
        assertThat(ziel.abgeschlossen).isFalse();
        assertThat(speichern.fertigAufrufe).isZero();
    }

    @Test
    void letzterFehlversuchSpeichertFehlerIdUndLoggtNurDieMeldung() {
        SpeichernStub speichern = new SpeichernStub(Optional.empty());
        Auftrag auftrag = auftrag(nutzlast(EXPORT_ID));
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThatCode(() -> handler(speichern, ziel(new ExportzielStub(false)).port, Stufe.B,
                    new AtomicInteger()).nachLetztemFehlversuch(auftrag, FEHLER_ID))
                    .doesNotThrowAnyException();

            assertThat(speichern.fehlerExportId).isEqualTo(EXPORT_ID);
            assertThat(speichern.fehlerId).isEqualTo(FEHLER_ID);
            assertThat(log.eintraege()).filteredOn(e -> ExportJahrHandler.class.getName().equals(e.getLoggerName()))
                    .singleElement().satisfies(e -> assertThat(log.nachricht(e))
                            .isEqualTo("Export " + EXPORT_ID + " gescheitert (fehler-id=" + FEHLER_ID + ")"));
        }
    }

    @Test
    void ohneSchreibenDerFehlerzeileGibtEsKeinLog() {
        SpeichernStub speichern = new SpeichernStub(Optional.empty());
        speichern.fehlerErgebnis = false;
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            handler(speichern, ziel(new ExportzielStub(false)).port, Stufe.B, new AtomicInteger())
                    .nachLetztemFehlversuch(auftrag(nutzlast(EXPORT_ID)), FEHLER_ID);

            assertThat(speichern.fehlerExportId).isEqualTo(EXPORT_ID);
            assertThat(log.eintraege())
                    .filteredOn(e -> ExportJahrHandler.class.getName().equals(e.getLoggerName())).isEmpty();
        }
    }

    private static ExportJahrHandler handler(SpeichernStub speichern, AblagePort ablage, Stufe stufe,
                                               AtomicInteger mails) {
        String konfigurierteStufe = stufe == Stufe.P ? Stufe.B.name() : stufe.name();
        ExportJahrHandler handler = new ExportJahrHandler(speichern, leereBelege(), ablage,
                mandant -> new MandantEinstellungen.Werte(Geschaeftsjahresende.STANDARD, Set.of()),
                (mandant, id) -> mails.incrementAndGet(), Optional.of(konfigurierteStufe));
        if (stufe == Stufe.P) {
            // Stufe P wird bis zur separaten Freigabe durch die Startkonfiguration abgewiesen.
            try {
                Field feld = ExportJahrHandler.class.getDeclaredField("stufe");
                feld.setAccessible(true);
                feld.set(handler, Stufe.P);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        return handler;
    }

    private static ExportBelege leereBelege() {
        return new ExportBelege() {
            @Override
            public List<Beleg> seite(int geschaeftsjahr, Optional<UUID> nach, int anzahl) {
                return List.of();
            }

            @Override
            public List<Manifestdatei> manifeste(java.time.LocalDate von, java.time.LocalDate bis) {
                return List.of();
            }
        };
    }

    private static AblageAufruf ziel(ExportzielStub ziel) {
        AtomicInteger lebenszyklusAufrufe = new AtomicInteger();
        AblagePort port = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "exportSchreiben" -> ziel;
                    case "exportLebenszyklusSichern" -> {
                        lebenszyklusAufrufe.incrementAndGet();
                        yield null;
                    }
                    case "kopieren" -> throw new AssertionError("Leerer Export darf keine Datei kopieren");
                    case "toString" -> "AblageTest";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unerwarteter Ablage-Aufruf: " + methode.getName());
                });
        return new AblageAufruf(port, lebenszyklusAufrufe);
    }

    private static Auftrag auftrag(Nutzlast nutzlast) {
        return new Auftrag(17, MANDANT, ExportJahrHandler.TYP, nutzlast, 0);
    }

    private static Nutzlast nutzlast(UUID exportId) {
        return new Nutzlast(Map.of(ExportJahrHandler.EXPORT_ID, exportId));
    }

    private static Export export(ExportStatus status) {
        return new Export(EXPORT_ID, 2025, status, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), "SYNTHETISCHER_BENUTZER", "INHABER",
                Instant.parse("2025-01-01T00:00:00Z"), Optional.empty());
    }

    private record AblageAufruf(AblagePort port, AtomicInteger lebenszyklusAufrufe) {
    }

    private static final class SpeichernStub extends ExportSpeichern {
        private final Optional<Export> export;
        private int fertigAufrufe;
        private boolean fertigErgebnis = true;
        private UUID fehlerExportId;
        private UUID fehlerId;
        private boolean fehlerErgebnis = true;

        private SpeichernStub(Optional<Export> export) {
            super(null, null, null);
            this.export = export;
        }

        @Override
        public Optional<Export> laden(UUID id) {
            return export.filter(e -> e.id().equals(id));
        }

        @Override
        public boolean fertig(Auftrag auftrag, Export export, AbgelegteDatei zip, int anzahl) {
            fertigAufrufe++;
            return fertigErgebnis;
        }

        @Override
        public boolean fehler(UUID id, UUID fehlerId) {
            fehlerExportId = id;
            this.fehlerId = fehlerId;
            return fehlerErgebnis;
        }
    }

    private static final class ExportzielStub implements Exportziel {
        private final TestStrom strom;
        private boolean abgeschlossen;
        private boolean geschlossen;

        private ExportzielStub(boolean schreibfehler) {
            strom = new TestStrom(schreibfehler);
        }

        @Override
        public OutputStream strom() {
            return strom;
        }

        @Override
        public AbgelegteDatei abschliessen() {
            abgeschlossen = true;
            return new AbgelegteDatei("exporte/" + EXPORT_ID + ".zip", "v1", "a".repeat(64), 100,
                    Optional.empty());
        }

        @Override
        public void close() {
            geschlossen = true;
            strom.close();
        }
    }

    private static final class TestStrom extends OutputStream {
        private final boolean schreibfehler;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean geschlossen;

        private TestStrom(boolean schreibfehler) {
            this.schreibfehler = schreibfehler;
        }

        @Override
        public void write(int b) throws IOException {
            pruefen();
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            pruefen();
            bytes.write(b, off, len);
        }

        private void pruefen() throws IOException {
            if (schreibfehler) {
                throw new IOException("synthetischer Schreibfehler");
            }
        }

        @Override
        public void close() {
            geschlossen = true;
        }
    }
}
