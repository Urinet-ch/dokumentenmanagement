package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WissensMarkdownTest {

    @Test
    void fussnotenUndInterneLinksWerdenGemeinsamGeparst() {
        WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(
                "Aussage.[^q1]\n\n[Seite](/konzepte/arbeit)\n\n[^q1]: Quelle", 20_000);

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
        assertThat(analyse.ungueltigerLink()).isFalse();
        assertThat(analyse.html()).doesNotContain("<img").contains("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(analyse.links()).containsExactly("konzepte/nicht-vorhanden");
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
                "- unbelegt\n  - belegt.[^q1]\n\n[^q1]: Quelle", 20_000);

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
}
