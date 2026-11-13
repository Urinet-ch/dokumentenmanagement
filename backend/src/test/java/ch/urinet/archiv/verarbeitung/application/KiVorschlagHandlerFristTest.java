package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.KiStand;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.ki.application.MetadatenExtraktion;
import ch.urinet.archiv.ki.application.SeiteUngeeignet;
import ch.urinet.archiv.ki.application.SeitenbildPort;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Frist des Renderns im {@link KiVorschlagHandler} (Nachtrag PR #93): Reicht {@code archiv.ki.render-zeitgrenze} nicht,
 * geht das Dokument mit dem Text und ohne QR bzw. Seitenbild weiter, statt als Fehlversuch dreimal dasselbe zu
 * versuchen. Ebenso ohne Seitenbild, wenn eine Seite einen ungültigen Kopf oder zu viele Pixel hat (Review PR #120).
 * Andere Fehler beim Rendern bleiben Fehlversuche. Ohne Quarkus, mit Attrappen der Ports.
 */
class KiVorschlagHandlerFristTest {

    private static final Instant EINGANG = Instant.parse("2026-03-15T09:00:00Z");

    private final UUID id = UUID.randomUUID();
    private final List<MetadatenExtraktion.Eingabe> eingaben = new ArrayList<>();
    private final List<Boolean> gerendert = new ArrayList<>();
    private int ohneVorschlag;

    @Test
    void fristAbgelaufenGehtMitDemTextUndOhneQrWeiter(@TempDir Path ordner) throws Exception {
        handler(ordner, "application/pdf", OcrFehlgeschlagen.Art.ZEITUEBERSCHREITUNG, false).ausfuehren(auftrag());

        assertThat(gerendert).containsExactly(false);
        assertThat(eingaben).singleElement().satisfies(e -> {
            assertThat(e.text()).isEqualTo("Text mit Layout");
            assertThat(e.qr()).isEmpty();
            assertThat(e.bild()).isEmpty();
        });
        assertThat(ohneVorschlag).isEqualTo(1);
    }

    @Test
    void imBildwegFehltNachDerFristNurDasSeitenbild(@TempDir Path ordner) throws Exception {
        handler(ordner, "image/jpeg", OcrFehlgeschlagen.Art.ZEITUEBERSCHREITUNG, true).ausfuehren(auftrag());

        assertThat(gerendert).containsExactly(false, true);
        assertThat(eingaben).singleElement().satisfies(e -> {
            assertThat(e.text()).isEqualTo("Text mit Layout");
            assertThat(e.bild()).isEmpty();
        });
    }

    @Test
    void andererFehlerBeimRendernBleibtFehlversuch(@TempDir Path ordner) {
        KiVorschlagHandler handler = handler(ordner, "application/pdf", OcrFehlgeschlagen.Art.WERKZEUG, false);

        assertThatThrownBy(() -> handler.ausfuehren(auftrag())).isInstanceOf(OcrFehlgeschlagen.class);
        assertThat(eingaben).isEmpty();
        assertThat(ohneVorschlag).isZero();
    }

    @Test
    void ungeeigneteSeiteGehtOhneSeitenbildWeiter(@TempDir Path ordner) throws Exception {
        handler(ordner, "image/jpeg", seiten -> {
            throw new SeiteUngeeignet("Seite mit 9000000 Pixeln über der Grenze");
        }).ausfuehren(auftrag());

        assertThat(gerendert).containsExactly(false, true);
        assertThat(eingaben).singleElement().satisfies(e -> {
            assertThat(e.text()).isEqualTo("Text mit Layout");
            assertThat(e.bild()).isEmpty();
        });
        assertThat(ohneVorschlag).isEqualTo(1);
    }

    @Test
    void andererLesefehlerDesSeitenbildsBleibtFehlversuch(@TempDir Path ordner) {
        KiVorschlagHandler handler = handler(ordner, "image/jpeg", seiten -> {
            throw new IOException("PPM zu kurz");
        });

        assertThatThrownBy(() -> handler.ausfuehren(auftrag())).isInstanceOf(OcrFehlgeschlagen.class);
        assertThat(eingaben).isEmpty();
        assertThat(ohneVorschlag).isZero();
    }

    private Auftrag auftrag() {
        return new Auftrag(1, new MandantCode("urinet"), VerarbeitungSpeichern.KI_VORSCHLAG,
                new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id)), 0);
    }

    /**
     * Handler mit Attrappen: {@code fehler} tritt beim Rendern auf, beim farbigen Rendern ({@code nurFarbig}) oder
     * schon beim grauen.
     */
    private KiVorschlagHandler handler(Path ordner, String mime, OcrFehlgeschlagen.Art fehler, boolean nurFarbig) {
        return handler(ordner, mime, fehler, nurFarbig, null);
    }

    /**
     * Handler ohne Fehler beim Rendern; das Seitenbild kommt aus {@code seitenbild}. Wie im echten Adapter wird ein
     * Lesefehler der Verwertung zu {@link OcrFehlgeschlagen} mit {@code EIN_AUSGABE}.
     */
    private KiVorschlagHandler handler(Path ordner, String mime, SeitenbildPort seitenbild) {
        return handler(ordner, mime, null, false, seitenbild);
    }

    private KiVorschlagHandler handler(Path ordner, String mime, OcrFehlgeschlagen.Art fehler, boolean nurFarbig,
                                       SeitenbildPort seitenbild) {
        String schluessel = DateiSchluessel.pdfa(2026, id).pfad(Ablageort.EINGANG);
        KiStand stand = new KiStand(id, DokumentStatus.VERARBEITUNG, false, 1, false, false, "Volltext", EINGANG,
                Optional.of(new Datei(DateiRolle.PDFA, 1, "application/pdf", schluessel, "v1", "ab".repeat(32))),
                mime);
        KiVorschlagSpeichern speichern = new KiVorschlagSpeichern(null, null, null, null) {
            @Override
            public Optional<KiStand> laden(UUID dokument) {
                return Optional.of(stand);
            }

            @Override
            public boolean ohneVorschlagWeiter(Auftrag auftrag, UUID dokument) {
                ohneVorschlag++;
                return true;
            }
        };
        MetadatenExtraktion extraktion = new MetadatenExtraktion(null, (dokument, seiten) -> Optional.empty(),
                seitenbild, null, null, null, null) {
            @Override
            public Optional<Vorschlag> vorschlagen(Eingabe eingabe) {
                eingaben.add(eingabe);
                return Optional.empty();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                    if (!methode.getName().equals("lesenEingang")) {
                        throw new UnsupportedOperationException(methode.getName());
                    }
                    Files.write((Path) argumente[4], new byte[]{'%'});
                    return null;
                });
        OcrPort ocr = new OcrPort() {
            @Override
            public <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String layoutText(Path pdf, Duration zeitgrenze) {
                return "Text mit Layout";
            }

            @Override
            public <T> T seitenbilder(Path pdf, Render render, Seitenverwertung<T> verwertung) {
                gerendert.add(render.farbig());
                if (fehler != null && (render.farbig() || !nurFarbig)) {
                    throw new OcrFehlgeschlagen(fehler, "Testfehler");
                }
                try {
                    return verwertung.verwerten(fehler == null ? List.of(ordner.resolve("seite-1.ppm")) : List.of());
                } catch (IOException e) {
                    throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Seitenbild nicht lesbar", e);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        return new KiVorschlagHandler(speichern, extraktion, ablage, ocr, ordner, 20, Duration.ofSeconds(45),
                8_750_000);
    }
}
