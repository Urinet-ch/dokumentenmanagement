package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.PdfaPruefung;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien.seite;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 5.1–5.4 und 5.8–5.10 (Aufgabe 2.6, Teil b), immer im Mandanten {@code urinet}. */
public class VerarbeitenSchritte {

    private static final String MANDANT = "urinet";

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    private UUID id;
    private Map<String, String[]> dateienVorher;
    private List<String> eingangVorher;
    /** Schlüssel → SHA-256 des Inhalts, wie er abgelegt wurde. */
    private final Map<String, String> inhalteVorher = new HashMap<>();

    @Angenommen("die Verarbeitung ist bereit")
    public void bereit() throws Exception {
        hilfe.vorbereiten();
    }

    @Angenommen("ein Dokument mit einem schiefen Foto «{}»")
    public void foto(String text) throws Exception {
        anlegen("SCAN", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "jpg", "image/jpeg",
                OcrTestdateien.jpeg(seite(3, "Testbeleg Fotografie", text, kennung())))));
    }

    @Angenommen("ein Dokument mit einem HEIC-Foto")
    public void heic() throws Exception {
        anlegen("SCAN", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "heic", "image/heic", OcrTestdateien.heic())));
    }

    @Angenommen("ein Dokument mit einem digitalen PDF «{}»")
    public void digitalesPdf(String text) throws Exception {
        anlegen("UPLOAD", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.textPdf(text, kennung()))));
    }

    @Angenommen("ein Dokument mit einem signierten PDF")
    public void signiert() throws Exception {
        anlegen("UPLOAD", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.signiertesPdf())));
    }

    @Angenommen("ein Dokument mit einem verschlüsselten PDF")
    public void verschluesselt() throws Exception {
        anlegen("UPLOAD", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf", "application/pdf",
                OcrTestdateien.verschluesseltesPdf())));
    }

    /** Originale als JPEG mit «Rohbild», Arbeitsfassungen (zugeschnitten, entzerrt) als JPEG mit «Arbeitsfassung». */
    @Angenommen("ein Dokument aus der Handy-Erfassung mit {int} Seiten")
    public void handy(int seiten) throws Exception {
        String[] namen = {"Eins", "Zwei", "Drei"};
        var dateien = new ArrayList<Quelle>();
        for (int nr = 1; nr <= seiten; nr++) {
            dateien.add(new Quelle(DateiRolle.ORIGINAL, nr, "jpg", "image/jpeg",
                    OcrTestdateien.jpeg(seite(6, "Rohbild Seite " + namen[nr - 1], kennung()))));
            dateien.add(new Quelle(DateiRolle.ARBEITSFASSUNG, nr, null, "image/jpeg",
                    OcrTestdateien.jpeg(seite(0, "Arbeitsfassung Seite " + namen[nr - 1]))));
        }
        anlegen("SCAN", dateien);
    }

    @Angenommen("die gespeicherte Prüfsumme des Originals passt nicht zur Datei im Object Storage")
    public void pruefsummeFalsch() throws Exception {
        hilfe.pruefsummeVerfaelschen(MANDANT, id);
        dateienVorher = hilfe.dateien(MANDANT, id);
    }

    @Wenn("der Worker die Aufträge bearbeitet")
    public void bearbeiten() throws Exception {
        hilfe.abarbeiten();
    }

    @Wenn("der Auftrag «VERARBEITEN» zweimal wieder fällig wird und der Worker ihn bearbeitet")
    public void zweimalWiederholen() throws Exception {
        long auftrag = hilfe.auftraege("VERARBEITEN", id).getFirst();
        for (int i = 0; i < 2; i++) {
            auftraege.aendern(auftrag, "nicht_vor = now()");
            hilfe.abarbeiten();
        }
    }

    @Dann("ist das Dokument «{word}» mit {int} Seite(n)")
    public void status(String status, int seiten) throws Exception {
        Map<String, Object> dokument = hilfe.dokument(MANDANT, id);
        assertThat(dokument).containsEntry("status", status).containsEntry("seiten", seiten)
                .containsEntry("pdfa_fehlt", false);
        assertThat(dokument.get("fehler")).isNull();
    }

    @Dann("die PDF\\/A-Fassung ist gültiges PDF\\/A-2b und stimmt mit der gespeicherten Prüfsumme überein")
    public void pdfa() throws Exception {
        Map<String, Object> dokument = hilfe.dokument(MANDANT, id);
        String schluessel = VerarbeitungTestHilfe.pdfaSchluessel(id);
        assertThat(dokument).containsEntry("s3_pdfa", schluessel);
        assertThat(hilfe.versionen(MANDANT, schluessel))
                .containsExactly(schluessel + "@" + dokument.get("s3_version_pdfa"));
        Path pdfa = hilfe.herunterladen(MANDANT, schluessel, (String) dokument.get("s3_version_pdfa"));
        try {
            assertThat(VerarbeitungTestHilfe.sha256(Files.readAllBytes(pdfa))).isEqualTo(dokument.get("sha256_pdfa"));
            assertThat(PdfaPruefung.istPdfa2b(pdfa)).isTrue();
        } finally {
            Files.deleteIfExists(pdfa);
        }
    }

    @Dann("es liegt je Seite ein Vorschaubild")
    public void vorschau() throws Exception {
        int seiten = (Integer) hilfe.dokument(MANDANT, id).get("seiten");
        String ordner = VerarbeitungTestHilfe.vorschauOrdner(id);
        var versionen = hilfe.objektversionen(MANDANT, ordner);
        assertThat(versionen).extracting(ObjectVersion::key)
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, seiten)
                        .mapToObj(s -> ordner + s + ".png").toList());
        byte[] erstes = Files.readAllBytes(hilfe.herunterladen(MANDANT, ordner + "1.png",
                versionen.stream().filter(v -> v.key().endsWith("/1.png")).findFirst().orElseThrow().versionId()));
        assertThat(erstes).startsWith(0x89, 'P', 'N', 'G');
    }

    @Dann("^der Volltext enthält «([^»]+)»$")
    public void volltext(String text) throws Exception {
        assertThat((String) hilfe.dokument(MANDANT, id).get("volltext")).contains(text);
    }

    @Dann("der Volltext enthält «{}» und nicht «{}»")
    public void volltextOhne(String text, String nicht) throws Exception {
        assertThat((String) hilfe.dokument(MANDANT, id).get("volltext")).contains(text).doesNotContain(nicht);
    }

    @Dann("die deutsche Volltextsuche nach «{}» findet das Dokument")
    public void suche(String wort) throws Exception {
        assertThat(hilfe.volltextFindet(MANDANT, id, wort)).isTrue();
    }

    @Dann("für das Dokument lief genau ein Auftrag «KI_VORSCHLAG»")
    public void kiAuftrag() throws Exception {
        assertThat(hilfe.auftragStatus("KI_VORSCHLAG", id)).containsExactly("FERTIG");
    }

    @Dann("für das Dokument lief kein Auftrag «KI_VORSCHLAG»")
    public void keinKiAuftrag() throws Exception {
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
    }

    @Dann("die Dateien aus dem Eingang sind unverändert")
    public void eingangUnveraendert() throws Exception {
        assertThat(hilfe.dateien(MANDANT, id)).usingRecursiveComparison().isEqualTo(dateienVorher);
        List<String> eingang = hilfe.versionen(MANDANT, VerarbeitungTestHilfe.eingangOrdner(id)).stream()
                .filter(v -> !v.startsWith(VerarbeitungTestHilfe.pdfaSchluessel(id))).toList();
        assertThat(eingang).containsExactlyInAnyOrderElementsOf(eingangVorher);
        for (String[] datei : dateienVorher.values()) {
            Path inhalt = hilfe.herunterladen(MANDANT, datei[1], datei[2]);
            try {
                assertThat(VerarbeitungTestHilfe.sha256(Files.readAllBytes(inhalt))).isEqualTo(inhalteVorher.get(datei[1]));
            } finally {
                Files.deleteIfExists(inhalt);
            }
        }
    }

    @Dann("ist der Auftrag «VERARBEITEN» nach {int} Versuch wieder offen und das Dokument «{word}»")
    public void wiederOffen(int versuche, String status) throws Exception {
        var zeile = auftraege.zeile(hilfe.auftraege("VERARBEITEN", id).getFirst());
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(versuche);
        assertThat(hilfe.dokument(MANDANT, id)).containsEntry("status", status);
    }

    @Dann("ist der Auftrag «VERARBEITEN» «FEHLER» nach {int} Versuchen")
    public void auftragFehler(int versuche) throws Exception {
        var zeile = auftraege.zeile(hilfe.auftraege("VERARBEITEN", id).getFirst());
        assertThat(zeile.status()).isEqualTo("FEHLER");
        assertThat(zeile.versuche()).isEqualTo(versuche);
        assertThat(zeile.nachbearbeitet()).isTrue();
    }

    @Dann("ist das Dokument «FEHLER» mit einem Fehlertext, der die Fehler-ID des Auftrags nennt")
    public void dokumentFehler() throws Exception {
        var zeile = auftraege.zeile(hilfe.auftraege("VERARBEITEN", id).getFirst());
        Map<String, Object> dokument = hilfe.dokument(MANDANT, id);
        assertThat(dokument).containsEntry("status", "FEHLER").containsEntry("s3_pdfa", null);
        assertThat((String) dokument.get("fehler")).contains("nach 3 Versuchen").contains(zeile.fehlerId())
                .doesNotContain("Prüfsumme").doesNotContain("eingang/");
    }

    @Dann("ist das Dokument «EINGANGSKORB» ohne PDF\\/A mit Vermerk «pdfa_fehlt» und Fehlertext «{}»")
    public void pdfaFehlt(String text) throws Exception {
        Map<String, Object> dokument = hilfe.dokument(MANDANT, id);
        assertThat(dokument).containsEntry("status", "EINGANGSKORB").containsEntry("pdfa_fehlt", true)
                .containsEntry("s3_pdfa", null).containsEntry("s3_version_pdfa", null)
                .containsEntry("sha256_pdfa", null);
        assertThat((String) dokument.get("fehler")).contains(text);
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id))).isEmpty();
    }

    private void anlegen(String quelle, List<Quelle> dateien) throws Exception {
        id = hilfe.anlegen(MANDANT, quelle, dateien);
        hilfe.einreihen(MANDANT, id);
        dateienVorher = hilfe.dateien(MANDANT, id);
        inhalteVorher.clear();
        for (String[] datei : dateienVorher.values()) {
            inhalteVorher.put(datei[1], datei[0]);
        }
        eingangVorher = hilfe.versionen(MANDANT, VerarbeitungTestHilfe.eingangOrdner(id));
    }

    /** Eindeutige Zeile, damit gleiche Testbilder keine Dubletten werden (Req 3.4). */
    private static String kennung() {
        return "Lauf " + HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4));
    }
}
