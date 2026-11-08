package ch.urinet.archiv.spike.ki;

import ch.urinet.archiv.spike.ki.KiDienste.ArchivAssistent;
import ch.urinet.archiv.spike.ki.KiDienste.ArchivWerkzeuge;
import ch.urinet.archiv.spike.ki.KiDienste.JsonModus;
import ch.urinet.archiv.spike.ki.KiDienste.MetadatenExtraktor;
import ch.urinet.archiv.spike.ki.MetadatenVorschlag.Richtung;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Führt alle Prüfungen des Spikes aus. Nur synthetische Belege, keine echten Daten. */
@QuarkusMain
public class SpikeMain implements QuarkusApplication {

    private static final int WIEDERHOLUNGEN = 3;

    record Erwartung(String art, LocalDate datum, String belegnummer, String partner, BigDecimal betrag,
                     Richtung richtung, LocalDate faelligkeit) {
    }

    record Beleg(String id, String text, Erwartung erwartet) {
    }

    static final List<Beleg> BELEGE = List.of(
            new Beleg("S1-ausgangsrechnung", """
                    Urinet Aschwanden, Dorfstrasse 1, 6460 Altdorf, UID CHE-111.222.333
                    Rechnung Nr. 2026-003
                    Altdorf, 3. Februar 2026
                    An: Muster Treuhand AG, Bahnhofstrasse 5, 6003 Luzern
                    Wartung Webseite Januar 2026          CHF 120.50
                    Total                                  CHF 120.50
                    Zahlbar innert 30 Tagen bis 5. März 2026
                    """,
                    new Erwartung("AUSGANGSRECHNUNG", LocalDate.of(2026, 2, 3), "2026-003", "Muster Treuhand",
                            new BigDecimal("120.50"), Richtung.AUSGANG, LocalDate.of(2026, 3, 5))),
            new Beleg("S2-lieferantenbeleg", """
                    Beispiel Energie AG, Werkstrasse 9, 6460 Altdorf, UID CHE-999.888.777
                    Rechnung 2026-123
                    Datum: 15.01.2026
                    Kunde: Urinet Aschwanden, Dorfstrasse 1, 6460 Altdorf
                    Strom Dezember 2025                    CHF 87.35
                    MWST 8.1% enthalten
                    Zahlbar bis 14.02.2026 auf IBAN CH93 0076 2011 6238 5295 7
                    """,
                    new Erwartung("LIEFERANTENBELEG", LocalDate.of(2026, 1, 15), "2026-123", "Beispiel Energie",
                            new BigDecimal("87.35"), Richtung.EINGANG, LocalDate.of(2026, 2, 14))),
            new Beleg("S3-offerte", """
                    Urinet Aschwanden, Dorfstrasse 1, 6460 Altdorf, UID CHE-111.222.333
                    Offerte O-2026-005
                    Altdorf, 20.10.2026
                    Für: Verein Beispielchor, 6460 Altdorf
                    Neue Webseite, pauschal                CHF 2'400.00
                    Gültig bis 30.11.2026
                    """,
                    new Erwartung("OFFERTE", LocalDate.of(2026, 10, 20), "O-2026-005", "Beispielchor",
                            new BigDecimal("2400.00"), Richtung.AUSGANG, null)));

    @Inject
    MetadatenExtraktor extraktor;
    @Inject
    JsonModus jsonModus;
    @Inject
    ArchivAssistent assistent;
    @Inject
    ArchivWerkzeuge werkzeuge;
    @Inject
    ChatModel chatModel;
    @Inject
    EmbeddingModel embeddingModel;
    @Inject
    RerankerClient reranker;

    @ConfigProperty(name = TokenDateiConfigSourceFactory.TOKEN_DATEI)
    Path tokenDatei;
    @ConfigProperty(name = "archiv.ki.chat-modell")
    String chatModell;
    @ConfigProperty(name = "archiv.ki.embedding-modell")
    String embeddingModell;

    @Override
    @ActivateRequestContext
    public int run(String... args) {
        if (!Files.isReadable(tokenDatei)) {
            System.err.println("Token-Datei fehlt oder ist nicht lesbar: " + tokenDatei);
            return 2;
        }
        System.out.println("Chat-Modell: " + chatModell + ", Embedding-Modell: " + embeddingModell);
        pruefe("1 JSON-Schema-Antwortformat", this::jsonSchema);
        pruefe("2 JSON-Modus (json_object)", this::jsonModus);
        pruefe("3 Tool Calling", this::werkzeuge);
        pruefe("4 Embeddings", this::embeddings);
        pruefe("5 Bild-Eingabe (K1)", this::bild);
        pruefe("6 Reranker", this::rerank);
        return 0;
    }

    private void pruefe(String name, Callable<String> pruefung) {
        System.out.println("\n== " + name);
        long start = System.nanoTime();
        try {
            System.out.println(pruefung.call());
        } catch (Exception e) {
            System.out.println("FEHLER: " + e.getClass().getSimpleName() + ": " + kurz(e.getMessage()));
        }
        System.out.printf("(%d ms)%n", (System.nanoTime() - start) / 1_000_000);
    }

    private String jsonSchema() {
        return "Variante A (Record aus design.md: LocalDate, Map):\n"
                + extraktion(text -> {
                    Result<MetadatenVorschlag> r = extraktor.extrahiere(text);
                    return new Ergebnis(r.content(), r.tokenUsage());
                })
                + "\n\nVariante B (Datum als ISO-Text, Sicherheit mit festen Feldern):\n"
                + extraktion(text -> {
                    Result<MetadatenVorschlagIso> r = extraktor.extrahiereIso(text);
                    return new Ergebnis(r.content().alsVorschlag(), r.tokenUsage());
                });
    }

