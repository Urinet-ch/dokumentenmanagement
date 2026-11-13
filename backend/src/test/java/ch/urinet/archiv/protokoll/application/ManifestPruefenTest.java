package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.adapter.out.stub.AblageStub;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.out.json.JacksonJsonLeser;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.protokoll.domain.Pruefbericht;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.Stempel;
import ch.urinet.archiv.protokoll.domain.Vorher;
import ch.urinet.archiv.protokoll.domain.ZeitstempelVerweis;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prüfwerkzeug {@code archiv manifest pruefen} (Req 15.12, Nachweis: JUnit): Signatur, Hash-Kette, Zeitstempel,
 * Erneuerung nach 15.2, Lücke, fehlender Zeitstempel und Verspätung. Ablage im Speicher ({@link AblageStub}),
 * Zeitstempel als Attrappe; die echte RFC-3161-Prüfung deckt {@code BcZeitstempelAdapterTest}.
 */
class ManifestPruefenTest {

    private static final MandantCode MANDANT = new MandantCode("probe");
    private static final LocalDate TAG1 = LocalDate.of(2026, 1, 10);
    private static final LocalDate TAG2 = TAG1.plusDays(1);
    private static final LocalDate TAG3 = TAG1.plusDays(2);
    private static final List<String> BEIDE = List.of("haupt", "zweit");
    private static final Optional<LocalDate> SPERRE = Optional.of(LocalDate.of(2040, 1, 1));

    private final KeyPair paar = ed25519();
    private final AblageStub stub = new AblageStub();
    private final Map<String, byte[]> ersetzt = new HashMap<>();
    private final Set<String> entfernt = new HashSet<>();
    private final Map<String, Integer> weitere = new HashMap<>();
    private final Set<String> ungesperrt = new HashSet<>();
    private final AblagePort ablage = manipulierbar();
    private List<String> konfiguriert = BEIDE;

    private record Stand(LocalDate datum, Instant bis, String sha256, Vorher verweis) {
    }

    @Test
    void eineVollstaendigeKetteIstGueltig() {
        kette(3, BEIDE, Duration.ofMinutes(20));

        Pruefbericht bericht = pruefen(TAG1, TAG3);

        assertThat(bericht.fehler()).isEmpty();
        assertThat(bericht.meldungen()).isEmpty();
        assertThat(bericht.anzahlGeprueft()).isEqualTo(3);
    }

    @Test
    void eineLueckeInDerKetteIstEinFehler() {
        kette(3, BEIDE, Duration.ofMinutes(20));
        entfernt.add(ManifestTeil.JSON.pfad(TAG2));

        Pruefbericht bericht = pruefen(TAG1, TAG3);

        assertThat(bericht.ok()).isFalse();
        assertThat(bericht.fehler()).contains("2026-01-11: Manifest fehlt (Lücke in der Kette)",
                "2026-01-12: Lücke in der Kette, Manifest 2026-01-11 fehlt");
    }

    @Test
    void eineNeuBegonneneKetteTrotzVortagIstEinFehler() {
        ablegen(TAG1, null, BEIDE, Duration.ofMinutes(20), List.of());
        ablegen(TAG2, null, BEIDE, Duration.ofMinutes(20), List.of());

        assertThat(pruefen(TAG1, TAG2).fehler())
                .contains("2026-01-11: Kette neu begonnen, obwohl das Manifest 2026-01-10 vorliegt");
    }

    @Test
    void eineUngueltigeSignaturIstEinFehlerUndBrichtDieErneuerung() {
        kette(2, BEIDE, Duration.ofMinutes(20));
        ersetzt.put(ManifestTeil.SIG.pfad(TAG1), ManifestSignatur.signieren(ed25519().getPrivate(),
                ablage.lesenManifest(MANDANT, TAG1, ManifestTeil.JSON).orElseThrow().inhalt()));

        Pruefbericht bericht = pruefen(TAG1, TAG2);

        assertThat(bericht.fehler()).contains("2026-01-10: Signatur ungültig",
                "2026-01-11: Erneuerung: Signatur von 2026-01-10 passt nicht");
    }

