package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.domain.Stufenregel;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.Sperrpruefung;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Admin-Kommando für den unabhängigen Soll-/Ist-Abgleich der Restore-Probe. */
@ApplicationScoped
public class SperrpruefungKommando {

    public static final String AUFRUF = "Aufruf: archiv sicherung sperren-pruefen --mandant <code> "
            + "[--stichprobe <0..50>]";
    private static final int STICHPROBE_STANDARD = 5;

    private final Sperrpruefung pruefung;
    private final MandantRegister register;
    private final ObjectMapper json;
    private final String bucketPraefix;
    private final Path arbeitsverzeichnis;

    @Inject
    public SperrpruefungKommando(Sperrpruefung pruefung, MandantRegister register, ObjectMapper json,
                                 @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix,
                                 @ConfigProperty(name = "archiv.arbeit.verzeichnis") Path arbeitsverzeichnis) {
        this.pruefung = pruefung;
        this.register = register;
        this.json = json;
        this.bucketPraefix = bucketPraefix;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
    }

    public record Aufruf(MandantCode mandant, int stichprobe) {
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.isEmpty() || !argumente.getFirst().equals("sperren-pruefen")) {
            return Optional.empty();
        }
        Optional<MandantCode> mandant = Optional.empty();
        int stichprobe = STICHPROBE_STANDARD;
        boolean stichprobeGesetzt = false;
        try {
            for (int i = 1; i < argumente.size(); i++) {
                String schalter = argumente.get(i);
                if (i + 1 >= argumente.size()) {
                    return Optional.empty();
                }
                String wert = argumente.get(++i);
                switch (schalter) {
                    case "--mandant" -> {
                        if (mandant.isPresent()) {
                            return Optional.empty();
                        }
                        mandant = Optional.of(new MandantCode(wert));
                    }
                    case "--stichprobe" -> {
                        if (stichprobeGesetzt) {
                            return Optional.empty();
                        }
                        stichprobe = Integer.parseInt(wert);
                        if (stichprobe < 0 || stichprobe > 50) {
                            return Optional.empty();
                        }
                        stichprobeGesetzt = true;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (mandant.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(mandant.orElseThrow(), stichprobe));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        if (!bucketPraefix.startsWith(Stufenregel.PRAEFIX_STUFE_P)) {
            fehler.println("Sperrprüfung ist nur mit dem Präfix der Restore-Probe zulässig.");
            return 2;
        }
        Optional<Mandant> mandant;
        try {
            mandant = register.aktive().stream()
                    .filter(eintrag -> eintrag.code().equals(aufruf.get().mandant())).findFirst();
        } catch (RuntimeException e) {
            fehler.println("Sperrprüfung fehlgeschlagen: " + e.getClass().getSimpleName());
            return 3;
        }
        if (mandant.isEmpty()) {
            fehler.println("Unbekannter oder inaktiver Mandant.");
            return 2;
        }
        try (Sperrpruefung.Ergebnis ergebnis = pruefung.pruefen(mandant.get(), aufruf.get().stichprobe(),
                arbeitsverzeichnis)) {
            json(aus, ergebnis);
            if (aus.checkError()) {
                throw new IOException("Ausgabestrom nicht schreibbar");
            }
            return ergebnis.ohneAbweichung() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            fehler.println("Sperrprüfung fehlgeschlagen: " + e.getClass().getSimpleName());
            return 3;
        }
    }

    private void json(PrintStream aus, Sperrpruefung.Ergebnis ergebnis) throws IOException {
        try (JsonGenerator generator = json.getFactory().createGenerator(aus)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            generator.writeObjectFieldStart("zaehler");
            for (Sperrpruefung.Abweichung abweichung : Sperrpruefung.Abweichung.values()) {
                generator.writeNumberField(abweichung.name(), ergebnis.zaehler().get(abweichung));
            }
            generator.writeEndObject();
            ids(generator, "dokumentIds", ergebnis.dokumentIds());
            ids(generator, "manifestIds", ergebnis.manifestIds());
            generator.writeEndObject();
            generator.flush();
        }
        aus.println();
    }

    private static void ids(JsonGenerator generator, String feld, Path datei) throws IOException {
        generator.writeArrayFieldStart(feld);
        try (BufferedReader leser = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            for (String id; (id = leser.readLine()) != null; ) {
                generator.writeString(id);
            }
        }
        generator.writeEndArray();
    }
}
