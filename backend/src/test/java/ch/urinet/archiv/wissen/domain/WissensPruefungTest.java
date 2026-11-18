package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissensPruefungTest {

    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID QUELLSEITE = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final LocalDate DOKUMENTDATUM = LocalDate.of(2026, 3, 5);
    private static final Seitentext PDF = new Seitentext(List.of("Erste Seite", "Wörtlicher Auszug"));

    @Test
    void gueltigeDokumentquelleMitSeitenbereichUndAuszugIstAnnehmbar() {
        Wissensseite seite = quellenSeite("## Zusammenfassung\nKurz.[^q1]\n"
                + "## Kernaussagen\nFakt.[^q1]\n## Berührte Seiten\n- Konzept\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 1, 2, "Wörtlicher Auszug", null)));

        assertThat(pruefen(seite, kontextMitDokument(false)).befunde()).isEmpty();
    }

    @Test
    void pathUndPflichtfelderWerdenGeprueft() {
        Wissensseite reservierterPfad = seite("konzepte/index", Seitentyp.KONZEPT, "", List.of());
        Wissensseite falscherOrdner = seite("akteure/beratung", Seitentyp.KONZEPT, "", List.of());
        Wissensseite doppelt = seite("konzepte/beratung", Seitentyp.KONZEPT, "", List.of());
        WissensPruefung.Kontext belegt = new WissensPruefung.Kontext(20_000, null,
                Set.of("konzepte/beratung"), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                false, false, false, null);
        Wissensseite felder = new Wissensseite(null, "konzepte/beratung", Seitentyp.KONZEPT, " ",
                "x".repeat(WissensPruefung.BESCHREIBUNG_MAX_ZEICHEN + 1), List.of("Ungueltig_tag"),
                Wissensseite.Status.AKTIV, null, List.of(), Wissensseite.Vertraulichkeit.NORMAL,
                "", 1, null, null, null, null, false, false, null);
        Wissensseite zweiSaetze = new Wissensseite(null, "konzepte/beratung", Seitentyp.KONZEPT, "Titel",
                "Erster Satz. Zweiter Satz.", List.of(), Wissensseite.Status.AKTIV, null, List.of(),
                Wissensseite.Vertraulichkeit.NORMAL, "", 1, null, null, null, null, false, false, null);

        assertThat(codes(pruefen(reservierterPfad, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.PFAD_UNGUELTIG);
        assertThat(codes(pruefen(falscherOrdner, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.PFAD_UNGUELTIG);
        assertThat(codes(pruefen(doppelt, belegt))).contains(WissensPruefung.Code.PFAD_UNGUELTIG);
        assertThat(codes(pruefen(felder, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.FELD_FEHLT, WissensPruefung.Code.SCHLAGWORT_UNGUELTIG);
        assertThat(codes(pruefen(zweiSaetze, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.FELD_FEHLT);
    }

    @Test
    void seitenpfadEinerQuelleMussZumDokumentmonatPassen() {
        Wissensseite falscherMonat = new Wissensseite(null, "quellen/2026-04-handbuch", Seitentyp.QUELLE,
                "Titel", "Beschreibung", List.of(), Wissensseite.Status.AKTIV, null, List.of(),
                Wissensseite.Vertraulichkeit.NORMAL, "", 1, null, null, null, null, false, false, DOKUMENT);

        assertThat(codes(pruefen(falscherMonat, kontextMitDokument(false))))
                .contains(WissensPruefung.Code.PFAD_UNGUELTIG);
    }

    @Test
    void ungueltigeUndPersonelleQuellenSowieFehlendeSeitenWerdenErkannt() {
        Wissensseite personal = quellenSeite("## Zusammenfassung\nKurz.[^q1]\n"
                + "## Kernaussagen\nFakt.[^q1]\n## Berührte Seiten\n- Konzept\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 1, null, null, null)));
        Wissensseite ausserhalb = quellenSeite("Fakt.[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 3, null, null, null)));
        Wissensseite ohneSeite = quellenSeite("Fakt.[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, null, null, null, null)));

        assertThat(codes(pruefen(personal, kontextMitDokument(true))))
                .contains(WissensPruefung.Code.QUELLE_PERSONAL);
        assertThat(codes(pruefen(ausserhalb, kontextMitDokument(false))))
                .contains(WissensPruefung.Code.QUELLE_UNGUELTIG);
        assertThat(codes(pruefen(ohneSeite, kontextMitDokument(false))))
                .contains(WissensPruefung.Code.SEITE_FEHLT);
    }

    @Test
    void wissensseitenQuelleDarfKeinePdfAngabenEnthalten() {
        Wissensseite seite = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "Aussage.[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", null, 1, null, null, QUELLSEITE)));

        assertThat(codes(pruefen(seite, kontextMitQuellseite())))
                .contains(WissensPruefung.Code.QUELLE_UNGUELTIG);
    }

    @Test
    void nichtArchiviertesPersonalDokumentWirdAuchAlsPersonalQuelleGemeldet() {
        Wissensseite seite = quellenSeite("Aussage.[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 1, null, null, null)));
        WissensPruefung.DokumentQuelle quelle = new WissensPruefung.DokumentQuelle(true, false,
                true, 2, DOKUMENTDATUM, Optional.of(PDF));

        assertThat(codes(pruefen(seite, kontext(quelle))))
                .contains(WissensPruefung.Code.QUELLE_UNGUELTIG, WissensPruefung.Code.QUELLE_PERSONAL);
    }

    @Test
    void fehlenderAuszugUndNichtArchiviertesDokumentWerdenAbgelehnt() {
        Wissensseite auszug = quellenSeite("Aussage.[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 1, null, "Dieser Text fehlt", null)));
        WissensPruefung.DokumentQuelle nichtArchiviert = new WissensPruefung.DokumentQuelle(true, false,
                false, 2, DOKUMENTDATUM, Optional.of(PDF));
        WissensPruefung.Kontext nichtArchiviertKontext = kontext(nichtArchiviert);

        assertThat(codes(pruefen(auszug, kontextMitDokument(false))))
                .contains(WissensPruefung.Code.AUSZUG_NICHT_GEFUNDEN);
        assertThat(codes(pruefen(auszug, nichtArchiviertKontext)))
                .contains(WissensPruefung.Code.QUELLE_UNGUELTIG);
    }

    @Test
    void aussagenBrauchenQuelleneintragUndFussnote() {
        Wissensseite ohneFussnote = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "Eine belegpflichtige Aussage.", List.of());
        Wissensseite fehlendeQuelle = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "Eine Aussage.[^fehlend]\n\n[^fehlend]: Quelle", List.of());

        assertThat(codes(pruefen(ohneFussnote, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.AUSSAGE_OHNE_BELEG);
        assertThat(codes(pruefen(fehlendeQuelle, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.FUSSNOTE_OHNE_QUELLE);
    }

    @Test
    void linksWerdenFuerDieLinktabelleExtrahiertUndUnzulaessigeSyntaxAbgelehnt() {
        Wissensseite intern = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "Siehe [Seite](/quellen/2026-03-handbuch).[^q1]\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", null, null, null, null, QUELLSEITE)));
        WissensPruefung.Kontext mitSeite = kontextMitQuellseite();
        Wissensseite extern = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "[extern](https://example.invalid) ![Bild](https://example.invalid/bild.png) "
                        + "<script>falsch</script> [[Wikiseite]]", List.of());
        WissensPruefung.Ergebnis internePruefung = pruefen(intern, mitSeite);

        assertThat(internePruefung.befunde()).isEmpty();
        assertThat(internePruefung.links()).containsExactly("quellen/2026-03-handbuch");
        assertThat(codes(pruefen(extern, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.LINK_UNGUELTIG);
        assertThat(WissensMarkdown.darstellen(extern.inhalt())).doesNotContain("<script>");
    }

    @Test
    void seitenlaengeVerschachtelungUndAbschnittshinweiseWerdenGeprueft() {
        Wissensseite zuGross = seite("konzepte/arbeit", Seitentyp.KONZEPT, "123456", List.of());
        Wissensseite zuTief = seite("konzepte/arbeit", Seitentyp.KONZEPT,
                "> ".repeat(WissensMarkdown.MAX_VERSCHACHTELUNG + 1) + "Text", List.of());
        Wissensseite ohneAbschnitte = seite("ueberblick", Seitentyp.SYNTHESE, "", List.of());
        WissensPruefung.Kontext kurzeSeite = new WissensPruefung.Kontext(5, null, Set.of(), Map.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), false, false, false, null);

        assertThat(codes(pruefen(zuGross, kurzeSeite))).contains(WissensPruefung.Code.ZU_GROSS);
        assertThat(codes(pruefen(zuTief, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.MARKDOWN_ZU_TIEF);
        assertThat(pruefen(ohneAbschnitte, WissensPruefung.Kontext.leer()).hinweise())
                .extracting(WissensPruefung.Hinweis::code)
                .contains(WissensPruefung.Hinweiscode.ABSCHNITT_FEHLT);
    }

    @Test
    void auszugssucheNormalisiertLeerraumUndSilbentrennung() {
        Wissensseite seite = quellenSeite("## Zusammenfassung\nKurz.[^q1]\n"
                        + "## Kernaussagen\nFakt.[^q1]\n## Berührte Seiten\n- Konzept\n\n[^q1]: Quelle",
                List.of(new Quelleneintrag("q1", DOKUMENT, 2, 2, "Wortgetreuer Quellenauszug", null)));
        WissensPruefung.Kontext kontext = kontextMitDokument(false, new Seitentext(
                List.of("Erste Seite", "Wortgetreuer Quellen-\nauszug")));

        assertThat(codes(pruefen(seite, kontext))).doesNotContain(WissensPruefung.Code.AUSZUG_NICHT_GEFUNDEN);
    }

    @Test
    void ingestEntfernteAussagenUndVertraulichkeitWerdenGeprueft() {
        Wissensseite seite = seite("ueberblick", Seitentyp.SYNTHESE,
                "Aussage.[^neu]\n\n[^neu]: Quelle", List.of(
                        new Quelleneintrag("neu", null, null, null, null, QUELLSEITE)));
        WissensPruefung.Kontext kontext = new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(),
                Set.of(QUELLSEITE), Set.of("alt"), Set.of(), Set.of("kernaussage"), true, false,
                false, Wissensseite.Vertraulichkeit.NORMAL);
        Wissensseite intern = new Wissensseite(seite.id(), seite.pfad(), seite.typ(), seite.titel(),
                seite.beschreibung(), seite.tags(), seite.status(), seite.veraltetAb(), seite.quellen(),
                Wissensseite.Vertraulichkeit.INTERN, seite.inhalt(), seite.version(), seite.erzeugtVon(),
                seite.erzeugtAm(), seite.freigegebenVon(), seite.freigegebenAm(), seite.quellePruefen(),
                seite.widerspruchOffen(), seite.wissensquelleId());
        WissensPruefung.Ergebnis ergebnis = pruefen(seite, kontext);
        WissensPruefung.Kontext stufenwechsel = new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), false, false, false,
                Wissensseite.Vertraulichkeit.NORMAL);

        assertThat(codes(ergebnis)).contains(WissensPruefung.Code.QUELLE_NICHT_BELEGT,
                WissensPruefung.Code.AUSSAGE_ENTFERNT);
        assertThat(pruefen(intern, stufenwechsel).hinweise()).extracting(WissensPruefung.Hinweis::code)
                .contains(WissensPruefung.Hinweiscode.STUFE_STEIGT);
        assertThat(codes(pruefen(intern, WissensPruefung.Kontext.leer())))
                .contains(WissensPruefung.Code.STUFE_UEBERBLICK);
    }

    @Test
    void pfadVonBestehenderSeiteBleibtUnveraendertUndHoehereStufeWirdAngezeigt() {
        Wissensseite bisher = new Wissensseite(QUELLSEITE, "konzepte/alt", Seitentyp.KONZEPT,
                "Titel", "Beschreibung", List.of(), Wissensseite.Status.AKTIV, null, List.of(),
                Wissensseite.Vertraulichkeit.NORMAL, "", 1, "human:test", null, "human:test", null,
                false, false, null);
        Wissensseite geaendert = new Wissensseite(QUELLSEITE, "konzepte/neu", Seitentyp.KONZEPT,
                bisher.titel(), bisher.beschreibung(), bisher.tags(), bisher.status(), null, List.of(),
                bisher.vertraulichkeit(), "", 2, "human:test", null, "human:test", null,
                false, false, null);
        WissensPruefung.Kontext kontext = new WissensPruefung.Kontext(20_000, bisher.pfad(),
                Set.of("konzepte/neu"), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                false, false, true, Wissensseite.Vertraulichkeit.NORMAL);

        WissensPruefung.Ergebnis ergebnis = pruefen(geaendert, kontext);

        assertThat(codes(ergebnis)).contains(WissensPruefung.Code.PFAD_UNGUELTIG);
        assertThat(ergebnis.hinweise()).extracting(WissensPruefung.Hinweis::code)
                .contains(WissensPruefung.Hinweiscode.HOEHERE_STUFE_VORHANDEN);
    }

    private static WissensPruefung.Ergebnis pruefen(Wissensseite seite, WissensPruefung.Kontext kontext) {
        return WissensPruefung.pruefen(seite, kontext);
    }

    private static Set<WissensPruefung.Code> codes(WissensPruefung.Ergebnis ergebnis) {
        return ergebnis.befunde().stream().map(WissensPruefung.Befund::code)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Wissensseite seite(String pfad, Seitentyp typ, String inhalt, List<Quelleneintrag> quellen) {
        return new Wissensseite(null, pfad, typ, "Titel", "Kurze Beschreibung", List.of("synthetisch"),
                Wissensseite.Status.AKTIV, null, quellen, Wissensseite.Vertraulichkeit.NORMAL, inhalt, 1,
                "human:test", null, "human:test", null, false, false, null);
    }

    private static Wissensseite quellenSeite(String inhalt, List<Quelleneintrag> quellen) {
        return new Wissensseite(null, "quellen/2026-03-handbuch", Seitentyp.QUELLE, "Handbuch",
                "Kurze Beschreibung", List.of("synthetisch"), Wissensseite.Status.AKTIV, null, quellen,
                Wissensseite.Vertraulichkeit.NORMAL, inhalt, 1, "human:test", null, "human:test", null,
                false, false, DOKUMENT);
    }

    private static WissensPruefung.Kontext kontextMitDokument(boolean personal) {
        return kontextMitDokument(personal, PDF);
    }

    private static WissensPruefung.Kontext kontextMitDokument(boolean personal, Seitentext text) {
        WissensPruefung.DokumentQuelle quelle = new WissensPruefung.DokumentQuelle(true, true, personal, 2,
                DOKUMENTDATUM, Optional.of(text));
        return new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(DOKUMENT, quelle), Set.of(),
                Set.of(), Set.of(), Set.of(), false, false, false, null);
    }

    private static WissensPruefung.Kontext kontextMitQuellseite() {
        return new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(), Set.of(QUELLSEITE),
                Set.of(), Set.of(), Set.of(), false, false, false, null);
    }

    private static WissensPruefung.Kontext kontext(WissensPruefung.DokumentQuelle quelle) {
        return new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(DOKUMENT, quelle), Set.of(),
                Set.of(), Set.of(), Set.of(), false, false, false, null);
    }
}
