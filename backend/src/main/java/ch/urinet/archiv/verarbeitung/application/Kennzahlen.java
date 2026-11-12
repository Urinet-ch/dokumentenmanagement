package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kennzahlen je aktiven Mandanten (Req 16.4): offene, laufende und fehlgeschlagene Aufträge, Dokumente je Status ausser
 * den Endzuständen und die Grösse des Eingangskorbs. Nur Zähler, keine Inhalte. Die Korrekturquote je Feld folgt mit
 * den Korrekturen der KI-Vorschläge (Aufgabe 3.3).
 */
@ApplicationScoped
public class Kennzahlen {

    /** Status der Aufträge mit eigener Kennzahl; {@code FERTIG} wächst nur und sagt nichts über den Zustand. */
    public static final List<String> AUFTRAG_STATUS = List.of("OFFEN", "LAEUFT", "FEHLER");

    /** Status der Dokumente mit eigener Kennzahl, ohne die Endzustände {@code ARCHIVIERT} und {@code VERWORFEN}. */
    public static final List<String> DOKUMENT_STATUS = List.of("NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG",
            "ARCHIVIERUNG_FEHLER", "FEHLER");

    private static final Logger LOG = Logger.getLogger(Kennzahlen.class);

    private final MandantRegister register;
    private final AuftragZaehler auftraege;
    private final DokumentUeberwachung dokumente;

    public Kennzahlen(MandantRegister register, AuftragZaehler auftraege, DokumentUeberwachung dokumente) {
        this.register = register;
        this.auftraege = auftraege;
        this.dokumente = dokumente;
    }

    /** Stand eines Mandanten; jeder Status aus {@link #AUFTRAG_STATUS} und {@link #DOKUMENT_STATUS} ist enthalten. */
    public record Stand(MandantCode mandant, Map<String, Long> auftraege, Map<String, Long> dokumente,
                        long eingangskorb) {
    }

    /** Erhebt den Stand je aktiven Mandanten; ein Mandant mit Fehler fehlt in der Liste (WARN im Log). */
    public List<Stand> erheben() {
        List<Stand> stand = new ArrayList<>();
        for (Mandant mandant : register.aktive()) {
            try {
                Map<String, Long> auftrag = vollstaendig(AUFTRAG_STATUS, auftraege.anzahlJeStatus(mandant.code()));
                Map<String, Long> dokument = vollstaendig(DOKUMENT_STATUS,
                        dokumente.anzahlJeStatus(mandant.code()));
                long korb = DokumentStatus.EINGANGSKORB_STATUS.stream()
                        .mapToLong(s -> dokument.getOrDefault(s.name(), 0L)).sum();
                stand.add(new Stand(mandant.code(), auftrag, dokument, korb));
            } catch (RuntimeException e) {
                LOG.warnf("Kennzahlen für Mandant %s nicht erhoben, ursache=%s", mandant.code().wert(),
                        Ursachen.kette(e));
            }
        }
        return stand;
    }

    private static Map<String, Long> vollstaendig(List<String> status, Map<String, Long> anzahl) {
        Map<String, Long> ergebnis = new LinkedHashMap<>();
        status.forEach(s -> ergebnis.put(s, anzahl.getOrDefault(s, 0L)));
        return ergebnis;
    }
}
