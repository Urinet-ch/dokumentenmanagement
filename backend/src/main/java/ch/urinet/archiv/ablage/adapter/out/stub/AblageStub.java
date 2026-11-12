package ch.urinet.archiv.ablage.adapter.out.stub;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.Importdatei;
import ch.urinet.archiv.ablage.domain.Vorschau;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dev-Stub von {@link AblagePort}: Dateisystem statt Object Storage; aktiv mit {@code archiv.ablage.stub=true} (Profil
 * dev). Bildet Versionen und Sperren nach (gesperrte Versionen lassen sich weder löschen noch verkürzen), ohne Höchstfrist
 * und ohne Stufen; die vorsignierte URL ist eine {@code file:}-Adresse. Nie in prod (Startprüfung der Port-Auswahl).
 */
@ApplicationScoped
@Typed(AblageStub.class)
public class AblageStub implements AblagePort {

    private static final Duration URL_GUELTIGKEIT = Duration.ofMinutes(5);

    private final Map<String, List<Version>> versionen = new HashMap<>();
    private Path wurzel;
    private boolean ohneVersionsIdMarker;

    private record Version(String versionId, Path datei, String sha256, long groesse, Instant sperreBis) {

        boolean gesperrt(Instant jetzt) {
            return sperreBis != null && sperreBis.isAfter(jetzt);
        }
    }

