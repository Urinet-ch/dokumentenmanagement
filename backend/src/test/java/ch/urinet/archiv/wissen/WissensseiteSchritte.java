package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class WissensseiteSchritte {

    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID QUELLSEITE = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final Seitentext PDF = new Seitentext(List.of("Erste Seite", "Wörtlicher Auszug"));

    private Wissensseite seite;
    private WissensPruefung.Kontext kontext = WissensPruefung.Kontext.leer();
    private WissensPruefung.Ergebnis ergebnis;

    @Angenommen("eine neue Konzeptseite mit gültigem Pfad")
    public void gueltigeKonzeptseite() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT, "", List.of(), null);
    }

    @Angenommen("eine neue Seite mit allen erforderlichen Seitenfeldern")
    public void allePflichtfelder() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT, "", List.of(), null);
    }

    @Angenommen("eine neue Konzeptseite im Ordner «akteure»")
    public void konzeptseiteImFalschenOrdner() {
        seite = seite("akteure/aufbewahrung", Seitentyp.KONZEPT, "", List.of(), null);
    }

    @Angenommen("eine neue Seite ohne Beschreibung")
    public void ohneBeschreibung() {
        Wissensseite basis = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT, "", List.of(), null);
        seite = new Wissensseite(null, basis.pfad(), basis.typ(), basis.titel(), " ", basis.tags(), basis.status(),
                null, basis.quellen(), basis.vertraulichkeit(), basis.inhalt(), 1, basis.erzeugtVon(), null,
                basis.freigegebenVon(), null, false, false, null);
    }

    @Angenommen("eine Seite mit einer Fussnote ohne Quelleneintrag")
    public void fussnoteOhneQuelle() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT, "Aussage.[^q1] Weitere.[^q-fehlt]",
                quelleneintrag(), null);
        kontext = kontextMitDokument(false);
    }

    @Angenommen("eine Wissensquellen-Seite mit passendem Monatsordner")
    public void quellenSeite() {
        seite = seite("quellen/2026-03-handbuch", Seitentyp.QUELLE,
                "## Zusammenfassung\n## Kernaussagen\n## Berührte Seiten", quelleneintrag(), DOKUMENT);
        kontext = kontextMitDokument(false);
    }

    @Angenommen("eine neue Seite mit einem reservierten Namen")
    public void reservierterName() {
        seite = seite("konzepte/index", Seitentyp.KONZEPT, "", List.of(), null);
    }

    @Angenommen("eine Seite mit einer PDF-Quelle auf Seite 2 von 2")
    public void pdfQuelleMitSeitenangabe() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT,
                "Aussage.[^q1]",
                List.of(new Quelleneintrag("q1", DOKUMENT, 2, 2, "Wörtlicher Auszug", null)), null);
        kontext = kontextMitDokument(false);
    }

    @Angenommen("eine Seite mit einem unbelegten Absatz")
    public void unbelegterAbsatz() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT, "Eine unbelegte Aussage.", List.of(), null);
    }

    @Angenommen("eine Seite mit einer PERSONAL-Dokumentquelle")
    public void personalQuelle() {
        seite = seite("quellen/2026-03-handbuch", Seitentyp.QUELLE,
                "## Zusammenfassung\nKurz.[^q1]\n## Kernaussagen\nFakt.[^q1]\n"
                        + "## Berührte Seiten\n- Konzept",
                quelleneintrag(), DOKUMENT);
        kontext = kontextMitDokument(true);
    }

    @Angenommen("eine Quellen-Seite ohne Pflichtabschnitte")
    public void fehlendeAbschnitte() {
        seite = seite("quellen/2026-03-handbuch", Seitentyp.QUELLE, "", quelleneintrag(), DOKUMENT);
        kontext = kontextMitDokument(false);
    }

    @Angenommen("Markdown mit einem internen Seitenlink")
    public void internerSeitenlink() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT,
                "Siehe [Quelle](/quellen/2026-03-handbuch).[^q1]",
                quelleneintrag(), null);
        kontext = kontextMitDokument(false);
    }

    @Angenommen("Markdown mit externem Link, Bild und HTML")
    public void unzulaessigesMarkdown() {
        seite = seite("konzepte/aufbewahrung", Seitentyp.KONZEPT,
                "[extern](https://example.invalid) ![Bild](https://example.invalid/bild.png) "
                        + "<script>alert(1)</script> [[Wikiseite]]", List.of(), null);
    }

    @Wenn("das Seitenmodell geprüft wird")
    public void pruefen() {
        ergebnis = WissensPruefung.pruefen(seite, kontext);
    }

    @Dann("ist die Seite annehmbar")
    public void annehmbar() {
        assertThat(ergebnis.befunde()).as("Befunde der Seitenprüfung").isEmpty();
    }

    @Dann("enthält die Prüfung den Befund «{word}»")
    public void befundVorhanden(String code) {
        assertThat(ergebnis.befunde()).extracting(WissensPruefung.Befund::code)
                .contains(WissensPruefung.Code.valueOf(code));
    }

    @Dann("enthält die Prüfung den Hinweis «{word}»")
    public void hinweisVorhanden(String code) {
        assertThat(ergebnis.hinweise()).extracting(WissensPruefung.Hinweis::code)
                .contains(WissensPruefung.Hinweiscode.valueOf(code));
    }

    @Dann("wird der Seitenlink «{word}» extrahiert")
    public void linkExtrahiert(String pfad) {
        assertThat(ergebnis.links()).contains(pfad);
    }

    @Dann("wird HTML sicher dargestellt")
    public void htmlSicher() {
        String html = WissensMarkdown.darstellen(seite.inhalt());
        assertThat(html).doesNotContain("<script>", "href=\"https://", "src=\"https://");
    }

    private static List<Quelleneintrag> quelleneintrag() {
        return List.of(new Quelleneintrag("q1", DOKUMENT, 1, 2, null, null));
    }

    private static Wissensseite seite(String pfad, Seitentyp typ, String inhalt, List<Quelleneintrag> quellen,
                                      UUID wissensquelleId) {
        return new Wissensseite(null, pfad, typ, "Titel", "Beschreibung", List.of("synthetisch"),
                Wissensseite.Status.AKTIV, null, quellen, Wissensseite.Vertraulichkeit.NORMAL, inhalt, 1,
                "human:test", null, "human:test", null, false, false, wissensquelleId);
    }

    private static WissensPruefung.Kontext kontextMitDokument(boolean personal) {
        WissensPruefung.DokumentQuelle quelle = new WissensPruefung.DokumentQuelle(true, true, personal, 2,
                LocalDate.of(2026, 3, 5), Optional.of(PDF));
        return new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(DOKUMENT, quelle), Set.of(QUELLSEITE),
                Set.of(), Set.of(), Set.of(), false, false, false, null, null);
    }
}
