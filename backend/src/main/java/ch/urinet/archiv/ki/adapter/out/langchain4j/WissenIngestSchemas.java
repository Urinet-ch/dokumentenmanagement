package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/** Feste JSON-Schemas der vier KI-Teilaufträge für die Wissensbasis. */
public final class WissenIngestSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WissenIngestSchemas() {
    }

    public static Class<?> antworttyp(KiPort.WissensSchritt schritt) {
        return switch (schritt) {
            case KERNAUSSAGEN -> Kernaussagen.class;
            case PLANUNG -> Planung.class;
            case SEITE -> Seite.class;
            case QUELLE -> Quelle.class;
            case LINT_PAAR -> LintPaar.class;
        };
    }

    static KiPort.WissensInhalt lesen(String json, KiPort.WissensSchritt schritt) {
        try {
            return switch (schritt) {
                case KERNAUSSAGEN -> {
                    Kernaussagen antwort = MAPPER.readValue(json, Kernaussagen.class);
                    yield new KiPort.Kernaussagen(antwort.aussagen().stream()
                            .map(a -> new KiPort.Aussage(a.nr(), a.text(), a.seiten(), a.auszug())).toList());
                }
                case PLANUNG -> {
                    Planung antwort = MAPPER.readValue(json, Planung.class);
                    yield new KiPort.Planung(antwort.seiten().stream()
                            .map(s -> new KiPort.Plan(s.pfad(), s.aktion(), s.typ(), s.grund(), s.aussagen()))
                            .toList());
                }
                case SEITE -> {
                    Seite antwort = MAPPER.readValue(json, Seite.class);
                    yield new KiPort.Seite(antwort.titel(), antwort.beschreibung(), antwort.tags(), antwort.inhalt(),
                            antwort.widersprueche().stream()
                                    .map(w -> new KiPort.Widerspruch(w.bestehend(), w.neu(), w.text())).toList(),
                            antwort.entfernte_aussagen().stream()
                                    .map(a -> new KiPort.EntfernteAussage(a.schluessel(), a.begruendung())).toList(),
                            antwort.begruendung());
                }
                case QUELLE -> {
                    Quelle antwort = MAPPER.readValue(json, Quelle.class);
                    yield new KiPort.Quelle(antwort.titel(), antwort.beschreibung(), antwort.tags(),
                            antwort.zusammenfassung().stream()
                                    .map(s -> new KiPort.Zusammenfassung(s.text(), s.aussagen())).toList());
                }
                case LINT_PAAR -> {
                    LintPaar antwort = MAPPER.readValue(json, LintPaar.class);
                    yield new KiPort.LintPaar(antwort.widersprueche().stream()
                            .map(w -> new KiPort.LintWiderspruch(w.seite(), w.schluessel(), w.gegenseite(),
                                    w.gegenschluessel(), w.text())).toList(),
                            antwort.veraltet().stream().map(v -> new KiPort.LintVeraltet(v.seite(), v.schluessel(),
                                    v.neuere_quelle(), v.text())).toList(),
                            antwort.links().stream().map(l -> new KiPort.LintLink(l.seite(), l.ziel(), l.text()))
                                    .toList());
                }
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensantwort nicht lesbar", e);
        }
    }

    public record Kernaussagen(@Description("Belegte Aussagen mit Seiten und wörtlichen Auszügen")
                               List<Aussage> aussagen) {
    }

    public record Aussage(Integer nr, String text, List<Integer> seiten, String auszug) {
    }

    public record Planung(@Description("Geplante Änderungen an Wissensseiten")
                          List<Plan> seiten) {
    }

    public record Plan(String pfad, String aktion, String typ, String grund, List<Integer> aussagen) {
    }

    public record Seite(String titel, String beschreibung, List<String> tags, String inhalt,
                        List<Widerspruch> widersprueche, List<EntfernteAussage> entfernte_aussagen,
                        String begruendung) {
    }

    public record Widerspruch(String bestehend, String neu, String text) {
    }

    public record EntfernteAussage(String schluessel, String begruendung) {
    }

    public record Quelle(String titel, String beschreibung, List<String> tags,
                         List<Zusammenfassung> zusammenfassung) {
    }

    public record Zusammenfassung(String text, List<Integer> aussagen) {
    }

    public record LintPaar(List<LintWiderspruch> widersprueche, List<LintVeraltet> veraltet, List<LintLink> links) {
    }

    public record LintWiderspruch(String seite, String schluessel, String gegenseite, String gegenschluessel,
                                  String text) {
    }

    public record LintVeraltet(String seite, String schluessel, String neuere_quelle, String text) {
    }

    public record LintLink(String seite, String ziel, String text) {
    }
}