    @Override
    public synchronized AbgelegteDatei ablegenEingang(MandantCode mandant, DateiSchluessel schluessel,
                                                      InputStream inhalt, long laenge) {
        try {
            byte[] daten = inhalt.readAllBytes();
            if (laenge >= 0 && daten.length != laenge) {
                throw new IllegalArgumentException("Länge der Datei weicht ab: " + schluessel.pfad(Ablageort.EINGANG));
            }
            Version v = speichern(mandant, schluessel.pfad(Ablageort.EINGANG), daten, null);
            return datei(schluessel.pfad(Ablageort.EINGANG), v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized ArchiviertesDokument archivieren(MandantCode mandant, List<ZuArchivieren> dateien,
                                                         Sperrvorgabe vorgabe) {
        Optional<Instant> bis = Sperrfrist.retainUntil(vorgabe, Instant.now(), Optional.empty());
        List<AbgelegteDatei> abgelegt = new ArrayList<>();
        for (ZuArchivieren d : dateien) {
            String quelle = d.schluessel().pfad(Ablageort.EINGANG);
            String ziel = d.schluessel().pfad(Ablageort.ARCHIV);
            Version v = liste(mandant, quelle).stream()
                    .filter(x -> d.eingangVersionId() == null || x.versionId().equals(d.eingangVersionId()))
                    .reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException("Fehlt im Eingang: " + quelle));
            if (!v.sha256().equals(d.sha256())) {
                throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel);
            }
            Version kopie = liste(mandant, ziel).stream().filter(x -> x.sha256().equals(d.sha256())).findFirst()
                    .orElseGet(() -> speichern(mandant, ziel, lesen(v), null));
            if (bis.isPresent() && (kopie.sperreBis() == null || kopie.sperreBis().isBefore(bis.get()))) {
                kopie = ersetzen(mandant, ziel, kopie, bis.get());
            }
            abgelegt.add(datei(ziel, kopie));
        }
        return ArchiviertesDokument.aus(abgelegt);
    }

    @Override
    public synchronized List<SperrAenderung> sperreVerlaengern(MandantCode mandant, List<AbgelegteDatei> dateien,
                                                               Sperrvorgabe vorgabe) {
        Instant jetzt = Instant.now();
        Optional<Instant> soll = Sperrfrist.retainUntil(vorgabe, jetzt, Optional.empty());
        List<SperrAenderung> aenderungen = new ArrayList<>();
        for (AbgelegteDatei datei : dateien) {
            Version v = liste(mandant, datei.schluessel()).stream()
                    .filter(x -> x.versionId().equals(datei.versionId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Version fehlt: " + datei.schluessel()));
            Optional<Instant> ist = Optional.ofNullable(v.sperreBis()).filter(b -> b.isAfter(jetzt));
            SperrAenderung.Ergebnis ergebnis;
            if (soll.isEmpty()) {
                ergebnis = ist.isPresent() ? SperrAenderung.Ergebnis.UNVERAENDERT : SperrAenderung.Ergebnis.KEINE_SPERRE;
            } else if (ist.isPresent() && ist.get().isAfter(soll.get())) {
                ergebnis = SperrAenderung.Ergebnis.KONFLIKT;
            } else if (ist.isPresent() && ist.get().equals(soll.get())) {
                ergebnis = SperrAenderung.Ergebnis.UNVERAENDERT;
            } else {
                ergebnis = ist.isPresent() ? SperrAenderung.Ergebnis.VERLAENGERT : SperrAenderung.Ergebnis.GESETZT;
                v = ersetzen(mandant, datei.schluessel(), v, soll.get());
                ist = soll;
            }
            aenderungen.add(new SperrAenderung(datei.schluessel(), v.versionId(), ergebnis, ist));
        }
        return aenderungen;
    }

    @Override
    public synchronized void loeschenEingang(MandantCode mandant, int jahr, UUID docId) {
        String ordner = mandant.wert() + "/" + DateiSchluessel.ordner(Ablageort.EINGANG, jahr, docId);
        var eintraege = versionen.entrySet().iterator();
        while (eintraege.hasNext()) {
            var eintrag = eintraege.next();
            if (eintrag.getKey().startsWith(ordner)) {
                eintrag.getValue().forEach(v -> loeschen(v.datei()));
                eintraege.remove();
            }
        }
    }

    @Override
    public synchronized VorsignierteUrl vorsignierteUrl(MandantCode mandant, Ablageort ort, DateiSchluessel schluessel,
                                                        String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("Vorsignierte URL nur mit Versions-ID");
        }
        Version v = liste(mandant, schluessel.pfad(ort)).stream().filter(x -> x.versionId().equals(versionId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Version fehlt: " + schluessel.pfad(ort)));
        return new VorsignierteUrl(v.datei().toUri(), Instant.now().plus(URL_GUELTIGKEIT));
    }

    @Override
    public synchronized AbgelegteDatei ablegenManifest(MandantCode mandant, LocalDate datum, ManifestTeil teil,
                                                       byte[] inhalt, Optional<LocalDate> spaetestesAufbewahrenBis) {
        String key = teil.pfad(datum);
        String sha256 = Pruefsumme.von(inhalt);
        Optional<Version> vorhanden = liste(mandant, key).stream().findFirst();
        if (vorhanden.isPresent()) {
            if (!vorhanden.get().sha256().equals(sha256)) {
                throw new IllegalStateException("Unter " + key + " liegt schon ein anderes Manifest");
            }
            return datei(key, vorhanden.get());
        }
        Instant bis = Sperrfrist.manifest(spaetestesAufbewahrenBis, Instant.now(), Optional.empty());
        return datei(key, speichern(mandant, key, inhalt, bis));
    }

    @Override
    public synchronized Optional<ManifestDatei> lesenManifest(MandantCode mandant, LocalDate datum, ManifestTeil teil) {
        String key = teil.pfad(datum);
        List<Version> vorhanden = liste(mandant, key);
        if (vorhanden.isEmpty()) {
            return Optional.empty();
        }
        Version v = vorhanden.getLast();
        if (v.groesse() > MANIFEST_MAX_GROESSE) {
            throw new IllegalStateException("Manifest-Teil zu gross: " + key);
        }
        return Optional.of(new ManifestDatei(lesen(v), datei(key, v)));
    }

    @Override
    public synchronized void lesenEingang(MandantCode mandant, DateiSchluessel schluessel, String versionId,
                                          String sha256, Path ziel) {
        String key = schluessel.pfad(Ablageort.EINGANG);
        Version v = liste(mandant, key).stream().filter(x -> x.versionId().equals(versionId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Version fehlt: " + key));
        if (!v.sha256().equals(sha256)) {
            throw new IllegalStateException("Prüfsumme weicht ab: " + key);
        }
        try {
            Files.copy(v.datei(), ziel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized AbgelegteDatei ablegenVorschau(MandantCode mandant, UUID docId, int seite, Path datei) {
        String key = Vorschau.schluessel(docId, seite);
        try {
            return datei(key, speichern(mandant, key, Files.readAllBytes(datei), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized Optional<InputStream> lesenVorschau(MandantCode mandant, UUID docId, int seite) {
        List<Version> liste = liste(mandant, Vorschau.schluessel(docId, seite));
        return liste.isEmpty() ? Optional.empty() : Optional.of(new ByteArrayInputStream(lesen(liste.getLast())));
    }

    @Override
    public synchronized AbgelegteDatei ablegenImport(MandantCode mandant, UUID laufId, Path datei) {
        String key = Importdatei.schluessel(laufId);
        try {
            return datei(key, speichern(mandant, key, Files.readAllBytes(datei), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized int abgeleiteteBereinigen(MandantCode mandant, int jahr, UUID docId,
                                                  Collection<AbgelegteDatei> behalten, Runnable vorDemLoeschen) {
        String pdfa = mandant.wert() + "/" + DateiSchluessel.pdfa(jahr, docId).pfad(Ablageort.EINGANG);
        String vorschau = mandant.wert() + "/" + Vorschau.ordner(docId);
        int anzahl = 0;
        for (var eintrag : versionen.entrySet()) {
            String key = eintrag.getKey();
            if (!key.equals(pdfa) && !key.startsWith(vorschau)) {
                continue;
            }
            String schluessel = key.substring(mandant.wert().length() + 1);
            var iterator = eintrag.getValue().iterator();
            while (iterator.hasNext()) {
                Version v = iterator.next();
                boolean bleibt = behalten.stream().anyMatch(d -> d.schluessel().equals(schluessel)
                        && d.versionId().equals(v.versionId()));
                if (!bleibt) {
                    if (anzahl == 0) {
                        vorDemLoeschen.run();
                    }
                    loeschen(v.datei());
                    iterator.remove();
                    anzahl++;
                }
            }
        }
        return anzahl;
    }

    @Override
    public synchronized Optional<Dateipruefung> pruefen(MandantCode mandant, String schluessel, String versionId) {
        return liste(mandant, schluessel).stream().filter(v -> v.versionId().equals(versionId)).findFirst()
                .filter(v -> Files.exists(v.datei()))
                .map(v -> {
                    byte[] daten = lesen(v);
                    return new Dateipruefung(Pruefsumme.von(daten), daten.length, Optional.ofNullable(v.sperreBis()));
                });
    }

    @Override
    public synchronized Versionsseite versionen(MandantCode mandant, String praefix, Optional<Versionsseite.Marke> ab,
                                                int anzahl) {
        String vorsatz = mandant.wert() + "/";
        List<Objektversion> alle = new ArrayList<>();
        versionen.keySet().stream().filter(k -> k.startsWith(vorsatz + praefix)).sorted().forEach(k ->
                versionen.get(k).forEach(v -> alle.add(new Objektversion(k.substring(vorsatz.length()), v.versionId(),
                        false))));
        // wie S3 mit key-marker und version-id-marker: nach genau dieser Version; ohne Versions-ID kein Fortsetzen
        int start = ab.map(m -> {
            String versionId = m.versionIdZumFortsetzen(praefix);
            for (int i = 0; i < alle.size(); i++) {
                Objektversion o = alle.get(i);
                if (o.schluessel().equals(m.schluessel()) && o.versionId().equals(versionId)) {
                    return i + 1;
                }
            }
            return alle.size();
        }).orElse(0);
        int ende = Math.min(alle.size(), start + anzahl);
        List<Objektversion> seite = alle.subList(start, ende);
        boolean abgeschnitten = ende < alle.size() && !seite.isEmpty();
        return new Versionsseite(seite, Versionsseite.weiter(praefix, abgeschnitten,
                abgeschnitten ? seite.getLast().schluessel() : null,
                abgeschnitten && !ohneVersionsIdMarker ? seite.getLast().versionId() : null));
    }

    /** Nur für Tests: abgeschnittene Seiten ohne {@code NextVersionIdMarker}, wie ihn ein Object Storage weglassen kann. */
    synchronized void ohneVersionsIdMarker(boolean ohne) {
        this.ohneVersionsIdMarker = ohne;
    }

    @Override
    public synchronized SperrAenderung sperreVerlaengernBis(MandantCode mandant, String schluessel, String versionId,
                                                            Instant bis) {
        Instant jetzt = Instant.now();
        Version v = liste(mandant, schluessel).stream().filter(x -> x.versionId().equals(versionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version fehlt: " + schluessel));
        if (!v.gesperrt(jetzt)) {
            return new SperrAenderung(schluessel, versionId, SperrAenderung.Ergebnis.KEINE_SPERRE, Optional.empty());
        }
        if (!bis.isAfter(v.sperreBis())) {
            return new SperrAenderung(schluessel, versionId, SperrAenderung.Ergebnis.UNVERAENDERT,
                    Optional.of(v.sperreBis()));
        }
        ersetzen(mandant, schluessel, v, bis);
        return new SperrAenderung(schluessel, versionId, SperrAenderung.Ergebnis.VERLAENGERT, Optional.of(bis));
    }

    private Version speichern(MandantCode mandant, String key, byte[] daten, Instant sperreBis) {
        try {
            if (wurzel == null) {
                wurzel = Files.createTempDirectory("archiv-ablage-stub-");
            }
            String versionId = UUID.randomUUID().toString();
            Path datei = wurzel.resolve(versionId);
            Files.write(datei, daten);
            Version v = new Version(versionId, datei, Pruefsumme.von(daten), daten.length, sperreBis);
            versionen.computeIfAbsent(mandant.wert() + "/" + key, k -> new ArrayList<>()).add(v);
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ersetzt die Sperre einer Version; nur verlängern, nie verkürzen. */
    private Version ersetzen(MandantCode mandant, String key, Version alt, Instant bis) {
        if (alt.sperreBis() != null && alt.sperreBis().isAfter(bis)) {
            throw new IllegalStateException("Sperre lässt sich nicht verkürzen: " + key);
        }
        Version neu = new Version(alt.versionId(), alt.datei(), alt.sha256(), alt.groesse(), bis);
        List<Version> liste = versionen.get(mandant.wert() + "/" + key);
        liste.set(liste.indexOf(alt), neu);
        return neu;
    }

    private List<Version> liste(MandantCode mandant, String key) {
        return versionen.getOrDefault(mandant.wert() + "/" + key, List.of());
    }

    private static byte[] lesen(Version v) {
        try {
            return Files.readAllBytes(v.datei());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void loeschen(Path datei) {
        try {
            Files.deleteIfExists(datei);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AbgelegteDatei datei(String key, Version v) {
        return new AbgelegteDatei(key, v.versionId(), v.sha256(), v.groesse(), Optional.ofNullable(v.sperreBis()));
    }
}
