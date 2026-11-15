package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.AblageGestoert;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.MehrdeutigeVersion;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.out.json.JacksonJsonLeser;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich.Eintraege;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich.Eintrag;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich.Fenster;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich.Manifest;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich.Unbrauchbar;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manifest-Vergleich des Prüflaufs ohne Quarkus (Req 15.5): Einträge aus der ältesten Version der JSON, unbrauchbar bei
 * fehlender, mehrdeutiger, nicht kanonischer oder ungültiger JSON, bei anderer SHA-256 als in der Tabelle und bei
 * anderem Datum oder Fenster; die Versions-ID ist nur Zusatz.
 */
class ManifestAbgleichTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final LocalDate DATUM = LocalDate.parse("2026-11-30");
    private static final ManifestFenster FENSTER = ManifestFenster.fuer(DATUM, ManifestFenster.bis(DATUM.minusDays(1)));
    private static final UUID DOKUMENT = UUID.fromString("0b6f0a52-6d1e-4f43-9a8e-3a2d5c0e7f11");
    private static final JacksonJsonLeser LESER = new JacksonJsonLeser();

    @Test
    void liefertDieEintraegeOhneVersionsId() {
        byte[] json = manifest(List.of(new ManifestEintrag(DOKUMENT, "original", 1, "a".repeat(64), "v-vor-der-wiederherstellung"),
                new ManifestEintrag(DOKUMENT, "pdfa", 1, "b".repeat(64), null)));

        Manifest ergebnis = abgleich(() -> Optional.of(datei(json))).lesen(MANDANT, fenster(json));

        assertThat(ergebnis).isEqualTo(new Eintraege(DATUM, Map.of(new Eintrag(DOKUMENT, "original", 1), "a".repeat(64),
                new Eintrag(DOKUMENT, "pdfa", 1), "b".repeat(64))));
    }

    @Test
    void fehlendeOderMehrdeutigeJsonIstUnbrauchbar() {
        byte[] json = manifest(List.of());
        assertThat(abgleich(Optional::empty).lesen(MANDANT, fenster(json)))
                .isEqualTo(new Unbrauchbar(DATUM, "JSON unter manifest/ fehlt"));
        assertThat(abgleich(() -> {
            throw new MehrdeutigeVersion("mehrere Versionen mit gleichem Zeitpunkt");
        }).lesen(MANDANT, fenster(json))).isEqualTo(new Unbrauchbar(DATUM, "älteste Version nicht eindeutig"));
    }

    @Test
    void nichtKanonischUngueltigOderAndereSha256IstUnbrauchbar() {
        byte[] json = manifest(List.of(new ManifestEintrag(DOKUMENT, "original", 1, "a".repeat(64), "v1")));
        byte[] nichtKanonisch = (" " + new String(json, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT, fenster(nichtKanonisch), nichtKanonisch))
                .isInstanceOf(Unbrauchbar.class).extracting(m -> ((Unbrauchbar) m).grund()).asString()
                .contains("kanonisch");
        byte[] ungueltig = "{\"datum\":\"2026-11-30\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT, fenster(ungueltig), ungueltig))
                .isInstanceOf(Unbrauchbar.class).extracting(m -> ((Unbrauchbar) m).grund()).asString()
                .contains("ungültig");
        Fenster andereSha = new Fenster(DATUM, Optional.of(FENSTER.von()), FENSTER.bis(), "c".repeat(64));
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT, andereSha, json))
                .isEqualTo(new Unbrauchbar(DATUM, "SHA-256 passt nicht zur Tabelle manifest"));
    }

    @Test
    void anderesDatumFensterOderAndererMandantIstUnbrauchbar() {
        byte[] json = manifest(List.of());
        String sha = Sha256.hexVon(json);
        Unbrauchbar erwartet = new Unbrauchbar(DATUM, "Mandant, Datum oder Fenster passt nicht zur Tabelle manifest");
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT,
                new Fenster(DATUM, Optional.empty(), FENSTER.bis(), sha), json)).isEqualTo(erwartet);
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT,
                new Fenster(DATUM, Optional.of(FENSTER.von()), FENSTER.bis().plusSeconds(1), sha), json))
                .isEqualTo(erwartet);
        assertThat(ManifestAbgleich.auswerten(LESER, new MandantCode("andere"), fenster(json), json))
                .isEqualTo(erwartet);
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT,
                new Fenster(DATUM.plusDays(1), Optional.of(FENSTER.von()), FENSTER.bis(), sha), json))
                .isEqualTo(new Unbrauchbar(DATUM.plusDays(1), erwartet.grund()));
    }

    @Test
    void doppelterEintragIstUnbrauchbar() {
        ManifestEintrag e = new ManifestEintrag(DOKUMENT, "original", 1, "a".repeat(64), "v1");
        byte[] json = manifest(List.of(e, e));
        assertThat(ManifestAbgleich.auswerten(LESER, MANDANT, fenster(json), json))
                .isEqualTo(new Unbrauchbar(DATUM, "Eintrag doppelt"));
    }

    @Test
    void voruebergehendeStoerungDerAblageGehtAnDenPrueflauf() {
        byte[] json = manifest(List.of());
        AblageGestoert gestoert = new AblageGestoert("manifest/2026-11-30.json", new RuntimeException("503"));
        assertThatThrownBy(() -> abgleich(() -> {
            throw gestoert;
        }).lesen(MANDANT, fenster(json))).isSameAs(gestoert);
    }

    private static byte[] manifest(List<ManifestEintrag> eintraege) {
        return new ManifestInhalt(MANDANT.wert(), DATUM, FENSTER.von(), FENSTER.bis(), "d".repeat(64),
                new ch.urinet.archiv.protokoll.domain.Vorher("e".repeat(64), List.of()), eintraege, new TreeMap<>(),
                List.of()).kanonisch();
    }

    private static Fenster fenster(byte[] json) {
        return new Fenster(DATUM, Optional.of(FENSTER.von()), FENSTER.bis(), Sha256.hexVon(json));
    }

    private static ManifestDatei datei(byte[] json) {
        return new ManifestDatei(json, new AbgelegteDatei(ManifestTeil.JSON.pfad(DATUM), "m1", Sha256.hexVon(json),
                json.length, Optional.empty()));
    }

    private static ManifestAbgleich abgleich(Supplier<Optional<ManifestDatei>> lesen) {
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(ManifestAbgleichTest.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                    if (methode.getName().equals("lesenManifest") && argumente[2].equals(ManifestTeil.JSON)) {
                        return lesen.get();
                    }
                    throw new UnsupportedOperationException(methode.getName());
                });
        return new ManifestAbgleich(ablage, LESER, null, null);
    }
}
