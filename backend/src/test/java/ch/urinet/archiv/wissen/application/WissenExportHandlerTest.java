package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenExportHandlerTest {
    private static final UUID ID = UUID.randomUUID();
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final Auftrag AUFTRAG = new Auftrag(1, MANDANT, WissenExportHandler.TYP,
            new Nutzlast(Map.of(WissenExportHandler.EXPORT_ID, ID)), 0);
    private static final AbgelegteDatei DATEI = new AbgelegteDatei("exporte/probe.zip", "v1",
            "a".repeat(64), 5, Optional.empty());

    @Test
    void offenerExportWirdGeschriebenUndMitErgebnisAbgeschlossen() throws Exception {
        SpeicherStub speichern = new SpeicherStub(WissenExportRepository.Status.OFFEN);
        ZielStub ziel = new ZielStub();
        WissenExportHandler handler = new WissenExportHandler(speichern,
                (mandant, strom) -> {
                    assertThat(mandant).isEqualTo(MANDANT);
                    strom.write("probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }, ablage(ziel));

        handler.ausfuehren(AUFTRAG);

        assertThat(ziel.strom.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("probe");
        assertThat(ziel.abgeschlossen).isTrue();
        assertThat(ziel.geschlossen).isTrue();
        assertThat(speichern.datei).isEqualTo(DATEI);
        assertThat(speichern.auftrag).isEqualTo(AUFTRAG);
    }

    @Test
    void schreibfehlerBrichtUploadAbUndMeldetKeinenErfolg() {
        SpeicherStub speichern = new SpeicherStub(WissenExportRepository.Status.OFFEN);
        ZielStub ziel = new ZielStub();
        WissenExportHandler handler = new WissenExportHandler(speichern,
                (mandant, strom) -> { throw new IOException("synthetischer Schreibfehler"); }, ablage(ziel));

        assertThatThrownBy(() -> handler.ausfuehren(AUFTRAG))
                .isInstanceOf(IOException.class).hasMessage("synthetischer Schreibfehler");
        assertThat(ziel.geschlossen).isTrue();
        assertThat(ziel.abgeschlossen).isFalse();
        assertThat(speichern.datei).isNull();
    }

    @Test
    void abgeschlossenerExportWirdNichtErneutGeschrieben() throws Exception {
        SpeicherStub speichern = new SpeicherStub(WissenExportRepository.Status.FERTIG);
        ZielStub ziel = new ZielStub();
        WissenExportHandler handler = new WissenExportHandler(speichern,
                (mandant, strom) -> { throw new AssertionError("Export darf nicht erneut geschrieben werden"); },
                ablage(ziel));

        handler.ausfuehren(AUFTRAG);

        assertThat(ziel.geschlossen).isFalse();
        assertThat(speichern.datei).isNull();
    }

    private static AblagePort ablage(ZielStub ziel) {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "exportLebenszyklusSichern" -> null;
                    case "wissensExportSchreiben" -> ziel;
                    default -> throw new AssertionError("Unerwarteter Ablageaufruf: " + methode.getName());
                });
    }

    private static class SpeicherStub extends WissenExportSpeichern {
        private final WissenExportRepository.Export export;
        private Auftrag auftrag;
        private AbgelegteDatei datei;

        SpeicherStub(WissenExportRepository.Status status) {
            super(null, null, null);
            export = new WissenExportRepository.Export(ID, status, "probe", "INHABER", Instant.now(), null,
                    null, null, null, null, null);
        }

        @Override
        public Optional<WissenExportRepository.Export> laden(UUID id) {
            assertThat(id).isEqualTo(ID);
            return Optional.of(export);
        }

        @Override
        public boolean fertig(Auftrag auftrag, WissenExportRepository.Export export, AbgelegteDatei datei) {
            this.auftrag = auftrag;
            this.datei = datei;
            return true;
        }
    }

    private static class ZielStub implements Exportziel {
        private final ByteArrayOutputStream strom = new ByteArrayOutputStream();
        private boolean abgeschlossen;
        private boolean geschlossen;

        @Override
        public OutputStream strom() {
            return strom;
        }

        @Override
        public AbgelegteDatei abschliessen() {
            abgeschlossen = true;
            return DATEI;
        }

        @Override
        public void close() {
            geschlossen = true;
        }
    }
}
