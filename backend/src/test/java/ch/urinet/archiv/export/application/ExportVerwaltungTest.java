package ch.urinet.archiv.export.application;

import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportVerwaltungTest {

    @Test
    void listeFordertHöchstensLISTEExporteAnUndÜbernimmtDieNeuesteZuerst() {
        List<Export> neuesteZuerst = new ArrayList<>();
        for (int i = 0; i < ExportVerwaltung.LISTE; i++) {
            neuesteZuerst.add(new Export(new UUID(0, i + 1), 2025 - i, ExportStatus.FERTIG,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    "synthetischer-benutzer", "INHABER", Instant.parse("2025-12-31T23:59:59Z").minusSeconds(i),
                    Optional.empty()));
        }
        List<Integer> angeforderteGrenzen = new ArrayList<>();
        ExportRepository repository = new ExportRepository() {
            @Override
            public boolean anlegen(UUID id, int geschaeftsjahr, String benutzer, String rolle) {
                throw new AssertionError("Unerwarteter Aufruf");
            }

            @Override
            public Optional<Export> laden(UUID id) {
                throw new AssertionError("Unerwarteter Aufruf");
            }

            @Override
            public List<Export> neueste(int anzahl) {
                angeforderteGrenzen.add(anzahl);
                return neuesteZuerst;
            }

            @Override
            public boolean fertig(UUID id, ch.urinet.archiv.ablage.domain.AbgelegteDatei zip, int anzahl) {
                throw new AssertionError("Unerwarteter Aufruf");
            }

            @Override
            public boolean fehler(UUID id, UUID fehlerId) {
                throw new AssertionError("Unerwarteter Aufruf");
            }
        };

        List<Export> ergebnis = new ExportVerwaltung(repository, null, null, null, null)
                .liste(new Benutzer("synthetischer-benutzer", Rolle.INHABER));

        assertThat(angeforderteGrenzen).containsExactly(ExportVerwaltung.LISTE);
        assertThat(ergebnis).hasSize(ExportVerwaltung.LISTE).containsExactlyElementsOf(neuesteZuerst);
        assertThat(ergebnis).isSortedAccordingTo((a, b) -> b.erstellt().compareTo(a.erstellt()));
    }
}
