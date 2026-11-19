package ch.urinet.archiv.wissen.adapter.out.okf;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Schreibt aktuelle Wissensseiten als OKF- und importierbares Wissenspaket, ohne Dokumentdateien. */
public final class OkfSchreiber {
    public record Dokument(UUID id, String sha256, String titel, LocalDate datum) {
    }

    public record Seitenverweis(UUID id, String pfad, String titel, Instant geaendert) {
    }

    public record Seite(Wissensseite seite, Map<UUID, Dokument> dokumente, Map<UUID, Seitenverweis> seiten) {
        public Seite {
            Objects.requireNonNull(seite, "seite");
            dokumente = Map.copyOf(dokumente);
            seiten = Map.copyOf(seiten);
        }
    }

    public record Logzeile(Instant zeit, String aktion, String text) {
    }

    public record Paket(MandantCode mandant, String erzeugtVon, String erzeugtMit, Instant erzeugtAm,
                        String instanz, Set<String> pfade) {
        public Paket {
            Objects.requireNonNull(mandant, "mandant");
            Objects.requireNonNull(erzeugtAm, "erzeugtAm");
            if (erzeugtVon == null || erzeugtVon.isBlank() || !erzeugtVon.matches("[A-Za-z0-9._/:+ -]{1,100}")
                    || erzeugtMit == null || !Set.of("lokal", "cloud").contains(erzeugtMit)
                    || instanz == null || instanz.isBlank()) {
                throw new IllegalArgumentException("Ungültige Herkunft des Wissenspakets");
            }
            pfade = Set.copyOf(pfade);
            if (pfade.stream().anyMatch(pfad -> !WissensMarkdown.gueltigerSeitenpfad(pfad))) {
                throw new IllegalArgumentException("Ungültiger Seitenpfad im Wissenspaket");
            }
        }
    }

    private record Indexzeile(String pfad, String titel, String beschreibung) {
    }

    private OkfSchreiber() {
    }

    public static void schreiben(Paket paket, Iterable<Seite> seiten, Iterable<Logzeile> log, OutputStream ziel)
            throws IOException, InterruptedException {
        Objects.requireNonNull(paket, "paket");
        Objects.requireNonNull(seiten, "seiten");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(ziel, "ziel");
        Map<Seitentyp, List<Indexzeile>> index = new EnumMap<>(Seitentyp.class);
        Set<String> geschrieben = new HashSet<>();
        Wissensseite.Vertraulichkeit stufe = Wissensseite.Vertraulichkeit.NORMAL;
        try (ZipOutputStream zip = new ZipOutputStream(new NichtSchliessend(ziel), StandardCharsets.UTF_8)) {
            for (Seite daten : seiten) {
                unterbrochen();
                Wissensseite seite = daten.seite();
                if (!paket.pfade().contains(seite.pfad()) || !geschrieben.add(seite.pfad())) {
                    throw new IllegalArgumentException("Fehlender oder mehrfacher Seitenpfad im Wissenspaket");
                }
                WissensMarkdown.Analyse analyse = WissensMarkdown.analysieren(seite.inhalt(),
                        WissensMarkdown.MAX_EINGABE_ZEICHEN);
                if (!paket.pfade().containsAll(analyse.links())) {
                    throw new IllegalArgumentException("Ein Seitenlink zeigt nicht auf eine Datei im Wissenspaket");
                }
                if (Objects.requireNonNull(seite.vertraulichkeit(), "vertraulichkeit")
                        == Wissensseite.Vertraulichkeit.INTERN) {
                    stufe = Wissensseite.Vertraulichkeit.INTERN;
                }
                datei(zip, seite.pfad() + ".md", frontmatter(seitenkopf(paket, daten))
                        + WissensMarkdown.fuerOkf(seite.inhalt()));
                index.computeIfAbsent(seite.typ(), typ -> new ArrayList<>())
                        .add(new Indexzeile(seite.pfad(), seite.titel(), seite.beschreibung()));
            }
            if (!geschrieben.equals(paket.pfade())) {
                throw new IllegalArgumentException("Seiten des Wissenspakets fehlen");
            }
            if ("cloud".equals(paket.erzeugtMit()) && stufe == Wissensseite.Vertraulichkeit.INTERN) {
                throw new IllegalArgumentException("Cloud-Paket darf keine interne Kontextstufe haben");
            }
            datei(zip, "paket.yaml", yaml(paketkopf(paket, stufe)));
            datei(zip, "index.md", index(index));
            log(zip, log);
            zip.finish();
        }
    }

