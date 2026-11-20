package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class WissensPruefung {

    public static final int BESCHREIBUNG_MAX_ZEICHEN = 200;
    public static final int AUSZUG_MAX_ZEICHEN = 300;
    public static final int SEITE_MAX_ZEICHEN_STARTWERT = 20_000;
    private static final DateTimeFormatter QUELLENMONAT = DateTimeFormatter.ofPattern("uuuu-MM");
    private static final Set<String> ABKUERZUNGEN = Set.of("abs", "abt", "allg", "anm", "apr", "art", "aufl",
            "aug", "ausg", "bd", "bspw", "bzw", "ca", "dez", "dr", "etc", "evtl", "exkl", "feb", "ff", "fr", "gem", "ggf",
            "hr", "hrn", "hrsg", "inkl", "jan", "jh", "jul", "jun", "kap", "lit", "max", "min", "mio", "mrd", "mt",
            "mär", "nov", "nr", "okt", "prof", "resp", "sep", "sept", "sog", "st", "std", "stk", "str", "tel",
            "usw", "vgl", "ziff", "zit", "zzgl");

    private WissensPruefung() {
    }

    public static Ergebnis pruefen(Wissensseite seite, Kontext kontext) {
        if (seite == null || kontext == null) {
            throw new IllegalArgumentException("Seite und Pruefkontext muessen vorhanden sein.");
        }

        List<Befund> befunde = new ArrayList<>();
        List<Hinweis> hinweise = new ArrayList<>();
        if (seite.typ() == null || !textGueltig(seite.titel()) || !beschreibungGueltig(seite.beschreibung())
                || seite.status() == null || seite.vertraulichkeit() == null) {
            befunde.add(new Befund(Code.FELD_FEHLT,
                    "Typ, Titel, Beschreibung, Status oder Vertraulichkeit fehlt oder ist ungültig."));
        }
        for (String tag : seite.tags()) {
            if (!kebab(tag)) {
                befunde.add(new Befund(Code.SCHLAGWORT_UNGUELTIG,
                        "Ein Schlagwort fehlt oder ist nicht kleingeschriebenes kebab-case."));
                break;
            }
        }

        pruefePfad(seite, kontext, befunde, hinweise);

        WissensMarkdown.Analyse markdown = WissensMarkdown.analysieren(seite.inhalt(), kontext.maxZeichen());
        if (markdown.zuGross()) {
            befunde.add(new Befund(Code.ZU_GROSS, "Der Seiteninhalt überschreitet die zulässige Zeichenzahl."));
        }
        if (markdown.zuTief()) {
            befunde.add(new Befund(Code.MARKDOWN_ZU_TIEF, "Das Markdown ist zu stark verschachtelt."));
        }
        if (markdown.htmlVorhanden() || markdown.bildVorhanden() || markdown.ungueltigerLink()
                || markdown.wikilink()) {
            befunde.add(new Befund(Code.LINK_UNGUELTIG,
                    "Externe Links, Bilder, HTML, Wikilinks und Link-Referenzen «[x]: …» sind nicht zulässig; "
                            + "Seiten verlinken nur mit «[Titel](/pfad)»."));
        }
        if (markdown.fussnotenDefinition()) {
            befunde.add(new Befund(Code.FUSSNOTE_DEFINITION,
                    "Fussnoten-Definitionen «[^…]: …» sind im Inhalt nicht zulässig; die Quelle gehört in den "
                            + "Quelleneintrag."));
        }

        pruefeQuellen(seite, kontext, befunde);
        pruefeWissensquelle(seite, kontext, markdown.fussnoten(), befunde);
        for (String fussnote : markdown.fussnoten()) {
            if (!schluessel(seite.quellen()).contains(fussnote)) {
                befunde.add(new Befund(Code.FUSSNOTE_OHNE_QUELLE, "Die Fussnote «" + fussnote
                        + "» hat keinen Quelleneintrag."));
            }
        }
        if (markdown.aussagen().stream().anyMatch(aussage -> aussage.fussnoten().isEmpty())) {
            befunde.add(new Befund(Code.AUSSAGE_OHNE_BELEG,
                    "Mindestens ein Absatz oder Listenpunkt hat keine Fussnote; Codeblöcke sind nur in "
                            + "Widerspruchsblöcken und den Abschnitten «Offene Fragen» und «Berührte Seiten» "
                            + "zulässig."));
        }
        if (kontext.ingest() && !kontext.veralten()
                && Collections.disjoint(markdown.fussnoten(), kontext.kernaussagenSchluessel())) {
            befunde.add(new Befund(Code.QUELLE_NICHT_BELEGT,
                    "Die Änderung aus dem Ingest verweist auf keine Kernaussage der aktuellen Wissensquelle."));
        }
        if (!kontext.basisQuellenSchluessel().isEmpty()) {
            Set<String> entfernte = new TreeSet<>(kontext.basisQuellenSchluessel());
            entfernte.removeAll(markdown.fussnoten());
            entfernte.removeAll(kontext.entfernteAussagen());
            if (!entfernte.isEmpty()) {
                befunde.add(new Befund(Code.AUSSAGE_ENTFERNT,
                            "Aussagen der Basisversion wurden ohne Begründung entfernt."));
            }
        }

        pruefeAbschnitte(seite, markdown, hinweise);
        Wissensseite.Vertraulichkeit wirksam = wirksam(seite, kontext);
        if ("ueberblick".equals(seite.pfad())
                && (kontext.wirksameVertraulichkeit() == null || wirksam != Wissensseite.Vertraulichkeit.NORMAL)) {
            befunde.add(new Befund(Code.STUFE_UEBERBLICK,
                    "Die Seite «ueberblick» muss die wirksame Vertraulichkeit NORMAL behalten."));
        }
        if (kontext.bisherigeVertraulichkeit() != null && wirksam != null
                && rang(wirksam) > rang(kontext.bisherigeVertraulichkeit())) {
            hinweise.add(new Hinweis(Hinweiscode.STUFE_STEIGT,
                    "Die Annahme erhöht die Vertraulichkeit der Seite dauerhaft."));
        }

        return new Ergebnis(befunde, hinweise, markdown.links());
    }

    private static Wissensseite.Vertraulichkeit wirksam(Wissensseite seite, Kontext kontext) {
        if (seite.vertraulichkeit() == null) {
            return kontext.wirksameVertraulichkeit();
        }
        if (kontext.wirksameVertraulichkeit() == null) {
            return seite.vertraulichkeit();
        }
        return rang(seite.vertraulichkeit()) >= rang(kontext.wirksameVertraulichkeit())
                ? seite.vertraulichkeit() : kontext.wirksameVertraulichkeit();
    }

    private static void pruefePfad(Wissensseite seite, Kontext kontext, List<Befund> befunde,
                                   List<Hinweis> hinweise) {
        boolean gueltig = pfadGueltig(seite, kontext);
        if (!gueltig) {
            befunde.add(new Befund(Code.PFAD_UNGUELTIG,
                    "Der Pfad passt nicht zum Seitentyp, ist vergeben oder ungueltig."));
        }
        if (seite.pfad() != null && kontext.pfadHoehereStufe()
                && kontext.belegtePfade().contains(seite.pfad())) {
            hinweise.add(new Hinweis(Hinweiscode.HOEHERE_STUFE_VORHANDEN,
                    "Eine Seite mit höherer Vertraulichkeit belegt diesen Pfad."));
        }
    }

    private static boolean pfadGueltig(Wissensseite seite, Kontext kontext) {
        String pfad = seite.pfad();
        if (pfad == null || pfad.isBlank() || "index".equals(pfad) || "log".equals(pfad)) {
            return false;
        }
        if (seite.id() != null && !pfad.equals(kontext.bisherigerPfad())) {
            return false;
        }
        if (seite.id() == null && kontext.belegtePfade().contains(pfad)) {
            return false;
        }
        if (seite.typ() == null) {
            return false;
        }
        if (seite.typ() == Seitentyp.SYNTHESE) {
            return "ueberblick".equals(pfad);
        }

        String ordner = seite.typ().ordner().orElseThrow();
        String prefix = ordner + "/";
        if (!pfad.startsWith(prefix) || pfad.indexOf('/', prefix.length()) >= 0) {
            return false;
        }
        String name = pfad.substring(prefix.length());
        if (!kebab(name) || "index".equals(name) || "log".equals(name)) {
            return false;
        }
        if (seite.typ() == Seitentyp.QUELLE) {
            if (seite.wissensquelleId() == null) {
                return false;
            }
            DokumentQuelle quelle = kontext.dokumente().get(seite.wissensquelleId());
            if (quelle == null || quelle.datum() == null) {
                return false;
            }
            String monat = QUELLENMONAT.format(quelle.datum());
            return name.equals(monat) || name.startsWith(monat + "-");
        }
        return true;
    }

    private static void pruefeQuellen(Wissensseite seite, Kontext kontext, List<Befund> befunde) {
        Set<String> schluessel = new LinkedHashSet<>();
        for (Quelleneintrag quelle : seite.quellen()) {
            if (quelle.schluessel() == null || quelle.schluessel().isBlank()
                    || !schluessel.add(quelle.schluessel())) {
                befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                        "Ein Quelleneintrag hat keinen eindeutigen, stabilen Schlüssel."));
            }
            boolean dokument = quelle.dokumentId() != null;
            boolean quellSeite = quelle.quellSeiteId() != null;
            if (dokument == quellSeite) {
                befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                        "Ein Quelleneintrag muss genau ein Dokument oder eine Wissensseite bezeichnen."));
            } else if (dokument) {
                pruefeDokumentQuelle(quelle, kontext, befunde);
            } else {
                if (quelle.seiteVon() != null || quelle.seiteBis() != null || quelle.auszug() != null) {
                    befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                            "Eine Wissensseitenquelle darf keine PDF-Seiten oder Auszüge enthalten."));
                }
                if (!kontext.seitenIds().contains(quelle.quellSeiteId())) {
                    befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                            "Die referenzierte Wissensseite ist nicht vorhanden."));
                }
            }
            if (quelle.auszug() != null && zeichen(quelle.auszug()) > AUSZUG_MAX_ZEICHEN) {
                befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                        "Ein Quellenauszug darf hoechstens 300 Zeichen lang sein."));
            }
        }
    }

    /**
     * Die Wissensquelle einer Quelle-Seite gilt wie eine Dokumentquelle (Requirement 2.7, bei jedem Anlass). Damit
     * die Hülle sie enthält, muss die Quelle-Seite mindestens eine Fussnote auf einen Quelleneintrag zu ihr tragen.
     */
    private static void pruefeWissensquelle(Wissensseite seite, Kontext kontext, Set<String> fussnoten,
                                            List<Befund> befunde) {
        UUID wissensquelleId = seite.wissensquelleId();
        if (wissensquelleId == null) {
            return;
        }
        if (seite.typ() != null && seite.typ() != Seitentyp.QUELLE) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Nur eine Seite vom Typ «Quelle» verweist auf eine Wissensquelle."));
        }
        DokumentQuelle dokument = kontext.dokumente().get(wissensquelleId);
        if (dokument == null || !dokument.gleicherMandant()) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Die Wissensquelle fehlt im Mandanten oder ist nicht archiviert."));
            return;
        }
        if (dokument.personal()) {
            befunde.add(new Befund(Code.QUELLE_PERSONAL,
                    "PERSONAL-Dokumente dürfen nicht als Wissensquelle verwendet werden."));
        }
        if (!dokument.archiviert()) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Die Wissensquelle fehlt im Mandanten oder ist nicht archiviert."));
            return;
        }
        if (seite.typ() == Seitentyp.QUELLE && seite.quellen().stream()
                .noneMatch(quelle -> wissensquelleId.equals(quelle.dokumentId()) && quelle.schluessel() != null
                        && fussnoten.contains(quelle.schluessel()))) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Die Quelle-Seite belegt keine Aussage mit ihrer Wissensquelle."));
        }
    }

    private static void pruefeDokumentQuelle(Quelleneintrag quelle, Kontext kontext, List<Befund> befunde) {
        if (kontext.paket() && (quelle.auszug() == null || quelle.auszug().isBlank())) {
            befunde.add(new Befund(Code.AUSZUG_FEHLT, "Der Dokumentquelle des Pakets fehlt ein wörtlicher Auszug."));
        }
        DokumentQuelle dokument = kontext.dokumente().get(quelle.dokumentId());
        if (dokument == null || !dokument.gleicherMandant()) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Das Quelldokument fehlt im Mandanten oder ist nicht archiviert."));
            return;
        }
        if (dokument.personal()) {
            befunde.add(new Befund(Code.QUELLE_PERSONAL,
                "PERSONAL-Dokumente dürfen nicht als Wissensquelle verwendet werden."));
        }
        if (!dokument.archiviert()) {
            befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Das Quelldokument fehlt im Mandanten oder ist nicht archiviert."));
            return;
        }
        switch (auszugPruefen(quelle, dokument)) {
            case SEITE_FEHLT -> befunde.add(new Befund(Code.SEITE_FEHLT, "Der Dokumentquelle fehlt die PDF-Seite."));
            case NICHT_PRUEFBAR -> befunde.add(new Befund(kontext.paket() ? Code.AUSZUG_NICHT_PRUEFBAR : Code.SEITE_FEHLT,
                    "Für das Quelldokument sind keine prüfbaren PDF-Seiten verfügbar."));
            case QUELLE_UNGUELTIG -> befunde.add(new Befund(Code.QUELLE_UNGUELTIG,
                    "Die PDF-Seitenangabe liegt ausserhalb der Seitenzahl des Quelldokuments."));
            case NICHT_GEFUNDEN -> befunde.add(new Befund(Code.AUSZUG_NICHT_GEFUNDEN,
                    "Der wörtliche Auszug wurde auf der angegebenen PDF-Seite nicht gefunden."));
            case FEHLT -> {
                if (quelle.auszug() != null) {
                    befunde.add(new Befund(Code.AUSZUG_NICHT_GEFUNDEN,
                            "Der wörtliche Auszug wurde auf der angegebenen PDF-Seite nicht gefunden."));
                }
            }
            case GEFUNDEN -> { }
        }
    }

    public enum AuszugsErgebnis { GEFUNDEN, NICHT_GEFUNDEN, NICHT_PRUEFBAR, SEITE_FEHLT, QUELLE_UNGUELTIG, FEHLT }

    public static AuszugsErgebnis auszugPruefen(Quelleneintrag quelle, DokumentQuelle dokument) {
        if (quelle.seiteVon() == null) {
            return AuszugsErgebnis.SEITE_FEHLT;
        }
        if (dokument.seitentext().isEmpty() || dokument.seiten() == null) {
            return AuszugsErgebnis.NICHT_PRUEFBAR;
        }
        Seitentext seiten = dokument.seitentext().orElseThrow();
        int bis = quelle.seiteBis() == null ? quelle.seiteVon() : quelle.seiteBis();
        if (quelle.seiteVon() < 1 || bis < quelle.seiteVon() || bis > seiten.anzahl()
                || dokument.seiten() != seiten.anzahl()) {
            return AuszugsErgebnis.QUELLE_UNGUELTIG;
        }
        if (quelle.auszug() == null || quelle.auszug().isBlank()) {
            return AuszugsErgebnis.FEHLT;
        }
        return auszugGefunden(quelle.auszug(), seiten, quelle.seiteVon(), bis)
                ? AuszugsErgebnis.GEFUNDEN : AuszugsErgebnis.NICHT_GEFUNDEN;
    }

    private static boolean auszugGefunden(String auszug, Seitentext seiten, int von, int bis) {
        String gesucht = normalisieren(auszug);
        if (gesucht.isBlank()) {
            return false;
        }
        for (int seite = von; seite <= bis; seite++) {
            if (normalisieren(seiten.seiten().get(seite - 1)).contains(gesucht)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalisiert Auszug und Seitentext gleich für Ingest und Prüfung: NFKC, Silbentrennung am Zeilenende entfernen,
     * Leerraum zusammenfassen.
     */
    public static String auszugNormalisieren(String text) {
        return normalisieren(text);
    }

    private static String normalisieren(String text) {
        String normalisiert = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder ergebnis = new StringBuilder(normalisiert.length());
        boolean leerraum = true;
        for (int i = 0; i < normalisiert.length(); i++) {
            char zeichen = normalisiert.charAt(i);
            if ((zeichen == '-' || zeichen == '\u00ad') && i + 1 < normalisiert.length()
                    && (normalisiert.charAt(i + 1) == '\n' || normalisiert.charAt(i + 1) == '\r')) {
                i++;
                if (i + 1 < normalisiert.length() && normalisiert.charAt(i) == '\r'
                        && normalisiert.charAt(i + 1) == '\n') {
                    i++;
                }
                continue;
            }
            if (Character.isWhitespace(zeichen)) {
                if (!leerraum) {
                    ergebnis.append(' ');
                }
                leerraum = true;
            } else {
                ergebnis.append(zeichen);
                leerraum = false;
            }
        }
        return ergebnis.toString().strip();
    }

    private static void pruefeAbschnitte(Wissensseite seite, WissensMarkdown.Analyse markdown,
                                         List<Hinweis> hinweise) {
        Set<String> erforderlich = switch (seite.typ() == null ? Seitentyp.ANALYSE : seite.typ()) {
            case QUELLE -> Set.of("Zusammenfassung", "Kernaussagen", "Berührte Seiten");
            default -> "ueberblick".equals(seite.pfad())
                    ? Set.of("Gesamtbild", "Offene Fragen") : Set.of();
        };
        for (String abschnitt : erforderlich) {
            if (markdown.abschnitte().stream().noneMatch(abschnitt::equalsIgnoreCase)) {
                hinweise.add(new Hinweis(Hinweiscode.ABSCHNITT_FEHLT,
                        "Der Pflichtabschnitt «" + abschnitt + "» fehlt."));
            }
        }
    }

    private static Set<String> schluessel(List<Quelleneintrag> quellen) {
        Set<String> schluessel = new LinkedHashSet<>();
        quellen.stream().map(Quelleneintrag::schluessel).filter(value -> value != null)
                .forEach(schluessel::add);
        return schluessel;
    }

    private static boolean textGueltig(String text) {
        return text != null && !text.isBlank() && text.chars().noneMatch(Character::isISOControl);
    }

    private static boolean beschreibungGueltig(String beschreibung) {
        if (!textGueltig(beschreibung) || zeichen(beschreibung) > BESCHREIBUNG_MAX_ZEICHEN) {
            return false;
        }
        return einSatz(beschreibung.strip());
    }

    /**
     * Ein Satzende ist «!» oder «?» mit weiterem Text dahinter, oder ein Punkt mit Leerraum und Grossbuchstaben
     * dahinter, ausser nach einer Zahl, einem einzelnen Buchstaben («z. B.») oder einer bekannten Abkürzung.
     */
    private static boolean einSatz(String text) {
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            if (zeichen != '.' && zeichen != '!' && zeichen != '?') {
                continue;
            }
            int weiter = i + 1;
            while (weiter < text.length() && Character.isWhitespace(text.charAt(weiter))) {
                weiter++;
            }
            if (weiter == i + 1 || weiter >= text.length()) {
                continue;
            }
            if (zeichen != '.') {
                return false;
            }
            if (!Character.isUpperCase(text.codePointAt(weiter))) {
                continue;
            }
            int anfang = i;
            while (anfang > 0 && Character.isLetterOrDigit(text.charAt(anfang - 1))) {
                anfang--;
            }
            String wort = text.substring(anfang, i);
            if (wort.length() != 1 && !wort.chars().allMatch(Character::isDigit)
                    && !ABKUERZUNGEN.contains(wort.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static boolean kebab(String wert) {
        if (wert == null || wert.isEmpty() || wert.charAt(0) == '-' || wert.charAt(wert.length() - 1) == '-') {
            return false;
        }
        boolean bindestrich = false;
        for (int i = 0; i < wert.length(); i++) {
            char zeichen = wert.charAt(i);
            if (zeichen == '-') {
                if (bindestrich) {
                    return false;
                }
                bindestrich = true;
            } else if (zeichen >= 'a' && zeichen <= 'z' || zeichen >= '0' && zeichen <= '9') {
                bindestrich = false;
            } else {
                return false;
            }
        }
        return true;
    }

    private static int zeichen(String text) {
        return text.codePointCount(0, text.length());
    }

    private static int rang(Wissensseite.Vertraulichkeit vertraulichkeit) {
        return vertraulichkeit == Wissensseite.Vertraulichkeit.NORMAL ? 0 : 1;
    }

    public enum Code {
        FELD_FEHLT,
        SCHLAGWORT_UNGUELTIG,
        PFAD_UNGUELTIG,
        FUSSNOTE_OHNE_QUELLE,
        FUSSNOTE_DEFINITION,
        AUSSAGE_OHNE_BELEG,
        QUELLE_UNGUELTIG,
        QUELLE_UNBEKANNT,
        SEITE_FEHLT,
        QUELLE_PERSONAL,
        QUELLE_NICHT_BELEGT,
        AUSZUG_NICHT_GEFUNDEN,
        AUSZUG_FEHLT,
        AUSZUG_NICHT_PRUEFBAR,
        CLOUD_STUFE_UNZULAESSIG,
        LINK_UNGUELTIG,
        AUSSAGE_ENTFERNT,
        ZU_GROSS,
        MARKDOWN_ZU_TIEF,
        STUFE_UEBERBLICK
    }

    public enum Hinweiscode {
        ABSCHNITT_FEHLT,
        HOEHERE_STUFE_VORHANDEN,
        STUFE_STEIGT,
        BASIS_UNBEKANNT,
        STUFE_IGNORIERT,
        FELD_IGNORIERT,
        FELD_UNBEKANNT,
        DATEI_IGNORIERT
    }

    public record Befund(Code code, String meldung) {
    }

    public record Hinweis(Hinweiscode code, String meldung) {
    }

    public record Ergebnis(List<Befund> befunde, List<Hinweis> hinweise, Set<String> links) {
        public Ergebnis {
            befunde = List.copyOf(befunde);
            hinweise = List.copyOf(hinweise);
            links = Collections.unmodifiableSet(new LinkedHashSet<>(links));
        }

        public boolean annehmbar() {
            return befunde.isEmpty();
        }
    }

    public record DokumentQuelle(boolean gleicherMandant, boolean archiviert, boolean personal, Integer seiten,
                                 LocalDate datum, Optional<Seitentext> seitentext) {
        public DokumentQuelle {
            seitentext = seitentext == null ? Optional.empty() : seitentext;
        }
    }

    public record Kontext(int maxZeichen, String bisherigerPfad, Set<String> belegtePfade,
                          Map<UUID, DokumentQuelle> dokumente, Set<UUID> seitenIds,
                          Set<String> basisQuellenSchluessel, Set<String> entfernteAussagen,
                          Set<String> kernaussagenSchluessel, boolean ingest, boolean veralten,
                          boolean pfadHoehereStufe, Wissensseite.Vertraulichkeit bisherigeVertraulichkeit,
                          Wissensseite.Vertraulichkeit wirksameVertraulichkeit, boolean paket) {

        public Kontext(int maxZeichen, String bisherigerPfad, Set<String> belegtePfade,
                       Map<UUID, DokumentQuelle> dokumente, Set<UUID> seitenIds,
                       Set<String> basisQuellenSchluessel, Set<String> entfernteAussagen,
                       Set<String> kernaussagenSchluessel, boolean ingest, boolean veralten,
                       boolean pfadHoehereStufe, Wissensseite.Vertraulichkeit bisherigeVertraulichkeit,
                       Wissensseite.Vertraulichkeit wirksameVertraulichkeit) {
            this(maxZeichen, bisherigerPfad, belegtePfade, dokumente, seitenIds, basisQuellenSchluessel,
                    entfernteAussagen, kernaussagenSchluessel, ingest, veralten, pfadHoehereStufe,
                    bisherigeVertraulichkeit, wirksameVertraulichkeit, false);
        }

        public Kontext {
            if (maxZeichen < 1) {
                throw new IllegalArgumentException("Die maximale Seitenlänge muss positiv sein.");
            }
            belegtePfade = Set.copyOf(belegtePfade);
            dokumente = Map.copyOf(dokumente);
            seitenIds = Set.copyOf(seitenIds);
            basisQuellenSchluessel = Set.copyOf(basisQuellenSchluessel);
            entfernteAussagen = Set.copyOf(entfernteAussagen);
            kernaussagenSchluessel = Set.copyOf(kernaussagenSchluessel);
        }

        public static Kontext leer() {
            return new Kontext(SEITE_MAX_ZEICHEN_STARTWERT, null, Set.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), false, false, false, null, null);
        }
    }
}