    @Test
    void einGeaendertesManifestBrichtDieKetteAuchMitGueltigerSignatur() {
        kette(2, BEIDE, Duration.ofMinutes(20));
        ManifestFenster fenster = ManifestFenster.fuer(TAG1, null);
        byte[] anders = new ManifestInhalt(MANDANT.wert(), TAG1, null, fenster.bis(), null, null,
                List.of(new ManifestEintrag(UUID.randomUUID(), "original", 1, "a".repeat(64), "v1")), new TreeMap<>(),
                List.of()).kanonisch();
        ersetzt.put(ManifestTeil.JSON.pfad(TAG1), anders);
        ersetzt.put(ManifestTeil.SIG.pfad(TAG1), ManifestSignatur.signieren(paar.getPrivate(), anders));

        Pruefbericht bericht = pruefen(TAG1, TAG2);

        assertThat(bericht.fehler()).contains("2026-01-11: vorher_sha256 passt nicht zum Manifest 2026-01-10");
        assertThat(bericht.fehler()).noneMatch(f -> f.startsWith("2026-01-10: Signatur"));
    }

    @Test
    void einNichtKanonischesManifestIstEinFehler() {
        kette(1, BEIDE, Duration.ofMinutes(20));
        byte[] json = ablage.lesenManifest(MANDANT, TAG1, ManifestTeil.JSON).orElseThrow().inhalt();
        byte[] mitLeerzeichen = (" " + new String(json, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        ersetzt.put(ManifestTeil.JSON.pfad(TAG1), mitLeerzeichen);

        assertThat(pruefen(TAG1, TAG1).fehler())
                .containsExactly("2026-01-10: Manifest ist nicht in kanonischer Form (RFC 8785)");
    }

    @Test
    void geaendertePruefdatenDesVortagsBrechenDieErneuerung() {
        kette(2, BEIDE, Duration.ofMinutes(20));
        ersetzt.put(ManifestTeil.crl("zweit").pfad(TAG1), "andere Sperrliste".getBytes(StandardCharsets.UTF_8));

        assertThat(pruefen(TAG1, TAG2).fehler())
                .contains("2026-01-11: Erneuerung: Prüfdaten von zweit vom 2026-01-10 passen nicht");
    }

    @Test
    void einFehlenderZeitstempelEinesDienstesWirdGenanntIstAberKeinFehler() {
        ablegen(TAG1, null, List.of("haupt"), Duration.ofMinutes(20), List.of());

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).isEmpty();
        assertThat(bericht.meldungen()).containsExactly("2026-01-10: Zeitstempel von zweit fehlt");
    }

    @Test
    void ohneJedenGueltigenZeitstempelIstDasManifestFehlerhaft() {
        ablegen(TAG1, null, List.of(), Duration.ofMinutes(20), List.of());

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).containsExactly("2026-01-10: kein gültiger Zeitstempel");
        assertThat(bericht.meldungen()).containsExactly("2026-01-10: Zeitstempel von haupt fehlt",
                "2026-01-10: Zeitstempel von zweit fehlt");
    }