    private static Map<String, Object> paketkopf(Paket paket, Wissensseite.Vertraulichkeit stufe) {
        Map<String, Object> kopf = new LinkedHashMap<>();
        kopf.put("paket_format", 1);
        kopf.put("mandant", paket.mandant().wert());
        kopf.put("erzeugt_von", paket.erzeugtVon());
        kopf.put("erzeugt_mit", paket.erzeugtMit());
        kopf.put("kontext_stufe", stufe.name());
        kopf.put("erzeugt_am", paket.erzeugtAm().toString());
        kopf.put("herkunft", Map.of("instanz", paket.instanz()));
        return kopf;
    }

    private static Map<String, Object> seitenkopf(Paket paket, Seite daten) {
        Wissensseite seite = daten.seite();
        Map<String, Object> kopf = new LinkedHashMap<>();
        kopf.put("type", Objects.requireNonNull(seite.typ(), "typ").datenbankwert());
        kopf.put("title", nichtLeer(seite.titel(), "titel"));
        kopf.put("description", nichtLeer(seite.beschreibung(), "beschreibung"));
        kopf.put("tags", new ArrayList<>(seite.tags()));
        kopf.put("status", switch (Objects.requireNonNull(seite.status(), "status")) {
            case AKTIV -> "stable";
            case VERALTET -> "deprecated";
        });
        kopf.put("stale_after", seite.veraltetAb() == null ? null : seite.veraltetAb().toString());
        kopf.put("generated", Map.of("by", seite.erzeugtVon(), "at", seite.erzeugtAm().toString()));
        kopf.put("verified", List.of(Map.of("by", seite.freigegebenVon(), "at", seite.freigegebenAm().toString())));
        kopf.put("erzeugt_von", paket.erzeugtVon());
        kopf.put("basis_version", seite.version());
        List<Map<String, Object>> quellen = new ArrayList<>();
        boolean wissensquelleMarkiert = false;
        for (Quelleneintrag quelle : seite.quellen()) {
            boolean wissensquelle = !wissensquelleMarkiert && seite.typ() == Seitentyp.QUELLE
                    && seite.wissensquelleId() != null && seite.wissensquelleId().equals(quelle.dokumentId());
            quellen.add(quelle(paket, daten, quelle, wissensquelle));
            wissensquelleMarkiert |= wissensquelle;
        }
        if (seite.typ() == Seitentyp.QUELLE && !wissensquelleMarkiert) {
            throw new IllegalArgumentException("Dokumentquelle der Quelle-Seite fehlt");
        }
        kopf.put("sources", quellen);
        return kopf;
    }

    private static Map<String, Object> quelle(Paket paket, Seite daten, Quelleneintrag quelle,
                                               boolean wissensquelle) {
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        ergebnis.put("id", Objects.requireNonNull(quelle.schluessel(), "quellenschluessel"));
        if (quelle.dokumentId() != null && quelle.quellSeiteId() == null) {
            Dokument dokument = Objects.requireNonNull(daten.dokumente().get(quelle.dokumentId()),
                    "Dokumentquelle fehlt");
            if (!quelle.dokumentId().equals(dokument.id()) || dokument.sha256() == null
                    || !dokument.sha256().matches("[0-9a-fA-F]{64}") || quelle.seiteVon() == null
                    || quelle.seiteVon() < 1 || (quelle.seiteBis() != null && quelle.seiteBis() < quelle.seiteVon())) {
                throw new IllegalArgumentException("Ungültige Dokumentquelle im Wissenspaket");
            }
            ergebnis.put("resource", "urn:urinet-archiv:" + paket.mandant().wert() + ":dokument:"
                    + dokument.id() + "#page=" + quelle.seiteVon());
            ergebnis.put("title", nichtLeer(dokument.titel(), "quellentitel"));
            ergebnis.put("last_modified", dokument.datum() == null ? null : dokument.datum().toString());
            ergebnis.put("sha256", dokument.sha256().toLowerCase(java.util.Locale.ROOT));
            ergebnis.put("seite", quelle.seiteBis() == null || quelle.seiteVon().equals(quelle.seiteBis())
                    ? quelle.seiteVon().toString() : quelle.seiteVon() + "-" + quelle.seiteBis());
            ergebnis.put("auszug", quelle.auszug());
            if (wissensquelle) {
                ergebnis.put("wissensquelle", true);
            }
        } else if (quelle.dokumentId() == null && quelle.quellSeiteId() != null) {
            Seitenverweis seite = Objects.requireNonNull(daten.seiten().get(quelle.quellSeiteId()),
                    "Quellseite fehlt");
            if (!quelle.quellSeiteId().equals(seite.id()) || !paket.pfade().contains(seite.pfad())) {
                throw new IllegalArgumentException("Ungültige Quellseite im Wissenspaket");
            }
            ergebnis.put("resource", "/" + seite.pfad() + ".md");
            ergebnis.put("title", nichtLeer(seite.titel(), "quellentitel"));
            ergebnis.put("last_modified", seite.geaendert() == null ? null : seite.geaendert().toString());
        } else {
            throw new IllegalArgumentException("Ungültiges Quellziel im Wissenspaket");
        }
        return ergebnis;
    }

