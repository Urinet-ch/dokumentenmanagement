package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WissensMarkdownTest {
    @Test
    void widerspruchsvergleichErkenntGeaenderteUndMehrfacheBloeckeAberKeinenCode() {
        String block = "> ⚠️ Widerspruch: Alt.[^alt] Neu.[^neu]";
        assertThat(WissensMarkdown.entfernteWidersprueche(block, block.replace("Alt.", "Anders."))).hasSize(1);
        assertThat(WissensMarkdown.entfernteWidersprueche(block + "\n\n" + block, block)).hasSize(1);
        assertThat(WissensMarkdown.entfernteWidersprueche(block, block.replace(" ", "  "))).hasSize(1);
        assertThat(WissensMarkdown.entfernteWidersprueche(block + "\r\n", block + "\n")).isEmpty();
        assertThat(WissensMarkdown.analysieren("```\n" + block + "\n```",
                WissensMarkdown.MAX_EINGABE_ZEICHEN).widersprueche()).isEmpty();
    }


    @Test
    void okfSchreibtNurEchteLinkzieleUmUndLaesstFussnotenUndCodeUnveraendert() {
        String markdown = "[**Regel**](/konzepte/arbeit \"Titel\") und [Übersicht](</ueberblick>)." +
                "[^q-1]\n\n`[Code](/konzepte/arbeit)`\n\n```\n[Code](/ueberblick)\n```\n";

        assertThat(WissensMarkdown.fuerOkf(markdown)).isEqualTo(
                "[**Regel**](/konzepte/arbeit.md \"Titel\") und [Übersicht](</ueberblick.md>)." +
                        "[^q-1]\n\n`[Code](/konzepte/arbeit)`\n\n```\n[Code](/ueberblick)\n```\n");
    }

    @Test
    void okfErhaeltMehrzeiligeLinksUndMaskierteZeichen() {
        String markdown = "[Regel](\n  /konzepte/arbeits\\-zeit \"Titel\")\r\n"
                + "[Zweite](/konzepte/zweite)\r\n";

        assertThat(WissensMarkdown.fuerOkf(markdown)).isEqualTo(
                "[Regel](\n  /konzepte/arbeits\\-zeit.md \"Titel\")\r\n"
                        + "[Zweite](/konzepte/zweite.md)\r\n");
    }

    @Test
    void okfLehntNichtZulaessigeLinksAb() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> WissensMarkdown.fuerOkf("[extern](https://example.invalid)"));
    }

    @Test
    void fussnotenUndInterneLinksWerdenGemeinsamGeparst() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Aussage.[^q1]\n\n[Seite](/konzepte/arbeit)", 20_000);

        assertThat(analyse.fussnoten()).containsExactly("q1");
        assertThat(analyse.aussagen()).hasSize(2);
        assertThat(analyse.aussagen().get(0).fussnoten()).containsExactly("q1");
        assertThat(analyse.links()).containsExactly("konzepte/arbeit");
        assertThat(analyse.html()).contains("href=\"/wissen/konzepte/arbeit\"");
    }

    @Test
    void externeLinksBilderWikilinksUndHtmlWerdenErkanntUndSicherGerendert() {
        String markdown = "[extern](https://example.invalid) ![Bild](https://example.invalid/bild.png) "
                + "<script>alert(1)</script> [[Wikiseite]]";
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(markdown, 20_000);

        assertThat(analyse.ungueltigerLink()).isTrue();
        assertThat(analyse.bildVorhanden()).isTrue();
        assertThat(analyse.htmlVorhanden()).isTrue();
        assertThat(analyse.wikilink()).isTrue();
        assertThat(analyse.html()).doesNotContain("href=\"https://", "src=\"https://", "<script>");
        assertThat(analyse.html()).contains("&lt;script&gt;");
    }

    @Test
    void codebeispieleMitHtmlUndWikilinksSindKeinAktiverInhalt() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "```\n<script>[[nur-code]]</script>\n```", 20_000);

        assertThat(analyse.htmlVorhanden()).isFalse();
        assertThat(analyse.wikilink()).isFalse();
        assertThat(analyse.html()).doesNotContain("<script>");
    }

    @Test
    void htmlInFussnotenUndUnbekannteLinkpfadeBleibenUnbedenklich() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Aussage.[^q1]\n[Seite](/konzepte/nicht-vorhanden)\n\n[^q1]: <img src=x onerror=alert(1)>",
                20_000);

        assertThat(analyse.htmlVorhanden()).isTrue();
        assertThat(analyse.fussnotenDefinition()).isTrue();
        assertThat(analyse.ungueltigerLink()).isFalse();
        assertThat(analyse.html()).doesNotContain("<img", "onerror");
        assertThat(analyse.links()).containsExactly("konzepte/nicht-vorhanden");
    }

    @Test
    void fussnotenImSpecFormatWerdenOhneDefinitionErkannt() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Die Frist ist am 31. März.[^q-1]\n\n- Punkt.[^q-2]", 20_000);

        assertThat(analyse.fussnoten()).containsExactlyInAnyOrder("q-1", "q-2");
        assertThat(analyse.aussagen()).extracting(WissensMarkdown.Aussage::fussnoten)
                .containsExactly(java.util.Set.of("q-1"), java.util.Set.of("q-2"));
        assertThat(analyse.fussnotenDefinition()).isFalse();
        assertThat(analyse.html()).contains("footnote-ref").doesNotContain("[^q-1]");
    }

    @Test
    void eigeneFussnotenDefinitionWirdGemeldetUndNichtDargestellt() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Aussage.[^q1]\n\n[^q1]: Unbelegte Zusatzbehauptung", 20_000);

        assertThat(analyse.fussnotenDefinition()).isTrue();
        assertThat(analyse.fussnoten()).containsExactly("q1");
        assertThat(analyse.html()).doesNotContain("Zusatzbehauptung");
    }

    @Test
    void eigeneFussnotenDefinitionMitWagenruecklaufWirdNichtDargestellt() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Aussage.[^q1]\r\r[^q1]: Unbelegte Zusatzbehauptung\r\r    Zweiter Absatz", 20_000);

        assertThat(analyse.fussnotenDefinition()).isTrue();
        assertThat(analyse.html()).contains("footnote-ref")
                .doesNotContain("Zusatzbehauptung", "Zweiter Absatz");
    }

    @Test
    void fussnotenInCodeZaehlenNicht() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren("Text `[^q1]`", 20_000);

        assertThat(analyse.fussnoten()).isEmpty();
        assertThat(analyse.aussagen()).singleElement()
                .satisfies(aussage -> assertThat(aussage.fussnoten()).isEmpty());
    }

    @Test
    void codebloeckeSindAussagen() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Fakt.[^q1]\n\n```\nUnbelegte Frist\n```\n\n    Eingerueckt unbelegt", 20_000);

        assertThat(analyse.aussagen()).filteredOn(aussage -> aussage.fussnoten().isEmpty())
                .extracting(WissensMarkdown.Aussage::text)
                .anySatisfy(text -> assertThat(text).contains("Unbelegte Frist"))
                .anySatisfy(text -> assertThat(text).contains("Eingerueckt unbelegt"));
    }

    @Test
    void offeneKlammernZaehlenNurInnerhalbEinesAbsatzes() {
        String absaetze = "Absatz (siehe.[^q1]\n\n".repeat(WissensMarkdown.MAX_VERSCHACHTELUNG + 6);
        String einAbsatz = "(".repeat(WissensMarkdown.MAX_VERSCHACHTELUNG + 1) + "Text";

        assertThat(WissensMarkdown.analysieren(absaetze, 20_000).zuTief()).isFalse();
        assertThat(WissensMarkdown.analysieren(absaetze.replace('\n', '\r'), 20_000).zuTief()).isFalse();
        assertThat(WissensMarkdown.analysieren(einAbsatz, 20_000).zuTief()).isTrue();
    }

    @Test
    void nichtMoeglicheSeitenpfadeWerdenNichtAlsLinkAusgegeben() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "[Root](/nicht-erlaubt) [Tiefe](/konzepte/a/b) [Reserviert](/konzepte/index) "
                        + "[Gueltig](/ueberblick)", 20_000);

        assertThat(analyse.ungueltigerLink()).isTrue();
        assertThat(analyse.links()).containsExactly("ueberblick");
        assertThat(analyse.html()).doesNotContain("href=\"/wissen/nicht-erlaubt\"",
                "href=\"/wissen/konzepte/a/b\"");
        assertThat(analyse.html()).contains("href=\"/wissen/ueberblick\"");
    }

    @Test
    void linksUndUnzulaessigeSyntaxInUeberschriftenWerdenGeprueft() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "# [Extern](https://example.invalid) [[Wikiseite]]", 20_000);

        assertThat(analyse.ungueltigerLink()).isTrue();
        assertThat(analyse.wikilink()).isTrue();
        assertThat(analyse.links()).isEmpty();
    }

    @Test
    void verschachtelterListenpunktBelegtNichtDenUebergeordnetenListenpunkt() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "- unbelegt\n  - belegt.[^q1]", 20_000);

        assertThat(analyse.aussagen()).anySatisfy(aussage -> {
            assertThat(aussage.text()).contains("unbelegt");
            assertThat(aussage.fussnoten()).isEmpty();
        });
    }

    @Test
    void groesseUndVerschachtelungSindBegrenzt() {
        WissensMarkdown.Analyse zuGross = WissensMarkdown.analysieren("123456", 5);
        WissensMarkdown.Analyse zuTief = WissensMarkdown.analysieren(
                "> ".repeat(WissensMarkdown.MAX_VERSCHACHTELUNG + 1) + "Text", 20_000);

        assertThat(zuGross.zuGross()).isTrue();
        assertThat(zuTief.zuTief()).isTrue();
        assertThat(WissensMarkdown.analysieren("    Code mit [Klammer]", 20_000).zuTief()).isFalse();
    }

    @Test
    void geteilterSchluesselBleibtBeiAussageAusserhalbDesBlocksSichtbar() {
        var analyse = WissensMarkdown.analysieren(
                "> ⚠️ Widerspruch: A.[^a] B.[^b]\n\nWichtige Aussage.[^a]", 20_000);
        assertThat(analyse.widersprueche()).singleElement()
                .satisfies(b -> assertThat(b.fussnoten()).containsExactlyInAnyOrder("a", "b"));
        assertThat(analyse.aussagen()).extracting(a -> a.fussnoten()).containsExactly(java.util.Set.of("a"));
    }
}
