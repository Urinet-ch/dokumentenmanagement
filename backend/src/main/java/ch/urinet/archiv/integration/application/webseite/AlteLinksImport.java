package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.AlteUrl;
import ch.urinet.archiv.importe.application.CsvLeser;
import ch.urinet.archiv.importe.application.ImportEinstellungen;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Importiert die CSV-Zuordnung alter URLs im aktiven Mandanten. Der bestehende CSV-Leser liefert begrenzte Datensätze
 * und erkennt Zeichensatz sowie Trennzeichen. Der Datenbank-Port schreibt erst, wenn alle Zeilen gültig sind.
 */
@ApplicationScoped
public class AlteLinksImport {

    private final CsvLeser leser;
    private final AlteLinksImportPort port;
    private final ImportEinstellungen einstellungen;
    private final ZugriffProtokoll protokoll;

    public AlteLinksImport(CsvLeser leser, AlteLinksImportPort port, ImportEinstellungen einstellungen,
                          ZugriffProtokoll protokoll) {
        this.leser = leser;
        this.port = port;
        this.einstellungen = einstellungen;
        this.protokoll = protokoll;
    }

    public enum Status {
        ANGELEGT, SCHON_VORHANDEN, ABGELEHNT
    }

    public record ZeilenErgebnis(int zeile, String url, Status status, String grund) {
    }

    public record Bericht(List<ZeilenErgebnis> zeilen, int angelegt, int schonVorhanden, int abgelehnt) {
        public Bericht {
            zeilen = List.copyOf(zeilen);
        }
    }

    /** Anlass des protokollierten Admin-Kommandos. */
    public record Anlass(String wer, String grund) {
        public Anlass {
            if (wer == null || !wer.strip().equals(wer) || !wer.matches("[^\\p{Cntrl}]{1,80}")) {
                throw new IllegalArgumentException("--wer fehlt oder ist ungültig");
            }
            if (grund == null || !grund.strip().equals(grund) || !grund.matches("[^\\p{Cntrl}]{1,200}")) {
                throw new IllegalArgumentException("--grund fehlt oder ist ungültig");
            }
        }
    }

    private static final int FELD_GRENZE = AlteUrl.MAX_LAENGE;
    private static final String ZEILE_ABGELEHNT = "Import abgebrochen, weil mindestens eine Zeile abgelehnt wurde";

    @Transactional
    public Bericht importieren(Path datei, boolean ausfuehren, Anlass anlass) {
        Objects.requireNonNull(datei, "datei");
        if (ausfuehren && anlass == null) {
            throw new IllegalArgumentException("Für die Ausführung sind --wer und --grund erforderlich");
        }
        if (groesse(datei) > einstellungen.maxGroesse()) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }

        Sammeln sammlung = new Sammeln();
        leser.lesen(datei, Math.min(FELD_GRENZE, einstellungen.feldGrenze()), sammlung);
        if (sammlung.kandidaten.isEmpty()) {
            throw new FachFehler(Fehlercode.CSV_LEER);
        }
        doppelteUrlsAblehnen(sammlung.kandidaten);

        List<Kandidat> gueltig = sammlung.kandidaten.stream().filter(k -> k.grund == null).toList();
        if (!gueltig.isEmpty()) {
            pruefen(gueltig);
        }

        if (sammlung.kandidaten.stream().anyMatch(k -> k.grund != null)) {
            sammlung.kandidaten.stream().filter(k -> k.grund == null && k.status == null)
                    .forEach(k -> k.ablehnen(ZEILE_ABGELEHNT));
            return bericht(sammlung.kandidaten);
        }

