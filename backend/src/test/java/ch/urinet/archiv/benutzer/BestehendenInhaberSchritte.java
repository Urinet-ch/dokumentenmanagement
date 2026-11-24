package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.in.kommando.BestehendenInhaberKommando;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.AbgleichStand;
import ch.urinet.archiv.benutzer.application.BenutzerAbgleichen;
import ch.urinet.archiv.benutzer.application.BenutzerVerzeichnisUebernahme;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadKonfigurationSeed;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class BestehendenInhaberSchritte {
    @Inject MandantProvisionierung provisionierung;
    @Inject KonfigurationSeed seed;
    @Inject KonfigurationsdateiCodec codec;
    @Inject MandantKontext kontext;
    @Inject IdentitaetsDienst identitaet;
    @Inject BestehendenInhaberKommando kommando;
    @Inject AbgleichStand stand;
    @Inject MandantAbbauRegister register;
    @Inject BenutzerVerzeichnisUebernahme uebernahme;
    @Inject AgroalDataSource datenquelle;

    private IdentitaetsDienst original;
    private IdentitaetsDienstStub stub;
    private MandantCode mandant;
    private String ziel;
    private IdentitaetsDienst.Benutzer vorher;
    private BenutzerAbgleichen abgleich;
    private BenutzerAbgleichen.Bericht nachher;

    @Angenommen("ein bestehender Inhaber hat noch keinen dokumentierten Rollenvorgang")
    public void bestehenderInhaber() throws Exception {
        mandant = new MandantCode("probe-inhaber-" + UUID.randomUUID().toString().substring(0, 8));
        KonfigurationSeed originalSeed = ClientProxy.unwrap(seed);
        QuarkusMock.installMockForType(new KlassenpfadKonfigurationSeed(codec) {
            @Override
            public Konfiguration lesen(MandantCode ignoriert) {
                return originalSeed.lesen(new MandantCode("urinet"));
            }
        }, KonfigurationSeed.class);
        try {
            provisionierung.anlegen(mandant.wert(), null, null);
        } finally {
            QuarkusMock.installMockForType(originalSeed, KonfigurationSeed.class);
        }
        original = ClientProxy.unwrap(identitaet);
        stub = new IdentitaetsDienstStub();
        QuarkusMock.installMockForType(stub, IdentitaetsDienst.class);
        ziel = stub.anlegen(mandant.wert(),
                new IdentitaetsDienst.Neuanlage("inhaber-probe", "probe@example.invalid", "Test", "Person"),
                Rolle.INHABER);
        vorher = stub.benutzerLesen(mandant.wert(), ziel);
        abgleich = new BenutzerAbgleichen(stub, stand, register, stub, uebernahme, mandant.wert());
        var bericht = imMandant(() -> abgleich.ausfuehren(mandant, true));
        assertThat(bericht.abweichungen()).extracting(BenutzerAbgleichen.Befund::code).contains("ROLLE_OHNE_VORGANG");
    }

    @Wenn("der Betreiber die bestehende Inhaberrolle prüft und dokumentiert")
    public void dokumentieren() throws Exception {
        var args = new ArrayList<>(List.of("inhaber-uebernehmen", mandant.wert(), "--benutzer", ziel,
                "--wer", UUID.randomUUID().toString()));
        var ausgabe = new ByteArrayOutputStream();
        var fehler = new ByteArrayOutputStream();
        assertThat(imMandant(() -> kommando.ausfuehren(args, new PrintStream(ausgabe), new PrintStream(fehler))))
                .as(fehler.toString()).isZero();
        assertThat(ausgabe.toString()).contains("GEPLANT");
        args.add("--ausfuehren");
        assertThat(imMandant(() -> kommando.ausfuehren(args, new PrintStream(ausgabe), new PrintStream(fehler))))
                .as(fehler.toString()).isZero();
        assertThat(imMandant(() -> kommando.ausfuehren(args, new PrintStream(ausgabe), new PrintStream(fehler))))
                .as(fehler.toString()).isZero();
        assertThat(ausgabe.toString()).contains("DOKUMENTIERT", "BEREITS_DOKUMENTIERT");
    }

    @Wenn("der Betreiber die Benutzerübernahme ausführt")
    public void uebernehmen() throws Exception {
        var pruefung = imMandant(() -> abgleich.ausfuehren(mandant, true));
        assertThat(pruefung.abweichungen()).isEmpty();
        assertThat(pruefung.uebernahmeGeplant()).isEqualTo(1);
        assertThat(imMandant(() -> abgleich.ausfuehren(mandant, false)).uebernommen()).isEqualTo(1);
        nachher = imMandant(() -> abgleich.ausfuehren(mandant, true));
    }

    @Dann("ist die Inhaberrolle unverändert und der Abgleich meldet keinen fehlenden Rollenvorgang")
    public void abgleichSauber() {
        assertThat(stub.benutzerLesen(mandant.wert(), ziel)).isEqualTo(vorher);
        assertThat(nachher.abweichungen()).isEmpty();
        assertThat(nachher.uebernahmeGeplant()).isZero();
    }

    @Dann("sind der Rollenvorgang und genau eine Übernahme ohne persönliche Angaben protokolliert")
    public void protokollPruefen() throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             var abfrage = verbindung.prepareStatement(
                     "select details->>'phase', details->>'anlass', details::text from " + mandant.schema()
                             + ".zugriff_protokoll where aktion = 'ROLLE_ZUWEISEN' and details->>'ziel' = ? order by id")) {
            abfrage.setString(1, ziel);
            try (var zeilen = abfrage.executeQuery()) {
                for (String phase : List.of("ANGEFRAGT", "ABGESCHLOSSEN")) {
                    assertThat(zeilen.next()).isTrue();
                    assertThat(zeilen.getString(1)).isEqualTo(phase);
                    assertThat(zeilen.getString(2)).isEqualTo("UEBERNAHME");
                    assertThat(zeilen.getString(3)).doesNotContain("probe@example.invalid", "inhaber-probe");
                }
                assertThat(zeilen.next()).isFalse();
            }
            try (var verzeichnis = verbindung.prepareStatement("select count(*) from " + mandant.schema()
                    + ".benutzer_verzeichnis where benutzer = ? and anlass = 'UEBERNAHME'")) {
                verzeichnis.setString(1, ziel);
                try (var zeilen = verzeichnis.executeQuery()) {
                    assertThat(zeilen.next()).isTrue();
                    assertThat(zeilen.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @After("@inhaber-uebernahme")
    public void wiederherstellen() {
        if (original != null) {
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }

    private <T> T imMandant(Supplier<T> arbeit) throws Exception {
        try (var thread = Executors.newSingleThreadExecutor()) {
            return thread.submit(() -> kontext.mit(mandant, arbeit)).get(30, TimeUnit.SECONDS);
        }
    }
}
