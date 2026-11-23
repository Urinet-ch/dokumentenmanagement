package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.Abgleich;
import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet;
import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.verarbeitung.application.AbgleichAlarmQuelle;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.application.DokumentUeberwachung;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class VerwaltungsabgleichSchritte {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantRegister register;
    @Inject AgroalDataSource datenquelle;
    @Inject AbgleichIdentitaet identitaet;
    @Inject Abgleich abgleich;
    @Inject AbgleichAlarmQuelle quelle;
    @Inject AlarmSpeicher speicher;

    private AbgleichIdentitaet original;
    private UUID inhaberUrinet;
    private UUID inhaberVhf;

    @Before("@verwaltung-abgleich")
    public void vorbereiten() throws Exception {
        for (MandantCode code : List.of(URINET, VHF)) {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code.wert());
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code.wert(), null, null);
                    }
                }
            }
        }
        original = ClientProxy.unwrap(identitaet);
    }

    @After("@verwaltung-abgleich")
    public void identitaetWiederherstellen() {
        if (original != null) {
            QuarkusMock.installMockForType(original, AbgleichIdentitaet.class);
        }
    }

    @Angenommen("zwei Mandanten haben je einen synthetischen Inhaber ohne dokumentierte Vergabe")
    public void zweiInhaber() {
        inhaberUrinet = UUID.randomUUID();
        inhaberVhf = UUID.randomUUID();
        List<RealmBenutzer> realm = List.of(
                new RealmBenutzer(inhaberUrinet, List.of(URINET.wert()), Set.of(Rolle.INHABER)),
                new RealmBenutzer(inhaberVhf, List.of(VHF.wert()), Set.of(Rolle.INHABER)));
        QuarkusMock.installMockForType(new IdentitaetsDienstStub() {
            @Override public List<RealmBenutzer> seite(int seite) {
                return seite == 0 ? realm : List.of();
            }
        }, AbgleichIdentitaet.class);
    }

    @Wenn("der Worker die Benutzerverwaltung abgleicht")
    public void workerAbgleichen() {
        TaktThread.ausfuehren("abgleich-szenario", abgleich::ausfuehren);
    }

    @Dann("steht jede Abweichung im Protokoll und im Alarm des eigenen Mandanten")
    public void protokollUndAlarm() throws Exception {
        pruefen(URINET, inhaberUrinet, inhaberVhf);
        pruefen(VHF, inhaberVhf, inhaberUrinet);
    }

    private void pruefen(MandantCode mandant, UUID eigen, UUID fremd) throws Exception {
        String erwartet = "abgleich:" + eigen + ":ROLLE_OHNE_VORGANG";
        assertThat(quelle.offene(mandant)).extracting(Befund::schluessel).contains(erwartet)
                .doesNotContain("abgleich:" + fremd + ":ROLLE_OHNE_VORGANG");
        try (Connection verbindung = datenquelle.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from " + mandant.schema()
                        + ".zugriff_protokoll where aktion = 'BENUTZER_ABWEICHUNG' "
                        + "and details->>'ziel' = ? and details->>'abweichung' = 'ROLLE_OHNE_VORGANG'")) {
            abfrage.setString(1, eigen.toString());
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Dann("die Überwachung meldet jeden Befund einmal an den betroffenen Mandanten")
    public void zustellung() {
        List<String> gesendet = new ArrayList<>();
        InhaberBenachrichtigung post = new InhaberBenachrichtigung() {
            @Override public boolean alarm(MandantCode mandant, Befund befund) {
                gesendet.add(mandant.wert() + "/" + befund.schluessel());
                return true;
            }
            @Override public void archivierungGescheitert(MandantCode m, UUID d, UUID f) {
                throw new UnsupportedOperationException();
            }
            @Override public void prueflaufAbweichungen(MandantCode m, UUID l, PrueflaufRegister.Zaehler z,
                    List<PrueflaufRegister.Abweichung> e) { throw new UnsupportedOperationException(); }
            @Override public void prueflaufAbgebrochen(MandantCode m, UUID l, int teil,
                    PrueflaufRegister.Fortsetzung stand, PrueflaufRegister.Zaehler z,
                    List<PrueflaufRegister.Abweichung> e, UUID f) { throw new UnsupportedOperationException(); }
            @Override public boolean mailAbrufGestoert(MandantCode m, int fehler) {
                throw new UnsupportedOperationException();
            }
        };
        DokumentUeberwachung dokumente = new DokumentUeberwachung() {
            @Override public List<Befund> laengerAls(MandantCode m, Duration grenze) { return List.of(); }
            @Override public Map<String, Long> anzahlJeStatus(MandantCode m) { return Map.of(); }
        };
        Ueberwachung ueberwachung = new Ueberwachung(register, dokumente, m -> Optional.empty(),
                Optional::empty, m -> List.of(), quelle, speicher, post, Duration.ofHours(1), URINET.wert());
        ueberwachung.pruefen();
        ueberwachung.pruefen();

        assertThat(gesendet).filteredOn(s -> s.contains(inhaberUrinet.toString()) || s.contains(inhaberVhf.toString()))
                .containsExactlyInAnyOrder(
                        URINET.wert() + "/abgleich:" + inhaberUrinet + ":ROLLE_OHNE_VORGANG",
                        VHF.wert() + "/abgleich:" + inhaberVhf + ":ROLLE_OHNE_VORGANG");
    }
}
