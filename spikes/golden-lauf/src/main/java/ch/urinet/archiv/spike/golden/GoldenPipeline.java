package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import ch.urinet.archiv.spike.golden.SwissQrLeser.QrDaten;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ein Beleg: OCR → Swiss QR → genau ein KI-Aufruf (bei Schemafehler eine Nachfrage, Req 7.3) → Nachbearbeitung → Vergleich. */
@ApplicationScoped
public class GoldenPipeline {

    public record BelegErgebnis(Erwartung erwartung, Nachbearbeitung.Ergebnis vorschlag, Map<Feld, Boolean> richtig,
                                boolean qrGefunden, int aufrufe, long tokensEin, long tokensAus, long ocrMs, long kiMs,
                                String fehler) {

        public boolean gateRichtig() {
            for (Feld f : Vergleich.GATE_FELDER) {
                if (!richtig.get(f)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Inject
    MetadatenExtraktor extraktor;

    @ConfigProperty(name = "golden.ocr.image")
    String image;
    @ConfigProperty(name = "golden.ocr.sprachen")
    String sprachen;
    @ConfigProperty(name = "golden.ocr.zeitgrenze")
    Duration zeitgrenze;
    @ConfigProperty(name = "golden.eigene-arten")
    List<String> eigeneArten;

    public BelegErgebnis verarbeite(Erwartung e, Mandant mandant, List<Dokumentarten.Art> arten, Path cache, boolean k1) {
        long start = System.nanoTime();
        Ocr.Resultat ocr;
        Optional<QrDaten> qr;
        try {
            ocr = ocrMitCache(e.datei(), cache);
            qr = SwissQrLeser.lies(ocr.seiten());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fehlschlag(e, 0, 0, 0, (System.nanoTime() - start) / 1_000_000, 0, "OCR: " + ex.getMessage());
        }
        long ocrMs = (System.nanoTime() - start) / 1_000_000;

        String text = Prompt.gekuerzt(ocr.text());
        String artenListe = Dokumentarten.alsPromptListe(arten, eigeneArten);
        long kiStart = System.nanoTime();
        int aufrufe = 0;
        long ein = 0, aus = 0;
        Result<MetadatenVorschlag> antwort = null;
        String fehler = null;
        Image bild = null;
        if (k1) {
            try {
                bild = Seitenbild.aus(ocr.seiten());
            } catch (IOException | RuntimeException ex) {
                return fehlschlag(e, 0, 0, 0, ocrMs, 0, "K1-Bild: " + KiFehler.grund(ex));
            }
        }
        try {
            aufrufe++;
            antwort = k1
                    ? extraktor.extrahiereBild(mandant.name(), artenListe, Prompt.qr(qr), bild)
                    : extraktor.extrahiere(mandant.name(), artenListe, Prompt.qr(qr), text);
        } catch (RuntimeException ex) {
            if (KiFehler.istSchemafehler(ex)) {
                fehler = "Schemafehler";
                try {
                    aufrufe++;
                    antwort = k1
                            ? extraktor.nachfrageBild(mandant.name(), artenListe, Prompt.qr(qr), bild, fehler)
                            : extraktor.nachfrage(mandant.name(), artenListe, Prompt.qr(qr), text, fehler);
                } catch (RuntimeException ex2) {
                    fehler = fehler + ", Nachfrage: " + KiFehler.grund(ex2);
                }
            } else {
                fehler = KiFehler.grund(ex);
            }
        }
        long kiMs = (System.nanoTime() - kiStart) / 1_000_000;
        if (antwort != null && antwort.tokenUsage() != null) {
            TokenUsage t = antwort.tokenUsage();
            ein = t.inputTokenCount() == null ? 0 : t.inputTokenCount();
            aus = t.outputTokenCount() == null ? 0 : t.outputTokenCount();
        }
        if (antwort == null || antwort.content() == null) {
            return fehlschlag(e, aufrufe, ein, aus, ocrMs, kiMs, "KI: " + fehler);
        }
        Nachbearbeitung.Ergebnis v = Nachbearbeitung.bearbeite(antwort.content(), qr, ocr.text(), mandant, arten,
                eigeneArten, LocalDate.now());
        Map<Feld, Boolean> richtig = new EnumMap<>(Feld.class);
        richtig.put(Feld.ART, Vergleich.art(e.art(), v.art()));
        richtig.put(Feld.DATUM, Vergleich.datum(e.datum(), v.datum()));
        richtig.put(Feld.PARTNER, Vergleich.partner(e.partner(), v.partner()));
        richtig.put(Feld.BETRAG, Vergleich.betrag(e.betrag(), v.betrag()));
        richtig.put(Feld.BELEGNUMMER, Vergleich.belegnummer(e.belegnummer(), v.belegnummer()));
        return new BelegErgebnis(e, v, richtig, qr.isPresent(), aufrufe, ein, aus, ocrMs, kiMs,
                aufrufe > 1 ? "Nachfrage nach " + fehler : null);
    }

    private static BelegErgebnis fehlschlag(Erwartung e, int aufrufe, long ein, long aus, long ocrMs, long kiMs,
                                            String fehler) {
        Map<Feld, Boolean> falsch = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            falsch.put(f, false);
        }
        return new BelegErgebnis(e, null, falsch, false, aufrufe, ein, aus, ocrMs, kiMs, fehler);
    }

    /** OCR-Ergebnis je Dateiinhalt (SHA-256) und Image zwischenspeichern, damit Prompt-Iterationen nicht neu OCRen. */
    private Ocr.Resultat ocrMitCache(Path datei, Path cache) throws IOException, InterruptedException {
        Path arbeit = cache.resolve(sha256(datei).substring(0, 24) + "-" + image.replaceAll("[^A-Za-z0-9.]", "_"));
        Optional<Ocr.Resultat> fertig = Ocr.ausCache(arbeit);
        if (fertig.isPresent()) {
            return fertig.get();
        }
        return new Ocr(image, sprachen, zeitgrenze).verarbeite(datei, arbeit);
    }

    private static String sha256(Path datei) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(datei), MessageDigest.getInstance("SHA-256"))) {
            DigestInputStream d = (DigestInputStream) in;
            d.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(d.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