        List<Kandidat> neu = sammlung.kandidaten.stream().filter(k -> k.status == null).toList();
        if (ausfuehren && !neu.isEmpty()) {
            port.anlegen(neu.stream().map(k -> new AlteLinksImportPort.Zuordnung(k.url, k.dokumentId)).toList());
            protokoll.schreiben(neu.stream().map(k -> new ZugriffEintrag(
                    Optional.of("cli:" + anlass.wer()), Optional.empty(), ProtokollAktion.AENDERN,
                    Optional.of(k.dokumentId), Optional.empty(), new ProtokollDetails(anlass.grund(), null, null)))
                    .toList());
        }
        return bericht(sammlung.kandidaten);
    }

    private long groesse(Path datei) {
        try {
            return Files.size(datei);
        } catch (IOException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
    }

    private void pruefen(List<Kandidat> kandidaten) {
        List<UUID> ids = kandidaten.stream().map(k -> k.dokumentId).distinct().toList();
        Map<UUID, String> status = port.dokumentstatus(ids);
        Set<String> urls = new LinkedHashSet<>();
        kandidaten.forEach(k -> urls.add(k.url));
        Map<String, UUID> vorhanden = port.zuordnungen(urls);
        for (Kandidat kandidat : kandidaten) {
            String dokumentstatus = status.get(kandidat.dokumentId);
            if (dokumentstatus == null) {
                kandidat.ablehnen("Zieldokument existiert nicht im Mandanten");
            } else if (!"ARCHIVIERT".equals(dokumentstatus)) {
                kandidat.ablehnen("Zieldokument ist nicht archiviert");
            } else {
                UUID zugeordnet = vorhanden.get(kandidat.url);
                if (zugeordnet == null) {
                    continue;
                }
                if (zugeordnet.equals(kandidat.dokumentId)) {
                    kandidat.status = Status.SCHON_VORHANDEN;
                } else {
                    kandidat.ablehnen("Alte URL ist bereits einem anderen Dokument zugeordnet");
                }
            }
        }
    }

    private static void doppelteUrlsAblehnen(List<Kandidat> kandidaten) {
        Map<String, List<Kandidat>> nachUrl = new LinkedHashMap<>();
        kandidaten.stream().filter(k -> k.grund == null)
                .forEach(k -> nachUrl.computeIfAbsent(k.url, ignoriert -> new ArrayList<>()).add(k));
        nachUrl.values().stream().filter(gleiche -> gleiche.size() > 1)
                .forEach(gleiche -> gleiche.forEach(k -> k.ablehnen("Alte URL kommt mehrfach in der Datei vor")));
    }

    private static Bericht bericht(List<Kandidat> kandidaten) {
        List<ZeilenErgebnis> ergebnisse = kandidaten.stream().map(Kandidat::ergebnis).toList();
        int angelegt = (int) ergebnisse.stream().filter(e -> e.status() == Status.ANGELEGT).count();
        int schonVorhanden = (int) ergebnisse.stream().filter(e -> e.status() == Status.SCHON_VORHANDEN).count();
        int abgelehnt = (int) ergebnisse.stream().filter(e -> e.status() == Status.ABGELEHNT).count();
        return new Bericht(ergebnisse, angelegt, schonVorhanden, abgelehnt);
    }

    private final class Sammeln implements CsvLeser.Empfaenger {
        private final List<Kandidat> kandidaten = new ArrayList<>();
        private int urlPosition = -1;
        private int dokumentPosition = -1;

        @Override
        public void kopf(String zeichensatz, char trennzeichen, Kopfzeile kopf) {
            urlPosition = kopf.position("alte_url");
            dokumentPosition = kopf.position("dokument_id");
            if (kopf.spalten() != 2 || urlPosition < 0 || dokumentPosition < 0 || urlPosition == dokumentPosition) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
        }

        @Override
        public void zeile(CsvTabelle.Zeile zeile) {
            if (zeile.zuLang()) {
                kandidaten.add(new Kandidat(zeile.nr(), null, null, "Zeile überschreitet die zulässige Länge"));
                return;
            }
            if (zeile.werte().size() != 2) {
                kandidaten.add(new Kandidat(zeile.nr(), null, null, "Zeile muss genau zwei Spalten enthalten"));
                return;
            }
            String url;
            try {
                url = AlteUrl.normalisieren(zeile.werte().get(urlPosition));
            } catch (FachFehler e) {
                kandidaten.add(new Kandidat(zeile.nr(), null, null, "alte_url ist ungültig"));
                return;
            }
            UUID dokumentId;
            try {
                String text = zeile.werte().get(dokumentPosition);
                dokumentId = UUID.fromString(text);
                if (!dokumentId.toString().equals(text)) {
                    throw new IllegalArgumentException("Nicht kanonische UUID");
                }
            } catch (IllegalArgumentException e) {
                kandidaten.add(new Kandidat(zeile.nr(), url, null, "dokument_id ist ungültig"));
                return;
            }
            kandidaten.add(new Kandidat(zeile.nr(), url, dokumentId, null));
        }
    }

    private static final class Kandidat {
        private final int zeile;
        private final String url;
        private final UUID dokumentId;
        private Status status;
        private String grund;

        private Kandidat(int zeile, String url, UUID dokumentId, String grund) {
            this.zeile = zeile;
            this.url = url;
            this.dokumentId = dokumentId;
            this.grund = grund;
            if (grund != null) {
                status = Status.ABGELEHNT;
            }
        }

        private void ablehnen(String meldung) {
            status = Status.ABGELEHNT;
            grund = meldung;
        }

        private ZeilenErgebnis ergebnis() {
            return new ZeilenErgebnis(zeile, url, status == null ? Status.ANGELEGT : status, grund);
        }
    }
}
