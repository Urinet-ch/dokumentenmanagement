package ch.urinet.archiv.wissen;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenHuelleDienst;
import ch.urinet.archiv.wissen.application.WissenSichtbarkeit;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WissenSichtbarkeitSchritte {

    private static final OffsetDateTime ZEIT = OffsetDateTime.parse("2001-01-01T00:00:00Z");
    private static final List<Rolle> ROLLEN = List.of(Rolle.values());

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MandantKontext kontext;

    @Inject
    UserTransaction transaktion;

    @Inject
    WissenHuelleDienst huellen;

    @Inject
    WissenSichtbarkeit sichtbarkeit;

    @Inject
    Berechtigung berechtigung;

    private final UUID seite = UUID.randomUUID();
    private final UUID quellseite = UUID.randomUUID();
    private final UUID dokument = UUID.randomUUID();
    private final Map<String, Boolean> sichtbarkeiten = new HashMap<>();
    private final Map<String, Integer> huellenGroessen = new HashMap<>();
    private String uebersichtStufe;

    @Wenn("rekursive Wissenshüllen und Sichtbarkeit in beiden Mandanten geprüft werden")
    public void sichtbarkeitPruefen() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            imMandantenKontext(code, () -> {
                testdatenAnlegen(code);
                huellen.versionFreigeben(seite, 1);
                huellen.versionFreigeben(seite, 2);
                huellenGroessen.put(code + "/seite", huellengroesse(code, seite));
                huellenGroessen.put(code + "/quellseite", huellengroesse(code, quellseite));
                rollenSichtbarkeitErfassen(code, "normal", seite, null);
                rollenSichtbarkeitErfassen(code, "normal", quellseite, null);
                rollenSichtbarkeitErfassen(code, "version-ohne-stufe", seite, 2);

                if (code.equals("vhf-gsk")) {
                    mindeststufeManuellErhoehen();
                    huellen.dokumentSichtbarkeitGeaendert(dokument);
                    rollenSichtbarkeitErfassen(code, "mindeststufe", seite, null);
                    sichtbarkeiten.put(schluessel(code, "version-normal", seite, Rolle.MITARBEITER),
                            sichtbarkeit.versionSichtbar(benutzer(Rolle.MITARBEITER), seite, 1));

                    quellvertraulichkeitErhoehen();
                    huellen.dokumentSichtbarkeitGeaendert(dokument);
                    rollenSichtbarkeitErfassen(code, "quelle-intern", seite, null);
                    rollenSichtbarkeitErfassen(code, "quelle-intern", quellseite, null);
                    rollenSichtbarkeitErfassen(code, "version-intern", seite, 1);
                }
            });
        }
    }

    @Dann("enthält die Hülle jeder zyklisch verknüpften Seite das Quelldokument")
    public void huellenSindVollstaendig() {
        assertThat(huellenGroessen).containsEntry("urinet/seite", 1)
                .containsEntry("urinet/quellseite", 1)
                .containsEntry("vhf-gsk/seite", 1)
                .containsEntry("vhf-gsk/quellseite", 1);
    }

    @Dann("sehen alle vier Rollen die normalen Seiten beider Mandanten")
    public void alleRollenSehenNormaleSeiten() {
        for (String code : List.of("urinet", "vhf-gsk")) {
            for (Rolle rolle : ROLLEN) {
                for (UUID seitenId : List.of(seite, quellseite)) {
                    assertThat(sichtbarkeiten.get(schluessel(code, "normal", seitenId, rolle)))
                            .as(code + " " + seitenId + " " + rolle).isTrue();
                }
            }
        }
    }

    @Dann("ist eine Version ohne gespeicherte Mindeststufe nur für Rollen mit Recht A3 sichtbar")
    public void versionOhneMindeststufe() {
        for (String code : List.of("urinet", "vhf-gsk")) {
            for (Rolle rolle : ROLLEN) {
                boolean erwartet = rolle != Rolle.MITARBEITER;
                assertThat(sichtbarkeiten).containsEntry(
                        schluessel(code, "version-ohne-stufe", seite, rolle), erwartet);
            }
        }
    }

    @Dann("sieht «mitarbeiter» im Mandanten «{word}» nach manueller Erhöhung und Neuberechnung die aktuelle Seite nicht")
    public void manuellInterneSeite(String code) {
        assertThat(sichtbarkeiten).containsEntry(schluessel(code, "mindeststufe", seite, Rolle.MITARBEITER), false);
        for (Rolle rolle : List.of(Rolle.INHABER, Rolle.TREUHAENDER, Rolle.GESCHAEFTSSTELLE)) {
            assertThat(sichtbarkeiten).containsEntry(schluessel(code, "mindeststufe", seite, rolle), true);
        }
    }

    @Dann("sieht «mitarbeiter» die frühere normale Version weiterhin, bis sich die Sichtbarkeit ihrer Quelle ändert")
    public void alteVersionBleibtSichtbar() {
        assertThat(sichtbarkeiten).containsEntry(
                schluessel("vhf-gsk", "version-normal", seite, Rolle.MITARBEITER), true);
    }

    @Dann("sehen nach der Änderung der Quelle nur die Rollen mit Recht A3 die internen Seiten und Versionen")
    public void quelleIntern() {
        for (Rolle rolle : ROLLEN) {
            boolean erwartet = rolle != Rolle.MITARBEITER;
            for (UUID seitenId : List.of(seite, quellseite)) {
                assertThat(sichtbarkeiten).containsEntry(schluessel("vhf-gsk", "quelle-intern", seitenId, rolle),
                        erwartet);
            }
            assertThat(sichtbarkeiten).containsEntry(
                    schluessel("vhf-gsk", "version-intern", seite, rolle), erwartet);
        }
    }

    @Angenommen("die Rollen der Wissensbasis sind geprüft")
    public void rollenGeprueft() {
        assertThat(Rechtematrix.ergebnis(Rolle.TREUHAENDER, Aktion.A17)).isEqualTo(Ergebnis.VERBOTEN);
        assertThat(Rechtematrix.ergebnis(Rolle.GESCHAEFTSSTELLE, Aktion.A17)).isEqualTo(Ergebnis.ERLAUBT);
        assertThat(Rechtematrix.ergebnis(Rolle.GESCHAEFTSSTELLE, Aktion.A19)).isEqualTo(Ergebnis.VERBOTEN);
    }

    @Dann("ist die Aktion A17 für «{word}» verboten")
    public void aktionA17Verboten(String rolle) {
        assertThat(Rechtematrix.ergebnis(Rolle.valueOf(rolle.toUpperCase(Locale.ROOT)), Aktion.A17))
                .isEqualTo(Ergebnis.VERBOTEN);
    }

    @Dann("dürfen Benutzer ohne Recht A17 nur Status, Entscheid und Grund eigener Analysevorschläge sehen")
    public void analyseVorschlaegeNurEingeschraenkt() {
        Benutzer mitarbeiter = benutzer(Rolle.MITARBEITER);
        assertThat(berechtigung.wissensentwurfLesen(mitarbeiter, Optional.of(mitarbeiter.id())))
                .isEqualTo(Berechtigung.Wissensentwurfszugriff.NUR_STATUS_ENTSCHEID_GRUND);
        assertThatThrownBy(() -> berechtigung.wissensentwurfLesen(mitarbeiter, Optional.of("anderer")))
                .isInstanceOf(Verweigerung.class);
        assertThat(berechtigung.wissensentwurfLesen(benutzer(Rolle.INHABER), Optional.empty()))
                .isEqualTo(Berechtigung.Wissensentwurfszugriff.VOLL);
        Benutzer treuhaender = benutzer(Rolle.TREUHAENDER);
        assertThatThrownBy(() -> berechtigung.wissensentwurfLesen(treuhaender,
                Optional.of(treuhaender.id())))
                .isInstanceOf(Verweigerung.class);
    }

    @Dann("ist die Aktion A19 für «{word}» verboten")
    public void aktionA19Verboten(String rolle) {
        assertThat(Rechtematrix.ergebnis(Rolle.valueOf(rolle.toUpperCase(Locale.ROOT)), Aktion.A19))
                .isEqualTo(Ergebnis.VERBOTEN);
    }

    @Wenn("eine Übersicht mit einer internen Quelle neu berechnet wird")
    public void uebersichtNeuBerechnen() throws Exception {
        imMandantenKontext("urinet", () -> {
            UUID uebersicht = UUID.randomUUID();
            UUID interneQuelle = UUID.randomUUID();
            String schema = new MandantCode("urinet").schema();
            try (Connection verbindung = datenbank.get().getConnection()) {
                try (PreparedStatement p = verbindung.prepareStatement("""
                        insert into %s.dokument (id, quelle, vertraulichkeit, erstellt_von, status, bestaetigt,
                                                 bestaetigt_von)
                        values (?, 'UPLOAD', 'INTERN', 'synthetisch', 'ARCHIVIERT', ?, 'inhaber')
                        """.formatted(schema))) {
                    p.setObject(1, interneQuelle);
                    p.setObject(2, ZEIT);
                    p.executeUpdate();
                }
                seiteEinfuegen(verbindung, schema, uebersicht, "ueberblick");
                quellEintragen(verbindung, schema, uebersicht, "intern", interneQuelle, null);
                try (PreparedStatement p = verbindung.prepareStatement("""
                        insert into %s.wissensseite_version
                            (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit)
                        values (?, 1, '{"vertraulichkeit_mindest":"NORMAL"}', 'synthetischer Inhalt',
                                'human:probe', 'human:probe', ?)
                        """.formatted(schema))) {
                    p.setObject(1, uebersicht);
                    p.setObject(2, ZEIT);
                    p.executeUpdate();
                }
            }
            huellen.versionFreigeben(uebersicht, 1);
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement p = verbindung.prepareStatement(
                         "select vertraulichkeit from " + schema + ".wissensseite where id = ?")) {
                p.setObject(1, uebersicht);
                try (ResultSet rs = p.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    uebersichtStufe = rs.getString(1);
                }
            }
        });
    }

    @Dann("bleibt die gespeicherte Vertraulichkeit der Übersicht NORMAL")
    public void uebersichtBleibtNormal() {
        assertThat(uebersichtStufe).isEqualTo("NORMAL");
    }

    private void imMandantenKontext(String code, GepruefteArbeit arbeit) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> kontext.mit(new MandantCode(code), () -> {
                try {
                    transaktion.begin();
                    try {
                        arbeit.ausfuehren();
                    } finally {
                        transaktion.rollback();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Wissenssichtbarkeit konnte nicht geprüft werden", e);
                }
                return null;
            })).get();
        } finally {
            worker.shutdown();
        }
    }

    private void testdatenAnlegen(String code) throws Exception {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement p = verbindung.prepareStatement("""
                    insert into %s.dokument (id, quelle, vertraulichkeit, erstellt_von, status, bestaetigt,
                                             bestaetigt_von)
                    values (?, 'UPLOAD', 'NORMAL', 'synthetisch', 'ARCHIVIERT', ?, 'inhaber')
                    """.formatted(schema))) {
                p.setObject(1, dokument);
                p.setObject(2, ZEIT);
                p.executeUpdate();
            }
            seiteEinfuegen(verbindung, schema, seite, "probe/" + UUID.randomUUID());
            seiteEinfuegen(verbindung, schema, quellseite, "probe/" + UUID.randomUUID());
            quellEintragen(verbindung, schema, seite, "dokument", dokument, null);
            quellEintragen(verbindung, schema, seite, "zyklus", null, quellseite);
            quellEintragen(verbindung, schema, quellseite, "zyklus", null, seite);
            try (PreparedStatement p = verbindung.prepareStatement("""
                    insert into %s.wissensseite_version
                        (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit)
                    values (?, 1, '{"vertraulichkeit_mindest":"NORMAL"}', 'synthetischer Inhalt',
                            'human:probe', 'human:probe', ?)
                    """.formatted(schema))) {
                p.setObject(1, seite);
                p.setObject(2, ZEIT);
                p.executeUpdate();
            }
            try (PreparedStatement p = verbindung.prepareStatement("""
                    insert into %s.wissensseite_version
                        (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit)
                    values (?, 2, '{}', 'synthetischer Inhalt', 'human:probe', 'human:probe', ?)
                    """.formatted(schema))) {
                p.setObject(1, seite);
                p.setObject(2, ZEIT);
                p.executeUpdate();
            }
        }
    }

    private void seiteEinfuegen(Connection verbindung, String schema, UUID id, String pfad) throws Exception {
        try (PreparedStatement p = verbindung.prepareStatement("""
                insert into %s.wissensseite
                    (id, pfad, typ, titel, beschreibung, vertraulichkeit, vertraulichkeit_mindest, inhalt, version,
                     erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                values (?, ?, 'Konzept', 'Synthetische Probe', 'Beschreibung', 'NORMAL', 'NORMAL',
                        'synthetischer Inhalt', 1, 'human:probe', ?, 'human:probe', ?)
                """.formatted(schema))) {
            p.setObject(1, id);
            p.setString(2, pfad);
            p.setObject(3, ZEIT);
            p.setObject(4, ZEIT);
            p.executeUpdate();
        }
    }

    private void quellEintragen(Connection verbindung, String schema, UUID seiteId, String schluessel,
                                UUID dokumentId, UUID quellseiteId) throws Exception {
        try (PreparedStatement p = verbindung.prepareStatement("""
                insert into %s.wissensseite_quelle
                    (seite_id, schluessel, dokument_id, quell_seite_id, seite_von, auszug)
                values (?, ?, ?, ?, ?, 'synthetischer Auszug')
                """.formatted(schema))) {
            p.setObject(1, seiteId);
            p.setString(2, schluessel);
            p.setObject(3, dokumentId);
            p.setObject(4, quellseiteId);
            if (dokumentId == null) {
                p.setNull(5, java.sql.Types.INTEGER);
            } else {
                p.setInt(5, 1);
            }
            p.executeUpdate();
        }
    }

    private void mindeststufeManuellErhoehen() throws Exception {
        String schema = new MandantCode("vhf-gsk").schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement(
                     "update " + schema + ".wissensseite set vertraulichkeit_mindest = 'INTERN' where id = ?")) {
            p.setObject(1, seite);
            p.executeUpdate();
        }
    }

    private void quellvertraulichkeitErhoehen() throws Exception {
        String schema = new MandantCode("vhf-gsk").schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement(
                     "update " + schema + ".dokument set vertraulichkeit = 'INTERN' where id = ?")) {
            p.setObject(1, dokument);
            p.executeUpdate();
        }
    }

    private int huellengroesse(String code, UUID seiteId) throws Exception {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement(
                     "select count(*) from " + schema + ".wissensseite_dokument where seite_id = ? and dokument_id = ?")) {
            p.setObject(1, seiteId);
            p.setObject(2, dokument);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void rollenSichtbarkeitErfassen(String code, String phase, UUID seiteId, Integer version) {
        for (Rolle rolle : ROLLEN) {
            boolean sichtbar = version == null
                    ? sichtbarkeit.aktuelleSeiteSichtbar(benutzer(rolle), seiteId)
                    : sichtbarkeit.versionSichtbar(benutzer(rolle), seiteId, version);
            sichtbarkeiten.put(schluessel(code, phase, seiteId, rolle), sichtbar);
        }
    }

    private String schluessel(String code, String phase, UUID seitenId, Rolle rolle) {
        String ressourcenname = seitenId.equals(seite) ? "seite" : "quellseite";
        return code + "/" + phase + "/" + ressourcenname + "/" + rolle.code();
    }

    private static Benutzer benutzer(Rolle rolle) {
        return new Benutzer("synthetisch-" + rolle.code(), rolle);
    }

    @FunctionalInterface
    private interface GepruefteArbeit {
        void ausfuehren() throws Exception;
    }

}
