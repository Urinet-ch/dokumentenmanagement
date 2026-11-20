package ch.urinet.archiv.wissen.domain;

import org.commonmark.Extension;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WissensMarkdown {

    public static final int MAX_VERSCHACHTELUNG = 64;
    public static final int MAX_EINGABE_ZEICHEN = 200_000;
    private static final Set<String> ORDNER = Set.of("quellen", "konzepte", "akteure", "projekte", "analysen");

    private static final List<Extension> EXTENSIONS = List.of(FootnotesExtension.create());
    private static final Pattern FUSSNOTE = Pattern.compile("\\[\\^([^\\s\\[\\]\\\\]+)]");
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private static final Parser EXPORT_PARSER = Parser.builder().extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .attributeProviderFactory(context -> (node, tagName, attribute) -> {
                if (node instanceof Link link) {
                    String pfad = internerPfad(link.getDestination());
                    attribute.remove("href");
                    if (pfad != null) {
                        attribute.put("href", "/wissen/" + pfad);
                    }
                } else if (node instanceof Image) {
                    attribute.remove("src");
                    attribute.remove("srcset");
                }
            })
            .build();

    private WissensMarkdown() {
    }

    public static Analyse analysieren(String markdown, int maxZeichen) {
        return analysieren(markdown, maxZeichen, null);
    }

    private static Analyse analysieren(String markdown, int maxZeichen, Set<String> sichtbarePfade) {
        if (maxZeichen < 1) {
            throw new IllegalArgumentException("Die maximale Markdown-Laenge muss positiv sein.");
        }
        String roh = markdown == null ? "" : markdown;
        if (zeichen(roh) > maxZeichen || zeichen(roh) > MAX_EINGABE_ZEICHEN) {
            return Analyse.abgelehnt(true, false);
        }
        // CommonMark wertet auch ein einzelnes \r als Zeilenende; Vorprüfung und Parser sehen dieselbe Fassung.
        String text = roh.replace("\r\n", "\n").replace('\r', '\n');
        if (tiefeUeberschritten(text)) {
            return Analyse.abgelehnt(false, true);
        }

        int laenge = text.length();
        Node dokument = PARSER.parse(text + fussnotenDefinitionen(text));
        Set<String> links = new TreeSet<>();
        Set<String> fussnoten = new TreeSet<>();
        Set<String> abschnitte = new TreeSet<>();
        List<Aussage> aussagen = new ArrayList<>();
        Merkmale merkmale = new Merkmale();

        Deque<Integer> ueberschriften = new ArrayDeque<>();
        Deque<String> abschnittstitel = new ArrayDeque<>();
        for (Node kind = dokument.getFirstChild(); kind != null; kind = kind.getNext()) {
            if (kind instanceof Heading ueberschrift) {
                while (!ueberschriften.isEmpty() && ueberschriften.peek() >= ueberschrift.getLevel()) {
                    ueberschriften.pop();
                    abschnittstitel.pop();
                }
                String titel = text(ueberschrift).strip();
                ueberschriften.push(ueberschrift.getLevel());
                abschnittstitel.push(titel);
                abschnitte.add(titel);
                untersuchen(kind, false, false, false, laenge, links, fussnoten, aussagen, merkmale);
            } else {
                boolean ausgenommen = !abschnittstitel.isEmpty()
                        && abschnittOhneBeleg(abschnittstitel.peek());
                untersuchen(kind, false, false, ausgenommen, laenge, links, fussnoten, aussagen, merkmale);
            }
        }
        if (merkmale.zuTief) {
            return Analyse.abgelehnt(false, true);
        }
        merkmale.eigeneDefinitionen.forEach(Node::unlink);
        return new Analyse(rendern(dokument, sichtbarePfade), links, fussnoten, abschnitte, aussagen, merkmale.html,
                merkmale.bild, merkmale.ungueltigerLink, merkmale.wikilink,
                !merkmale.eigeneDefinitionen.isEmpty(), false, false, merkmale.widersprueche);
    }

    public static String darstellen(String markdown) {
        Analyse analyse = analysieren(markdown, MAX_EINGABE_ZEICHEN);
        if (analyse.zuGross() || analyse.zuTief()) {
            throw new IllegalArgumentException("Das Markdown ueberschreitet die zulaessige Groesse oder Verschachtelung.");
        }
        return analyse.html();
    }

    public static String darstellen(String markdown, Set<String> sichtbarePfade) {
        Analyse analyse = analysieren(markdown, MAX_EINGABE_ZEICHEN, Set.copyOf(sichtbarePfade));
        if (analyse.zuGross() || analyse.zuTief()) {
            throw new IllegalArgumentException("Das Markdown ueberschreitet die zulaessige Groesse oder Verschachtelung.");
        }
        return analyse.html();
    }

    /** Schreibt nur erkannte interne Linkziele um; insbesondere Fussnoten und Code bleiben unverändert. */
    public static String fuerOkf(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("Markdown fehlt");
        }
        Analyse analyse = analysieren(markdown, MAX_EINGABE_ZEICHEN);
        if (analyse.zuGross() || analyse.zuTief() || analyse.ungueltigerLink()
                || analyse.htmlVorhanden() || analyse.bildVorhanden() || analyse.wikilink()
                || analyse.fussnotenDefinition()) {
            throw new IllegalArgumentException("Markdown ist für den OKF-Export ungültig");
        }
        Deque<Node> offen = new ArrayDeque<>();
        offen.push(EXPORT_PARSER.parse(markdown));
        List<Integer> einfuegen = new ArrayList<>();
        while (!offen.isEmpty()) {
            Node node = offen.pop();
            if (node instanceof Link link) {
                if (internerPfad(link.getDestination()) == null) {
                    throw new IllegalArgumentException("Ungültiges Linkziel im OKF-Export");
                }
                einfuegen.add(zielEnde(markdown, link));
            }
            for (Node kind = node.getFirstChild(); kind != null; kind = kind.getNext()) {
                offen.push(kind);
            }
        }
        StringBuilder ausgabe = new StringBuilder(markdown);
        einfuegen.sort(Collections.reverseOrder());
        einfuegen.forEach(index -> ausgabe.insert(index, ".md"));
        return ausgabe.toString();
    }

    public static boolean gueltigerSeitenpfad(String pfad) {
        return pfad != null && internerPfad("/" + pfad) != null;
    }

    private static int zielEnde(String markdown, Link link) {
        List<SourceSpan> spans = link.getSourceSpans();
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("Linkposition im Markdown fehlt");
        }
        int ende = spans.getLast().getInputIndex() + spans.getLast().getLength();
        Node letzter = link.getLastChild();
        List<SourceSpan> kindSpans = letzter == null ? List.of() : letzter.getSourceSpans();
        int ab = kindSpans.isEmpty() ? spans.getFirst().getInputIndex() + 1
                : kindSpans.getLast().getInputIndex() + kindSpans.getLast().getLength();
        int klammer = markdown.indexOf("](", ab);
        if (klammer < 0 || klammer >= ende) {
            throw new IllegalArgumentException("Linkziel im Markdown nicht gefunden");
        }
        int start = klammer + 2;
        while (start < ende && Character.isWhitespace(markdown.charAt(start))) {
            start++;
        }
        boolean spitz = start < ende && markdown.charAt(start) == '<';
        int pos = spitz ? start + 1 : start;
        while (pos < ende) {
            char zeichen = markdown.charAt(pos);
            if (zeichen == '\\' && pos + 1 < ende) {
                pos += 2;
            } else if (spitz ? zeichen == '>' : zeichen == ')' || Character.isWhitespace(zeichen)) {
                return pos;
            } else {
                pos++;
            }
        }
        throw new IllegalArgumentException("Linkziel im Markdown nicht abgeschlossen");
    }

    private static String rendern(Node dokument, Set<String> sichtbarePfade) {
        if (sichtbarePfade != null) {
            Deque<Node> stapel = new ArrayDeque<>();
            stapel.push(dokument);
            while (!stapel.isEmpty()) {
                Node parent = stapel.pop();
                for (Node kind = parent.getFirstChild(); kind != null;) {
                    Node nachfolger = kind.getNext();
                    if (kind instanceof Link link) {
                        String pfad = internerPfad(link.getDestination());
                        if (pfad != null && !sichtbarePfade.contains(pfad)) {
                            link.insertBefore(new Text("Seite nicht vorhanden"));
                            link.unlink();
                        }
                    } else {
                        stapel.push(kind);
                    }
                    kind = nachfolger;
                }
            }
        }
        return RENDERER.render(dokument);
    }

    /**
     * Requirement 2.6 schreibt Fussnoten als {@code Aussage.[^schluessel]} ohne Definition im Inhalt; die Quelle
     * steht im Quelleneintrag. commonmark erkennt eine Fussnote aber nur mit Definition, darum wird für jedes
     * Label eine leere Definition hinter dem Inhalt ergänzt.
     */
    private static String fussnotenDefinitionen(String text) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher treffer = FUSSNOTE.matcher(text);
        while (treffer.find()) {
            labels.add(treffer.group(1));
        }
        StringBuilder definitionen = new StringBuilder("\n\n");
        labels.forEach(label -> definitionen.append("[^").append(label).append("]:\n\n"));
        return definitionen.toString();
    }

    private static boolean ergaenzt(Node definition, int laenge) {
        return !definition.getSourceSpans().isEmpty() && definition.getSourceSpans().get(0).getInputIndex() >= laenge;
    }

    private static void untersuchen(Node wurzel, boolean widerspruch,
                                   boolean inFussnote, boolean aussagenAusgenommen, int laenge, Set<String> links,
                                   Set<String> fussnoten, List<Aussage> aussagen, Merkmale merkmale) {
        Deque<Besuch> offen = new ArrayDeque<>();
        offen.push(new Besuch(wurzel, 0, widerspruch, inFussnote, aussagenAusgenommen));
        while (!offen.isEmpty()) {
            Besuch besuch = offen.pop();
            Node node = besuch.node();
            if (besuch.tiefe() > MAX_VERSCHACHTELUNG) {
                merkmale.zuTief = true;
                continue;
            }

            boolean blockZitat = besuch.widerspruch()
                    || node instanceof BlockQuote blockQuote && widerspruchsblock(blockQuote);
            boolean fussnotenDefinition = besuch.inFussnote()
                    || node instanceof FootnoteDefinition;
            if (blockZitat && !besuch.widerspruch() && !fussnotenDefinition) {
                merkmale.widersprueche.add(new Aussage(text(node), alleFussnotenIn(node)));
            }

            if (node instanceof HtmlInline || node instanceof HtmlBlock) {
                merkmale.html = true;
            }
            if (node instanceof Image) {
                merkmale.bild = true;
            }
            if (node instanceof LinkReferenceDefinition) {
                // Requirement 3.5 erlaubt nur «[Titel](/pfad)»; Definitionen tragen sonst unsichtbaren Text und Ziele.
                merkmale.ungueltigerLink = true;
            }
            if (node instanceof Link link) {
                String pfad = internerPfad(link.getDestination());
                if (pfad == null) {
                    merkmale.ungueltigerLink = true;
                } else {
                    links.add(pfad);
                }
            }
            if (node instanceof FootnoteReference reference) {
                fussnoten.add(reference.getLabel());
            }
            if (node instanceof FootnoteDefinition definition) {
                if (ergaenzt(definition, laenge)) {
                    definition.appendChild(absatz(definition.getLabel()));
                } else {
                    merkmale.eigeneDefinitionen.add(definition);
                }
            }
            if (node instanceof Text literal && !codeElternteil(node) && literal.getLiteral().contains("[[")) {
                merkmale.wikilink = true;
            }
            if (!besuch.aussagenAusgenommen() && !blockZitat && !fussnotenDefinition
                    && node instanceof Paragraph paragraph && !ersterAbsatzImListenpunkt(paragraph)) {
                aussagen.add(new Aussage(text(paragraph), fussnotenIn(paragraph)));
            } else if (!besuch.aussagenAusgenommen() && !blockZitat && !fussnotenDefinition
                    && node instanceof ListItem item) {
                // Der Listenpunkt ist mit seinem ersten Absatz belegt; weitere Absätze zählen einzeln.
                Node erster = item.getFirstChild();
                aussagen.add(new Aussage(text(item),
                        fussnotenIn(erster instanceof Paragraph ? erster : item)));
            } else if (!besuch.aussagenAusgenommen() && !blockZitat && !fussnotenDefinition
                    && (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock)) {
                aussagen.add(new Aussage(codeText(node), Set.of()));
            }

            for (Node kind = node.getLastChild(); kind != null; kind = kind.getPrevious()) {
                offen.push(new Besuch(kind, besuch.tiefe() + 1, blockZitat, fussnotenDefinition,
                        besuch.aussagenAusgenommen()));
            }
        }
    }

    private static Set<String> fussnotenIn(Node wurzel) {
        Set<String> schluessel = new LinkedHashSet<>();
        Deque<Node> offen = new ArrayDeque<>();
        offen.push(wurzel);
        while (!offen.isEmpty()) {
            Node node = offen.pop();
            if (node != wurzel && node instanceof ListItem) {
                continue;
            }
            if (node instanceof FootnoteReference reference) {
                schluessel.add(reference.getLabel());
            }
            for (Node kind = node.getLastChild(); kind != null; kind = kind.getPrevious()) {
                offen.push(kind);
            }
        }
        return Collections.unmodifiableSet(schluessel);
    }

    private static boolean ersterAbsatzImListenpunkt(Paragraph absatz) {
        return absatz.getParent() instanceof ListItem && absatz.getPrevious() == null;
    }

    private static Paragraph absatz(String label) {
        Paragraph absatz = new Paragraph();
        absatz.appendChild(new Text(label));
        return absatz;
    }

    private static String codeText(Node node) {
        return node instanceof FencedCodeBlock code ? code.getLiteral() : ((IndentedCodeBlock) node).getLiteral();
    }

    private static boolean codeElternteil(Node node) {
        for (Node eltern = node.getParent(); eltern != null; eltern = eltern.getParent()) {
            if (eltern instanceof Code || eltern instanceof FencedCodeBlock || eltern instanceof IndentedCodeBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ein Widerspruchsblock ist nach dem Glossar von Spec 04 ein markierter Abschnitt «mit den Quellen beider
     * Aussagen»: Nur ein Blockzitat mit der Marke und Fussnoten auf mindestens zwei verschiedene Labels ist von der
     * Belegpflicht ausgenommen; sonst zählen seine Absätze und Listenpunkte als Aussagen.
     */
    private static boolean widerspruchsblock(BlockQuote blockQuote) {
        for (Node kind = blockQuote.getFirstChild(); kind != null; kind = kind.getNext()) {
            String inhalt = text(kind).stripLeading();
            if (!inhalt.isBlank()) {
                return inhalt.startsWith("⚠️ Widerspruch:") && alleFussnotenIn(blockQuote).size() >= 2;
            }
        }
        return false;
    }

    private static Set<String> alleFussnotenIn(Node wurzel) {
        Set<String> schluessel = new LinkedHashSet<>();
        Deque<Node> offen = new ArrayDeque<>();
        offen.push(wurzel);
        while (!offen.isEmpty()) {
            Node node = offen.pop();
            if (node instanceof FootnoteReference reference) {
                schluessel.add(reference.getLabel());
            }
            for (Node kind = node.getLastChild(); kind != null; kind = kind.getPrevious()) {
                offen.push(kind);
            }
        }
        return schluessel;
    }

    private static boolean abschnittOhneBeleg(String titel) {
        return titel.equalsIgnoreCase("Offene Fragen") || titel.equalsIgnoreCase("Berührte Seiten");
    }

    private static String text(Node wurzel) {
        StringBuilder text = new StringBuilder();
        Deque<Node> offen = new ArrayDeque<>();
        offen.push(wurzel);
        while (!offen.isEmpty()) {
            Node node = offen.pop();
            if (node instanceof Text literal) {
                text.append(literal.getLiteral());
            } else if (node instanceof Code code) {
                text.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                text.append(' ');
            }
            for (Node kind = node.getLastChild(); kind != null; kind = kind.getPrevious()) {
                offen.push(kind);
            }
        }
        return text.toString();
    }

    private static String internerPfad(String ziel) {
        if (ziel == null || ziel.length() < 2 || ziel.charAt(0) != '/' || ziel.charAt(1) == '/'
                || ziel.indexOf('?') >= 0 || ziel.indexOf('#') >= 0 || ziel.indexOf('%') >= 0
                || ziel.indexOf('\\') >= 0) {
            return null;
        }
        String pfad = ziel.substring(1);
        int trenner = pfad.indexOf('/');
        if (trenner < 0) {
            return pfad.equals("ueberblick") ? pfad : null;
        }
        if (pfad.indexOf('/', trenner + 1) >= 0
                || !ORDNER.contains(pfad.substring(0, trenner))
                || !gueltigesSegment(pfad, trenner + 1, pfad.length())
                || pfad.substring(trenner + 1).equals("index")
                || pfad.substring(trenner + 1).equals("log")) {
            return null;
        }
        return pfad;
    }

    private static boolean gueltigesSegment(String pfad, int von, int bis) {
        if (von == bis || pfad.charAt(von) == '-' || pfad.charAt(bis - 1) == '-') {
            return false;
        }
        for (int i = von; i < bis; i++) {
            char zeichen = pfad.charAt(i);
            if (!(zeichen >= 'a' && zeichen <= 'z' || zeichen >= '0' && zeichen <= '9' || zeichen == '-')
                    || zeichen == '-' && i > von && pfad.charAt(i - 1) == '-') {
                return false;
            }
        }
        return true;
    }

    private static int zeichen(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean tiefeUeberschritten(String markdown) {
        int klammertiefe = 0;
        int zeilenanfang = 0;
        char zaun = 0;
        int inlineCodeTicks = 0;
        while (zeilenanfang <= markdown.length()) {
            int zeilenende = markdown.indexOf('\n', zeilenanfang);
            if (zeilenende < 0) {
                zeilenende = markdown.length();
            }
            int cursor = zeilenanfang;
            while (cursor < zeilenende && markdown.charAt(cursor) == ' ') {
                cursor++;
            }
            if (zaun == 0 && leer(markdown, cursor, zeilenende)) {
                // Links und Code-Spans reichen nie über eine Leerzeile; offene Klammern davor zählen nicht mehr.
                klammertiefe = 0;
                inlineCodeTicks = 0;
                zeilenanfang = zeilenende + 1;
                continue;
            }
            if (istListenanfang(markdown, cursor, zeilenende)
                    && (cursor - zeilenanfang) / 2 > MAX_VERSCHACHTELUNG) {
                return true;
            }
            if (cursor - zeilenanfang > 3) {
                zeilenanfang = zeilenende + 1;
                continue;
            }
            if (cursor + 2 < zeilenende && (markdown.startsWith("```", cursor) || markdown.startsWith("~~~", cursor))) {
                char kandidat = markdown.charAt(cursor);
                if (zaun == 0) {
                    zaun = kandidat;
                } else if (zaun == kandidat) {
                    zaun = 0;
                }
                zeilenanfang = zeilenende + 1;
                continue;
            }
            if (zaun != 0) {
                zeilenanfang = zeilenende + 1;
                continue;
            }

            int blockZitatTiefe = 0;
            while (cursor < zeilenende && markdown.charAt(cursor) == '>') {
                if (++blockZitatTiefe > MAX_VERSCHACHTELUNG) {
                    return true;
                }
                cursor++;
                if (cursor < zeilenende && markdown.charAt(cursor) == ' ') {
                    cursor++;
                }
            }
            for (int i = cursor; i < zeilenende; i++) {
                char zeichen = markdown.charAt(i);
                if (zeichen == '\\') {
                    i++;
                    continue;
                }
                if (zeichen == '`') {
                    int run = 1;
                    while (i + run < zeilenende && markdown.charAt(i + run) == '`') {
                        run++;
                    }
                    if (inlineCodeTicks == 0) {
                        inlineCodeTicks = run;
                    } else if (inlineCodeTicks == run) {
                        inlineCodeTicks = 0;
                    }
                    i += run - 1;
                } else if (inlineCodeTicks == 0) {
                    if (zeichen == '[' || zeichen == '(') {
                        if (++klammertiefe > MAX_VERSCHACHTELUNG) {
                            return true;
                        }
                    } else if ((zeichen == ']' || zeichen == ')') && klammertiefe > 0) {
                        klammertiefe--;
                    }
                }
            }
            zeilenanfang = zeilenende + 1;
        }
        return false;
    }

    private static boolean leer(String text, int von, int bis) {
        for (int i = von; i < bis; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean istListenanfang(String text, int cursor, int ende) {
        if (cursor >= ende) {
            return false;
        }
        char zeichen = text.charAt(cursor);
        if (zeichen == '-' || zeichen == '*' || zeichen == '+') {
            return cursor + 1 < ende && Character.isWhitespace(text.charAt(cursor + 1));
        }
        int zahlEnde = cursor;
        while (zahlEnde < ende && text.charAt(zahlEnde) >= '0' && text.charAt(zahlEnde) <= '9') {
            zahlEnde++;
        }
        return zahlEnde > cursor && zahlEnde + 1 < ende
                && (text.charAt(zahlEnde) == '.' || text.charAt(zahlEnde) == ')')
                && Character.isWhitespace(text.charAt(zahlEnde + 1));
    }

    public record Aussage(String text, Set<String> fussnoten) {
        public Aussage {
            fussnoten = Collections.unmodifiableSet(new LinkedHashSet<>(fussnoten));
        }
    }

    public static List<Aussage> entfernteWidersprueche(String basis, String fassung) {
        List<Aussage> verblieben = new ArrayList<>(analysieren(fassung, MAX_EINGABE_ZEICHEN).widersprueche());
        List<Aussage> entfernt = new ArrayList<>();
        for (Aussage block : analysieren(basis, MAX_EINGABE_ZEICHEN).widersprueche()) {
            if (!verblieben.remove(block)) {
                entfernt.add(block);
            }
        }
        return List.copyOf(entfernt);
    }

    public record Analyse(String html, Set<String> links, Set<String> fussnoten, Set<String> abschnitte,
                          List<Aussage> aussagen, boolean htmlVorhanden, boolean bildVorhanden,
                          boolean ungueltigerLink, boolean wikilink, boolean fussnotenDefinition, boolean zuGross,
                          boolean zuTief, List<Aussage> widersprueche) {
        public Analyse {
            links = Collections.unmodifiableSet(new LinkedHashSet<>(links));
            fussnoten = Collections.unmodifiableSet(new LinkedHashSet<>(fussnoten));
            abschnitte = Collections.unmodifiableSet(new LinkedHashSet<>(abschnitte));
            aussagen = List.copyOf(aussagen);
            widersprueche = List.copyOf(widersprueche);
        }

        private static Analyse abgelehnt(boolean zuGross, boolean zuTief) {
            return new Analyse("", Set.of(), Set.of(), Set.of(), List.of(), false, false, false, false, false,
                    zuGross, zuTief, List.of());
        }
    }

    private record Besuch(Node node, int tiefe, boolean widerspruch, boolean inFussnote,
                          boolean aussagenAusgenommen) {
    }

    private static final class Merkmale {
        private boolean html;
        private boolean bild;
        private boolean ungueltigerLink;
        private boolean wikilink;
        private boolean zuTief;
        private final List<Node> eigeneDefinitionen = new ArrayList<>();
        private final List<Aussage> widersprueche = new ArrayList<>();
    }
}