    @Test
    void einZeitstempelMitFalschemImprintIstUngueltig() {
        kette(1, BEIDE, Duration.ofMinutes(20));
        ersetzt.put(ManifestTeil.tsr("haupt").pfad(TAG1), tsr(new byte[32], TAG1, Duration.ofMinutes(20)));

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).containsExactly("2026-01-10: Zeitstempel von haupt ungültig: Imprint passt nicht");
    }

    @Test
    void fehlendePruefdatenEinesNichtFestgehaltenenZeitstempelsWerdenNurGemeldet() {
        kette(1, BEIDE, Duration.ofMinutes(20));
        entfernt.add(ManifestTeil.crl("zweit").pfad(TAG1));

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).isEmpty();
        assertThat(bericht.meldungen())
                .containsExactly("2026-01-10: Zeitstempel von zweit unvollständig (Prüfdaten fehlen), nicht festgehalten");
    }

    @Test
    void fehlendePruefdatenEinesImFolgetagVerwiesenenZeitstempelsSindEinFehler() {
        kette(2, BEIDE, Duration.ofMinutes(20));
        entfernt.add(ManifestTeil.kette("zweit").pfad(TAG1));

        assertThat(pruefen(TAG1, TAG1).fehler())
                .containsExactly("2026-01-10: Prüfdaten des Zeitstempels von zweit fehlen");
    }

    @Test
    void einUnvollstaendigerZeitstempelOhneGueltigenAnderenIstKeinGueltigerZeitstempel() {
        kette(1, List.of("zweit"), Duration.ofMinutes(20));
        entfernt.add(ManifestTeil.crl("zweit").pfad(TAG1));

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).containsExactly("2026-01-10: kein gültiger Zeitstempel");
        assertThat(bericht.meldungen()).containsExactly("2026-01-10: Zeitstempel von haupt fehlt");
    }

    @Test
    void eineWeitereVersionUnterEinemManifestSchluesselIstEinFehler() {
        kette(1, BEIDE, Duration.ofMinutes(20));
        weitere.put(ManifestTeil.JSON.pfad(TAG1), 1);
        weitere.put(ManifestTeil.tsr("haupt").pfad(TAG1), 2);

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.ok()).isFalse();
        assertThat(bericht.fehler()).containsExactly(
                "2026-01-10: unter " + ManifestTeil.JSON.pfad(TAG1)
                        + " liegen 1 weitere Version(en) oder Löschmarke(n); geprüft wird die älteste",
                "2026-01-10: unter " + ManifestTeil.tsr("haupt").pfad(TAG1)
                        + " liegen 2 weitere Version(en) oder Löschmarke(n); geprüft wird die älteste");
    }

    @Test
    void einVerspaeteterZeitstempelWirdAlsVerspaetungGemeldet() {
        ablegen(TAG1, null, BEIDE, Duration.ofHours(30), List.of());

        Pruefbericht bericht = pruefen(TAG1, TAG1);

        assertThat(bericht.fehler()).isEmpty();
        assertThat(bericht.meldungen()).hasSize(2).allMatch(m -> m.contains("verspätet") && m.contains("30 h"));
    }

    @Test
    void einAusDerListeAusgetragenerDienstWirdUeberDenVerweisDesFolgetagsWeiterGeprueft() {
        kette(2, BEIDE, Duration.ofMinutes(20));
        konfiguriert = List.of("haupt");
        ersetzt.put(ManifestTeil.tsr("zweit").pfad(TAG1), tsr(new byte[32], TAG1, Duration.ofMinutes(20)));

        assertThat(pruefen(TAG1, TAG1).fehler())
                .containsExactly("2026-01-10: Zeitstempel von zweit ungültig: Imprint passt nicht");
    }

    @Test
    void einUngesperrterManifestTeilIstEinFehler() {
        kette(1, BEIDE, Duration.ofMinutes(20));
        ungesperrt.add(ManifestTeil.SIG.pfad(TAG1));

        assertThat(pruefen(TAG1, TAG1).fehler())
                .containsExactly("2026-01-10: " + ManifestTeil.SIG.pfad(TAG1) + " ist nicht gesperrt");
    }

    private Pruefbericht pruefen(LocalDate von, LocalDate bis) {
        return new ManifestPruefen(ablage, new JacksonJsonLeser(), schluessel(), new TsaAttrappe(),
                Duration.ofHours(24)).pruefen(MANDANT, von, bis);
    }

    private void kette(int tage, List<String> dienste, Duration nachEnde) {
        Stand stand = null;
        for (int i = 0; i < tage; i++) {
            stand = ablegen(TAG1.plusDays(i), stand, dienste, nachEnde, List.of());
        }
    }

    private Stand ablegen(LocalDate datum, Stand vorher, List<String> dienste, Duration nachEnde,
                          List<ManifestEintrag> eintraege) {
        ManifestFenster fenster = ManifestFenster.fuer(datum, vorher == null ? null : vorher.bis());
        byte[] json = new ManifestInhalt(MANDANT.wert(), datum, fenster.von(), fenster.bis(),
                vorher == null ? null : vorher.sha256(), vorher == null ? null : vorher.verweis(), eintraege,
                new TreeMap<>(), List.of()).kanonisch();
        byte[] sig = ManifestSignatur.signieren(paar.getPrivate(), json);
        stub.ablegenManifest(MANDANT, datum, ManifestTeil.JSON, json, SPERRE);
        stub.ablegenManifest(MANDANT, datum, ManifestTeil.SIG, sig, SPERRE);
        byte[] imprint = ManifestSignatur.imprint(json, sig);
        List<ZeitstempelVerweis> verweise = new ArrayList<>();
        for (String dienst : dienste) {
            byte[] tsr = tsr(imprint, datum, nachEnde);
            byte[] kette = ("kette " + dienst).getBytes(StandardCharsets.UTF_8);
            byte[] crl = ("crl " + dienst + " " + datum).getBytes(StandardCharsets.UTF_8);
            stub.ablegenManifest(MANDANT, datum, ManifestTeil.tsr(dienst), tsr, SPERRE);
            stub.ablegenManifest(MANDANT, datum, ManifestTeil.kette(dienst), kette, SPERRE);
            stub.ablegenManifest(MANDANT, datum, ManifestTeil.crl(dienst), crl, SPERRE);
            verweise.add(new ZeitstempelVerweis(dienst, Sha256.hexVon(tsr),
                    List.of(Sha256.hexVon(kette), Sha256.hexVon(crl))));
        }
        return new Stand(datum, fenster.bis(), Sha256.hexVon(json), new Vorher(Sha256.hexVon(sig), verweise));
    }

    /** Attrappe eines Tokens: Imprint (32 Byte) und {@code genTime} in Sekunden. */
    private static byte[] tsr(byte[] imprint, LocalDate datum, Duration nachEnde) {
        return ByteBuffer.allocate(40).put(imprint)
                .putLong(ManifestFenster.bis(datum).plus(nachEnde).getEpochSecond()).array();
    }

    private final class TsaAttrappe implements ZeitstempelPort {

        @Override
        public List<String> dienste() {
            return konfiguriert;
        }

        @Override
        public Stempel stempeln(String dienst, byte[] imprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stempel pruefen(String dienst, byte[] tsr, byte[] imprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Pruefdaten pruefdaten(String dienst, Stempel stempel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant pruefenAbgelegt(String dienst, byte[] tsr, byte[] imprint, String kettePem,
                                       Pruefdaten pruefdaten) {
            if (!kettePem.equals("kette " + dienst) || pruefdaten.art() != Pruefdaten.Art.CRL) {
                throw new ZeitstempelFehler("Kette passt nicht");
            }
            if (!Arrays.equals(Arrays.copyOf(tsr, 32), imprint)) {
                throw new ZeitstempelFehler("Imprint passt nicht");
            }
            return Instant.ofEpochSecond(ByteBuffer.wrap(tsr, 32, 8).getLong());
        }
    }

    private ManifestSchluessel schluessel() {
        return new ManifestSchluessel() {
            @Override
            public PrivateKey privat() {
                return paar.getPrivate();
            }

            @Override
            public PublicKey oeffentlich() {
                return paar.getPublic();
            }
        };
    }

    /**
     * Ablage im Speicher, deren Manifest-Teile sich ersetzen oder entfernen lassen (gesperrt geht das in S3 nicht) oder
     * weitere Versionen melden.
     */
    private AblagePort manipulierbar() {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(), new Class<?>[] {AblagePort.class},
                (proxy, methode, argumente) -> {
                    if (methode.getName().equals("lesenManifest")) {
                        String pfad = ((ManifestTeil) argumente[2]).pfad((LocalDate) argumente[1]);
                        if (entfernt.contains(pfad)) {
                            return Optional.empty();
                        }
                        if (ersetzt.containsKey(pfad) || weitere.containsKey(pfad) || ungesperrt.contains(pfad)) {
                            ManifestDatei echt = stub.lesenManifest(MANDANT, (LocalDate) argumente[1],
                                    (ManifestTeil) argumente[2]).orElseThrow();
                            AbgelegteDatei datei = ungesperrt.contains(pfad)
                                    ? new AbgelegteDatei(echt.datei().schluessel(), echt.datei().versionId(),
                                    echt.datei().sha256(), echt.datei().groesse(), Optional.empty())
                                    : echt.datei();
                            return Optional.of(new ManifestDatei(ersetzt.getOrDefault(pfad, echt.inhalt()), datei,
                                    weitere.getOrDefault(pfad, 0)));
                        }
                    }
                    try {
                        return methode.invoke(stub, argumente);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
