package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prompt-Hash: deckt gerenderten System-Prompt, Vorlagen, Schema, ArtCode-Reihenfolge und Bildweg ab. */
class PromptHashTest {

    static final String SYSTEM = Prompt.system("Muster Mandant", "- QUITTUNG: Quittung / Spesen, INTERN\n");
    static final List<String> CODES = Prompt.artCodes();

    static String digest(String system, String benutzer, String nachfrage, String k1, String schema, List<String> codes,
                         String weg) {
        String h = Prompt.hash(system, benutzer, nachfrage, k1, schema, codes, weg);
        return h.substring(h.indexOf('-') + 1);
    }

    static String basis() {
        return digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, Prompt.schema(), CODES, "ohne");
    }

    @Test
    void gleicheEingabenGleicherHash() {
        assertThat(basis()).hasSize(12).isEqualTo(basis());
        assertThat(Prompt.hash(SYSTEM, "ohne")).startsWith(MetadatenExtraktor.PROMPT_VERSION + "-").endsWith(basis());
    }

    @Test
    void jederTeilAendertDenHash() {
        String schema = Prompt.schema();
        List<String> umgestellt = new ArrayList<>(CODES);
        umgestellt.add(umgestellt.removeFirst());
        List<String> andere = List.of(
                digest(Prompt.system("Muster Mandant", "- QUITTUNG: Quittung / Spesen, INTERN (stellt der Mandant selbst aus)\n"),
                        Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "ohne"),
                digest(Prompt.system("Muster Mandant", "- BELEG: Quittung / Spesen, INTERN\n"),
                        Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "ohne"),
                digest(Prompt.system("Andere Firma", "- QUITTUNG: Quittung / Spesen, INTERN\n"),
                        Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER + " ", Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE + " ", Prompt.BENUTZER_K1, schema, CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1 + " ", schema, CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1,
                        schema.replace("Belegdatum als ISO-Datum", "Datum"), CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema + "absender", CODES, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, umgestellt, "ohne"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "fotos"),
                digest(SYSTEM, Prompt.BENUTZER, Prompt.NACHFRAGE, Prompt.BENUTZER_K1, schema, CODES, "alle"));
        assertThat(andere).doesNotContain(basis()).doesNotHaveDuplicates();
    }

    @Test
    void schemaEnthaeltBeschreibungenUndEnumReihenfolge() {
        String schema = Prompt.schema();
        assertThat(schema).contains("Belegdatum als ISO-Datum");
        assertThat(schema.indexOf(CODES.getFirst())).isLessThan(schema.indexOf(CODES.getLast()));
    }

    @Test
    void gerenderterPromptOhnePlatzhalter() {
        assertThat(SYSTEM).contains("Muster Mandant").contains("QUITTUNG").doesNotContain("{");
    }
}