    record Ergebnis(MetadatenVorschlag vorschlag, TokenUsage tokens) {
    }

    private String extraktion(Function<String, Ergebnis> aufruf) {
        StringBuilder bericht = new StringBuilder();
        int felder = 0;
        int richtig = 0;
        for (Beleg beleg : BELEGE) {
            for (int i = 1; i <= WIEDERHOLUNGEN; i++) {
                Ergebnis e = aufruf.apply(beleg.text());
                List<String> fehler = vergleiche(beleg.erwartet(), e.vorschlag());
                felder += 7;
                richtig += 7 - fehler.size();
                bericht.append(beleg.id()).append(" #").append(i)
                        .append(fehler.isEmpty() ? " alle Felder richtig" : " falsch: " + fehler)
                        .append(" | Tokens ein/aus ").append(e.tokens().inputTokenCount())
                        .append('/').append(e.tokens().outputTokenCount())
                        .append(" | sicherheit ").append(e.vorschlag().sicherheit()).append('\n');
            }
        }
        return bericht.append("Treffer: ").append(richtig).append('/').append(felder).toString();
    }

    private static List<String> vergleiche(Erwartung e, MetadatenVorschlag v) {
        List<String> fehler = new ArrayList<>();
        if (!e.art().equals(v.art())) fehler.add("art=" + v.art());
        if (!e.datum().equals(v.datum())) fehler.add("datum=" + v.datum());
        if (!e.belegnummer().equals(v.belegnummer())) fehler.add("belegnummer=" + v.belegnummer());
        if (v.partnerName() == null || !v.partnerName().contains(e.partner())) fehler.add("partner=" + v.partnerName());
        if (v.betrag() == null || e.betrag().compareTo(v.betrag()) != 0) fehler.add("betrag=" + v.betrag());
        if (e.richtung() != v.richtung()) fehler.add("richtung=" + v.richtung());
        if (!Objects.equals(e.faelligkeit(), v.faelligkeit())) fehler.add("faelligkeit=" + v.faelligkeit());
        return fehler;
    }

    private String jsonModus() {
        return "Antwort: " + jsonModus.extrahiere(BELEGE.getFirst().text());
    }

    private String werkzeuge() {
        werkzeuge.aufrufe().clear();
        String antwort = assistent.frage(
                "Wie viel haben wir 2026 der Muster Treuhand AG fakturiert, und wie viele Rechnungen sind dort offen?");
        return "Aufrufe: " + werkzeuge.aufrufe() + "\nAntwort: " + antwort;
    }

    private String embeddings() {
        List<TextSegment> texte = BELEGE.stream().map(b -> TextSegment.from(b.text())).toList();
        Response<List<Embedding>> antwort = embeddingModel.embedAll(texte);
        Embedding frage = embeddingModel.embed("Stromrechnung vom Januar").content();
        StringBuilder bericht = new StringBuilder("Dimension: ")
                .append(antwort.content().getFirst().dimension())
                .append(" | Tokens ").append(antwort.tokenUsage())
                .append("\nKosinus «Stromrechnung vom Januar»:");
        for (int i = 0; i < texte.size(); i++) {
            bericht.append(String.format("%n   %s %.4f", BELEGE.get(i).id(), kosinus(frage, antwort.content().get(i))));
        }
        return bericht.toString();
    }

    private static double kosinus(Embedding a, Embedding b) {
        float[] x = a.vector();
        float[] y = b.vector();
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < x.length; i++) {
            dot += x[i] * y[i];
            nx += x[i] * x[i];
            ny += y[i] * y[i];
        }
        return dot / Math.sqrt(nx * ny);
    }

    private String bild() throws Exception {
        String png = Base64.getEncoder().encodeToString(alsBild(BELEGE.get(1).text()));
        ChatResponse antwort = chatModel.chat(ChatRequest.builder().messages(UserMessage.from(
                        TextContent.from("Lies den Beleg im Bild. Antworte in einer Zeile: Belegnummer; Datum (JJJJ-MM-TT); Betrag; Aussteller."),
                        ImageContent.from(png, "image/png")))
                .build());
        String text = antwort.aiMessage().text();
        boolean gelesen = text.contains("2026-123") && text.contains("87.35")
                && (text.contains("2026-01-15") || text.contains("15.01.2026"));
        return (gelesen ? "gelesen" : "NICHT korrekt gelesen") + " | Tokens " + antwort.tokenUsage() + "\nAntwort: " + text;
    }

    private static byte[] alsBild(String text) throws Exception {
        BufferedImage bild = new BufferedImage(1100, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, bild.getWidth(), bild.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
        int y = 50;
        for (String zeile : text.split("\n")) {
            g.drawString(zeile, 40, y);
            y += 48;
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bild, "png", out);
        return out.toByteArray();
    }

    private String rerank() throws Exception {
        List<String> dokumente = BELEGE.stream().map(Beleg::text).toList();
        JsonNode antwort = reranker.rerank("Stromrechnung vom Januar", dokumente, 2);
        StringBuilder bericht = new StringBuilder("Felder: ");
        antwort.fieldNames().forEachRemaining(f -> bericht.append(f).append(' '));
        bericht.append("\nusage: ").append(antwort.path("usage"));
        for (JsonNode r : antwort.path("results")) {
            bericht.append(String.format("%n   index %d (%s) relevance_score %.4f",
                    r.path("index").asInt(), BELEGE.get(r.path("index").asInt()).id(), r.path("relevance_score").asDouble()));
        }
        return bericht.toString();
    }

    private static String kurz(String s) {
        return s == null ? "" : s.length() > 600 ? s.substring(0, 600) + " …" : s;
    }
}
