package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebseitenErfassungTest {
    @Test
    void unsichtbareDubletteHatKeineIdUndBindetKeineReferenz() {
        var port = new WebseitenErfassungPort() {
            boolean gesperrt;

            @Override
            public void sperren(String webseite, String referenz) {
                gesperrt = true;
            }

            @Override
            public Optional<Referenz> referenzFinden(String webseite, String referenz) {
                assertThat(gesperrt).isTrue();
                return Optional.empty();
            }

            @Override
            public void referenzSetzen(String webseite, String referenz, UUID id, String hash) {
                throw new AssertionError("Die fremde Dokument-ID darf nicht an eine neue Referenz gebunden werden");
            }

            @Override
            public void referenzLoeschen(String webseite, String referenz) {
                throw new AssertionError("Keine vorhandene Referenz");
            }

            @Override
            public Map<UUID, String> referenzen(String webseite, List<UUID> ids) {
                throw new AssertionError("Keine Referenzabfrage nötig");
            }
        };
        var dokumente = new DokumentErfassen(null, null, null, null, null, null, null) {
            @Override
            public EmpfangeneDatei pruefsumme(Path datei) {
                return new EmpfangeneDatei("a".repeat(64), 100);
            }

            @Override
            public List<UploadErgebnis> erfassen(UploadAnfrage anfrage) {
                return List.of(new UploadErgebnis.Dublette("intern.pdf", Optional.empty()));
            }
        };
        var service = new WebseitenErfassung(port, dokumente, null, null, null);
        var ergebnis = service.hochladen("probe", "ref-1", Path.of("synthetisch.pdf"), "intern.pdf", null,
                new Benutzer("token:probe", Rolle.GESCHAEFTSSTELLE));
        assertThat(ergebnis.dokument()).isNull();
        assertThat(ergebnis.dublette()).isTrue();
        assertThat(ergebnis.idempotent()).isFalse();
    }
}
