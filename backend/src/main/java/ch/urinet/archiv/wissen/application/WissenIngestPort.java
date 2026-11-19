package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.Wissensseite;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Driven Port für die Vorprüfung und Warteschlange eines Ingests im aktuellen Mandantenschema. */
public interface WissenIngestPort {

    Optional<Quelle> quelleLaden(UUID dokumentId);

    /** Ein Ingest-Entwurf des Dokuments läuft oder wartet ({@code IN_ARBEIT}, {@code WARTEND}; Req 5.10). */
    boolean entwurfFuerDokumentLaufend(UUID dokumentId);

    /**
     * Die Warteschlange des Mandanten ist belegt: ein Ingest-Entwurf ist aktiv ({@code IN_ARBEIT}, {@code OFFEN}) oder
     * wartet ({@code WARTEND}). Ein neuer Start reiht sich dann hinten ein und überholt keinen wartenden (Req 5.13).
     */
    boolean warteschlangeBelegt();

    UUID entwurfAnlegen(NeuerEntwurf entwurf);

    /** Summe der Tokens aus {@code ki_verbrauch} mit Zweck {@code WISSEN_*} im Zeitraum [von, bis) (Req 13.6). */
    long verbrauchImZeitraum(Instant von, Instant bis);

    BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens, long monatsbudgetTokens,
                                         Instant von, Instant bis);

    void budgetFreigeben(UUID reservierungId);

    Optional<Lauf> laufLaden(UUID entwurfId);

    List<SeitenMetadaten> seitenMetadatenLaden();

    Set<UUID> personalDokumenteLaden(Set<UUID> dokumentIds);

    Map<UUID, WissensPruefung.DokumentQuelle> dokumentQuellenLaden(Set<UUID> dokumentIds);

    /**
     * Sucht die sichtbaren Seiten zu den Texten der Kernaussagen; jeder Begriff zählt für sich (ODER), das Ranking
     * ordnet nach Übereinstimmung (Req 5.4).
     */
    List<SeitenMetadaten> seitenIndexLaden(Set<UUID> seiteIds, String suchtext);

    /** Die Seite, die auf die Wissensquelle verweist ({@code wissensquelle_id}, höchstens eine je Quelle). */
    Optional<UUID> quelleSeiteFinden(UUID dokumentId);

    /**
     * Höchste Nummer der Quellenschlüssel {@code q-<kurz>-<nr>} dieser Wissensquelle über alle Seiten und alle
     * Änderungsvorschläge des Mandanten, sonst 0. Ein neuer Ingest zählt danach weiter, damit ein Schlüssel nie für
     * eine andere Aussage wiederverwendet wird (Req 5.2, 5.3).
     */
    long hoechsteQuellenNummer(UUID dokumentId);

    Optional<SeitenFassung> seiteLaden(UUID seiteId);

    void aenderungAnlegen(NeueAenderung aenderung);

    void fortschrittSichern(UUID entwurfId, String erwarteterSchritt, String neuerSchritt, Fortschritt fortschritt,
                            long tokensEin, long tokensAus, boolean unvollstaendig, String hinweis);

    void entwurfSchliessen(UUID entwurfId, String erwarteterSchritt, String status, Fortschritt fortschritt,
                           long tokensEin, long tokensAus, boolean unvollstaendig, String hinweis);

    int handlerFehltNachholen(String hinweis);

    Optional<UUID> wartendenAktivieren(long monatsbudgetTokens, Instant von, Instant bis);

    record Quelle(String art, String status, String vertraulichkeit, boolean bestaetigt, String volltext, Integer seiten,
                  boolean pdfaVorhanden, Integer budgetTokens, Integer monatsbudgetTokens) {
    }

    record NeuerEntwurf(UUID id, UUID dokumentId, Integer seiteVon, Integer seiteBis, String status,
                        String schritt, String modell, String erstelltVon, String hinweis) {
    }

    record BudgetReservierung(UUID id, boolean monatsbudgetErschoepft) {
        public boolean reserviert() {
            return id != null;
        }
    }

    record Lauf(UUID entwurfId, UUID dokumentId, String entwurfStatus, String dokumentStatus, String art,
                String quelleVertraulichkeit,
                boolean bestaetigt, String titel, LocalDate datum, String volltext, Integer seiten,
                boolean pdfaVorhanden, Integer seiteVon, Integer seiteBis, String schritt, int tokensEin,
                int tokensAus, String modell, boolean unvollstaendig, Integer budgetTokens, Integer monatsbudgetTokens,
                Fortschritt fortschritt) {
    }

    /**
     * {@code kontextStufe}: höchste Vertraulichkeit der Wissensquelle, die bisher in einem Schritt in den Kontext
     * gelangt ist (Req 11.7); {@code null} vor dem ersten Schritt.
     */
    record Fortschritt(int abschnitt, int seite, boolean quelleErzeugt, List<Kernaussage> kernaussagen,
                       List<Planung> planung, List<SeitenVorschlag> vorschlaege, List<String> hinweise,
                       WissensStufe kontextStufe) {
        public Fortschritt {
            kernaussagen = kernaussagen == null ? List.of() : List.copyOf(kernaussagen);
            planung = planung == null ? List.of() : List.copyOf(planung);
            vorschlaege = vorschlaege == null ? List.of() : List.copyOf(vorschlaege);
            hinweise = hinweise == null ? List.of() : List.copyOf(hinweise);
        }

        public static Fortschritt leer() {
            return new Fortschritt(0, 0, false, List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    record Kernaussage(int nr, String schluessel, String text, List<Integer> seiten, String auszug, boolean belegt) {
        public Kernaussage {
            seiten = seiten == null ? List.of() : List.copyOf(seiten);
        }
    }

    record Planung(String pfad, String aktion, String typ, String grund, List<Integer> aussagen,
                   UUID seiteId, Integer basisVersion, boolean pfadHoehereStufe) {
        public Planung {
            aussagen = aussagen == null ? List.of() : List.copyOf(aussagen);
        }
    }

    record SeitenVorschlag(int nummer, String aktion, UUID seiteId, String pfad, Integer basisVersion,
                           Wissensseite daten, String begruendung, List<KiPort.Widerspruch> widersprueche,
                           List<KiPort.EntfernteAussage> entfernteAussagen,
                           List<WissensPruefung.Befund> befunde, List<WissensPruefung.Hinweis> hinweise,
                           String vertraulichkeitKontext) {
        public SeitenVorschlag {
            widersprueche = widersprueche == null ? List.of() : List.copyOf(widersprueche);
            entfernteAussagen = entfernteAussagen == null ? List.of() : List.copyOf(entfernteAussagen);
            befunde = befunde == null ? List.of() : List.copyOf(befunde);
            hinweise = hinweise == null ? List.of() : List.copyOf(hinweise);
        }
    }

    record SeitenMetadaten(UUID id, String pfad, String typ, String titel, String beschreibung, List<String> tags,
                           String status, int version, String vertraulichkeitMindest) {
        public SeitenMetadaten {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    record SeitenFassung(SeitenMetadaten metadaten, String inhalt, UUID wissensquelleId,
                         List<Quelleneintrag> quellen) {
        public SeitenFassung {
            quellen = quellen == null ? List.of() : List.copyOf(quellen);
        }
    }

    record NeueAenderung(UUID entwurfId, int nummer, String aktion, UUID seiteId, String pfad, Integer basisVersion,
                         Wissensseite daten, String begruendung, List<KiPort.Widerspruch> widersprueche,
                         List<KiPort.EntfernteAussage> entfernteAussagen, List<WissensPruefung.Befund> befunde,
                         List<WissensPruefung.Hinweis> hinweise, String diff, String vertraulichkeitKontext) {
        public NeueAenderung {
            widersprueche = widersprueche == null ? List.of() : List.copyOf(widersprueche);
            entfernteAussagen = entfernteAussagen == null ? List.of() : List.copyOf(entfernteAussagen);
            befunde = befunde == null ? List.of() : List.copyOf(befunde);
            hinweise = hinweise == null ? List.of() : List.copyOf(hinweise);
        }
    }
}
