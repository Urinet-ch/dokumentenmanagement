package ch.urinet.archiv.ablage.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port der Ablage im Object Storage (design.md «Ablage»; ADR-011, Req 16.13): Adapter {@code S3Ablage}, Dev-Stub
 * im Dateisystem über {@code archiv.ablage.stub}. Der Bucket folgt aus dem Mandanten ({@code <präfix><code>}).
 * Kein Aufruf setzt den Bypass-Header, keiner verkürzt eine Sperre, keiner überschreibt oder
 * löscht eine Datei unter {@code archiv/} oder {@code manifest/} (Req 6.5).
 */
public interface AblagePort {

    /** Legt eine Datei unter {@code eingang/} ohne Sperre ab (Req 6.2) und liefert Versions-ID und SHA-256. */
    AbgelegteDatei ablegenEingang(MandantCode mandant, DateiSchluessel schluessel, InputStream inhalt, long laenge);

    /**
     * Wie {@link #ablegenEingang(MandantCode, DateiSchluessel, InputStream, long)} für eine Datei, die schon auf der
     * Platte liegt (Upload, Aufgabe 2.4); der S3-Adapter lädt sie ohne weitere Kopie hoch.
     *
     * @throws ch.urinet.archiv.gemeinsam.domain.FachFehler {@code SPEICHER_NICHT_ERREICHBAR}, wenn der Object Storage
     *                                                      nicht antwortet oder mit 5xx ablehnt
     */
    default AbgelegteDatei ablegenEingang(MandantCode mandant, DateiSchluessel schluessel, Path datei) {
        try (InputStream inhalt = Files.newInputStream(datei)) {
            return ablegenEingang(mandant, schluessel, inhalt, Files.size(datei));
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht gelesen werden: " + schluessel.pfad(Ablageort.EINGANG), e);
        }
    }

    /**
     * Kopiert die Dateien eines Dokuments nach {@code archiv/}, vergleicht je Kopie die SHA-256 und sperrt sie nur bei
     * gesetzlicher Pflicht in GOVERNANCE (Req 6.3, 6.9, 6.12). Übernimmt eine vorhandene, korrekte Kopie; löscht eine
     * fehlerhafte, ungesperrte Kopie über ihre Versions-ID. Löscht {@code eingang/} nicht (das macht
     * {@link AblageService} erst nach dem Festhalten).
     *
     * @throws ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen bei abweichender Prüfsumme oder nicht gesetzter
     *                                                              Sperre (Req 6.4)
     */
    ArchiviertesDokument archivieren(MandantCode mandant, List<ZuArchivieren> dateien, Sperrvorgabe vorgabe);

    /**
     * Setzt oder verlängert die Sperre der angegebenen Versionen unter {@code archiv/} nach der Vorgabe (Req 9.7); ein
     * früheres Datum verkürzt nie, sondern ergibt {@code KONFLIKT}.
     */
    List<SperrAenderung> sperreVerlaengern(MandantCode mandant, List<AbgelegteDatei> dateien, Sperrvorgabe vorgabe);

    /** Löscht alle Versionen und Löschmarken unter {@code eingang/<jahr>/<docId>/}; nichts ausserhalb von {@code eingang/}. */
    void loeschenEingang(MandantCode mandant, int jahr, UUID docId);

    /** Download-Adresse nur für GET auf genau diese Versions-ID, höchstens 5 Minuten gültig (Req 14.6). */
    VorsignierteUrl vorsignierteUrl(MandantCode mandant, Ablageort ort, DateiSchluessel schluessel, String versionId);

    /**
     * Legt einen Teil des Tages-Manifests unter {@code manifest/} ab, gesperrt im konfigurierten Modus (Standard und ab
     * Stufe B fest COMPLIANCE, Req 6.11) bis zum spätesten {@code aufbewahren_bis}, ohne Frist 10 Jahre (Req 15.4).
     * Liegt dort schon derselbe Inhalt, gilt er als abgelegt; ein anderer Inhalt bricht ab (ein Manifest je Datum).
     */
    AbgelegteDatei ablegenManifest(MandantCode mandant, LocalDate datum, ManifestTeil teil, byte[] inhalt,
                                   Optional<LocalDate> spaetestesAufbewahrenBis);

    /**
     * Lädt genau diese Version einer Datei aus {@code eingang/} nach {@code ziel} (darf noch nicht bestehen) und prüft
     * ihre SHA-256 gegen die bei Eingang festgehaltene (Regel 3, Aufgabe 2.6); weicht sie ab, löscht der Aufruf
     * {@code ziel} und bricht ab. Die Datei im Object Storage bleibt unverändert.
     */
    void lesenEingang(MandantCode mandant, DateiSchluessel schluessel, String versionId, String sha256, Path ziel);

    /** Legt das Vorschaubild einer Seite unter {@code vorschau/<docId>/<seite>.png} ohne Sperre ab (Aufgabe 2.6). */
    AbgelegteDatei ablegenVorschau(MandantCode mandant, UUID docId, int seite, Path datei);

    /**
     * Liest das Vorschaubild einer Seite (neueste Version unter {@code vorschau/<docId>/<seite>.png}, Aufgabe 3.6);
     * fehlt es, leer. Die Vorschau ist regenerierbar und darum ohne Prüfsumme in der Datenbank. Der Aufrufer schliesst
     * den Datenstrom.
     */
    Optional<InputStream> lesenVorschau(MandantCode mandant, UUID docId, int seite);

    /**
     * Löscht alle Versionen und Löschmarken der abgeleiteten Dateien eines Dokuments, also von
     * {@code eingang/<jahr>/<docId>/pdfa.pdf} und unter {@code vorschau/<docId>/}, ausser den Versionen in
     * {@code behalten} (Schlüssel und Versions-ID). Originale und Arbeitsfassungen berührt der Aufruf nie. So bleiben
     * nach einem erneuten Versuch der Verarbeitung keine doppelten Dateien (Aufgabe 2.6).
     *
     * @return Anzahl gelöschter Versionen und Löschmarken
     */
    default int abgeleiteteBereinigen(MandantCode mandant, int jahr, UUID docId, Collection<AbgelegteDatei> behalten) {
        return abgeleiteteBereinigen(mandant, jahr, docId, behalten, () -> { });
    }

    /**
     * Wie {@link #abgeleiteteBereinigen(MandantCode, int, UUID, Collection)}, aber {@code vorDemLoeschen} läuft nach dem
     * Auflisten und vor dem ersten Löschen, nur wenn es etwas zu löschen gibt. Wirft er, löscht der Aufruf nichts und
     * gibt die Ausnahme weiter. So prüft ein Handler seinen Anspruch, ohne die Sperre über die Aufrufe an den Object
     * Storage zu halten: Was beim Auflisten schon da war, stammt nicht von einem späteren Lauf mit gültigem Anspruch.
     */
    int abgeleiteteBereinigen(MandantCode mandant, int jahr, UUID docId, Collection<AbgelegteDatei> behalten,
                              Runnable vorDemLoeschen);
}