    private static String index(Map<Seitentyp, List<Indexzeile>> gruppen) {
        StringBuilder text = new StringBuilder(frontmatter(Map.of("okf_version", "0.2"))).append("# Index\n");
        for (Seitentyp typ : Seitentyp.values()) {
            List<Indexzeile> seiten = gruppen.getOrDefault(typ, List.of());
            if (!seiten.isEmpty()) {
                text.append("\n## ").append(typ.datenbankwert()).append("\n\n");
                for (Indexzeile seite : seiten) {
                    text.append("* [").append(markdownText(seite.titel())).append("](/")
                            .append(seite.pfad()).append(".md) - ").append(markdownText(seite.beschreibung()))
                            .append('\n');
                }
            }
        }
        return text.toString();
    }

    private static void log(ZipOutputStream zip, Iterable<Logzeile> zeilen) throws IOException, InterruptedException {
        zip.putNextEntry(new ZipEntry("log.md"));
        schreiben(zip, "# Update Log\n");
        LocalDate letzterTag = null;
        Instant letzteZeit = null;
        for (Logzeile zeile : zeilen) {
            unterbrochen();
            if (letzteZeit != null && zeile.zeit().isAfter(letzteZeit)) {
                throw new IllegalArgumentException("Wissenslog muss neueste Einträge zuerst liefern");
            }
            LocalDate tag = zeile.zeit().atZone(ZoneOffset.UTC).toLocalDate();
            if (!tag.equals(letzterTag)) {
                schreiben(zip, "\n## " + tag + "\n\n");
                letzterTag = tag;
            }
            schreiben(zip, "* **" + markdownText(zeile.aktion()) + "**: " + markdownText(zeile.text()) + "\n");
            letzteZeit = zeile.zeit();
        }
        zip.closeEntry();
    }

    private static String markdownText(String text) {
        return Objects.requireNonNull(text, "text").replace("\\", "\\\\")
                .replace("[", "\\[").replace("]", "\\]").replace("*", "\\*")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String nichtLeer(String wert, String feld) {
        if (wert == null || wert.isBlank()) {
            throw new IllegalArgumentException("Feld fehlt im Wissenspaket: " + feld);
        }
        return wert;
    }

    private static String frontmatter(Map<String, Object> daten) {
        return "---\n" + yaml(daten) + "---\n";
    }

    private static String yaml(Map<String, Object> daten) {
        DumperOptions optionen = new DumperOptions();
        optionen.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        optionen.setAllowUnicode(true);
        return new Yaml(optionen).dump(daten);
    }

    private static void datei(ZipOutputStream zip, String name, String inhalt) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        schreiben(zip, inhalt);
        zip.closeEntry();
    }

    private static void schreiben(OutputStream ziel, String text) throws IOException {
        ziel.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("OKF-Export unterbrochen");
        }
    }

    private static final class NichtSchliessend extends FilterOutputStream {
        private NichtSchliessend(OutputStream ziel) {
            super(ziel);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
