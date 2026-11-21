package ch.urinet.archiv.wissenpaket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PaketWerkzeugTestHelfer {
    private PaketWerkzeugTestHelfer() {
    }

    public static JsonNode pruefen(byte[] zip, Path datei) throws Exception {
        Files.write(datei, zip);
        StringWriter ausgabe = new StringWriter();
        StringWriter fehler = new StringWriter();
        int status = WissenPaketCli.command(new PrintWriter(ausgabe, true), new PrintWriter(fehler, true))
                .execute("validate", datei.toString(), "--json");
        if (status != 0 && status != 1 || !fehler.toString().isBlank()) {
            throw new IllegalStateException("Werkzeug lieferte eine unerwartete Antwort");
        }
        return new ObjectMapper().readTree(ausgabe.toString());
    }
}
