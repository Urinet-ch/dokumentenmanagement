package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.KiStand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.ki.application.MetadatenExtraktion;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Erwartete {@code version} in der Nutzlast von {@code KI_VORSCHLAG} (Folge Review PR #93, Claude S2) und
 * {@link KiVorschlagHandler#zuBearbeiten}. Ohne Quarkus, mit Attrappen; das Dokument steht ohne PDF/A im Eingangskorb
 * (Req 5.9), damit der Handler weder Ablage noch OCR braucht.
 */
class KiVorschlagHandlerVersionTest {

    private static final Instant EINGANG = Instant.parse("2026-03-15T09:00:00Z");

    private final UUID id = UUID.randomUUID();
    private final List<MetadatenExtraktion.Eingabe> eingaben = new ArrayList<>();
    private final List<Integer> gespeichertMit = new ArrayList<>();
    private int ohneVorschlag;

    @Test
    void abweichendeVersionRuftDasModellNichtAufUndSpeichertNichts(@TempDir Path ordner) throws Exception {
        handler(ordner, stand(DokumentStatus.EINGANGSKORB, 5)).ausfuehren(auftrag(4L));

        assertThat(eingaben).isEmpty();
        assertThat(gespeichertMit).isEmpty();
        assertThat(ohneVorschlag).as("geht aus VERARBEITUNG weiter, sonst wirkungslos").isEqualTo(1);
    }

    @Test
    void abweichendeVersionInVerarbeitungGehtOhneVorschlagWeiter(@TempDir Path ordner) throws Exception {
        handler(ordner, stand(DokumentStatus.VERARBEITUNG, 5)).ausfuehren(auftrag(4L));

        assertThat(eingaben).isEmpty();
        assertThat(gespeichertMit).isEmpty();
        assertThat(ohneVorschlag).isEqualTo(1);
    }

    @Test
    void gleicheVersionSpeichertMitDerVersionAusDerNutzlast(@TempDir Path ordner) throws Exception {
        handler(ordner, stand(DokumentStatus.EINGANGSKORB, 4)).ausfuehren(auftrag(4L));

        assertThat(eingaben).hasSize(1);
        assertThat(gespeichertMit).containsExactly(4);
        assertThat(ohneVorschlag).isZero();
    }

    @Test
    void ohneVersionInDerNutzlastGiltDieGeladene(@TempDir Path ordner) throws Exception {
        handler(ordner, stand(DokumentStatus.EINGANGSKORB, 7)).ausfuehren(auftrag(null));

        assertThat(eingaben).hasSize(1);
        assertThat(gespeichertMit).containsExactly(7);
    }

    @Test
    void zuBearbeitenNurInVerarbeitungOderOhnePdfaImEingangskorb() {
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.VERARBEITUNG, false, false, false))).isTrue();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.EINGANGSKORB, true, false, false))).isTrue();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.EINGANGSKORB, false, false, false))).isFalse();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.VERARBEITUNG, false, true, false))).isFalse();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.EINGANGSKORB, true, true, false))).isFalse();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.VERARBEITUNG, false, false, true))).isFalse();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(DokumentStatus.EINGANGSKORB, true, false, true))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DokumentStatus.class, names = {"VERARBEITUNG", "EINGANGSKORB"},
            mode = EnumSource.Mode.EXCLUDE)
    void zuBearbeitenNieInAnderemStatus(DokumentStatus status) {
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(status, true, false, false))).isFalse();
        assertThat(KiVorschlagHandler.zuBearbeiten(stand(status, false, false, false))).isFalse();
    }

    private Auftrag auftrag(Long version) {
        Map<String, Object> werte = new HashMap<>();
        werte.put(VerarbeitenHandler.DOKUMENT_ID, id);
        if (version != null) {
            werte.put(VerarbeitenHandler.DOKUMENT_VERSION, version);
        }
        return new Auftrag(1, new MandantCode("urinet"), VerarbeitungSpeichern.KI_VORSCHLAG, new Nutzlast(werte), 1);
    }

    private KiStand stand(DokumentStatus status, int version) {
        return new KiStand(id, status, false, version, true, false, "Volltext", EINGANG, Optional.empty(),
                "application/pdf");
    }

    private KiStand stand(DokumentStatus status, boolean pdfaFehlt, boolean bestaetigt, boolean vorschlag) {
        return new KiStand(id, status, bestaetigt, 1, pdfaFehlt, vorschlag, "Volltext", EINGANG, Optional.empty(),
                "application/pdf");
    }

    private KiVorschlagHandler handler(Path ordner, KiStand stand) {
        KiVorschlagSpeichern speichern = new KiVorschlagSpeichern(null, null, null, null) {
            @Override
            public Optional<KiStand> laden(UUID dokument) {
                return Optional.of(stand);
            }

            @Override
            public boolean ohneVorschlagWeiter(Auftrag auftrag, UUID dokument) {
                ohneVorschlag++;
                return stand.status() == DokumentStatus.VERARBEITUNG;
            }

            @Override
            public boolean vorschlagSpeichern(Auftrag auftrag, KiStand geladen, int erwarteteVersion,
                                              Vorschlag vorschlag) {
                gespeichertMit.add(erwarteteVersion);
                return true;
            }
        };
        MetadatenExtraktion extraktion = new MetadatenExtraktion(null, null, null, null, null, null, null) {
            @Override
            public Optional<Vorschlag> vorschlagen(Eingabe eingabe) {
                eingaben.add(eingabe);
                return Optional.of(new Vorschlag("QUITTUNG", null, null, null, null, null, null, null, null, null,
                        null, null, null, "INTERN", List.of(), Map.of(), false, null));
            }
        };
        return new KiVorschlagHandler(speichern, extraktion, null, null, ordner, 20, Duration.ofSeconds(45),
                8_750_000);
    }
}
