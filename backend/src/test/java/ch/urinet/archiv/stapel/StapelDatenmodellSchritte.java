package ch.urinet.archiv.stapel;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class StapelDatenmodellSchritte {

    private static final MandantCode MANDANT_A = new MandantCode("urinet");
    private static final MandantCode MANDANT_B = new MandantCode("vhf-gsk");

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final UUID stapelA = UUID.randomUUID();
    private final UUID stapelB = UUID.randomUUID();
    private final UUID dokumentA = UUID.randomUUID();
    private final UUID dokumentB = UUID.randomUUID();
    private boolean testmandantenBereit;

    @Angenommen("die Testmandanten für den Stapel bestehen")
    public void testmandantenBereit() throws SQLException {
        for (MandantCode mandant : List.of(MANDANT_A, MANDANT_B)) {
            if (!mandantRegistriert(mandant)) {
                provisionierung.anlegen(mandant.wert(), null, null);
            }
        }
        testmandantenBereit = true;
    }

    @Wenn("ich Stapelprotokolle und Seiten für beide Mandanten speichere")
    public void stapelProtokolleSpeichern() throws SQLException {
        stapelEintragen(MANDANT_A, stapelA, 2);
        stapelEintragen(MANDANT_B, stapelB, 3);
        seiteEintragen(MANDANT_A, stapelA, 1, null, null);
        seiteEintragen(MANDANT_B, stapelB, 1, null, null);
    }

    @Dann("sind die Protokolle und Seiten nur im jeweiligen Mandantenschema vorhanden")
    public void mandantenGetrennt() throws SQLException {
        assertThat(wert("select sha256 from %s.stapel where id = ?".formatted(MANDANT_A.schema()),
                stapelA)).isEqualTo(pruefsumme(stapelA));
        assertThat(wert("select sha256 from %s.stapel where id = ?".formatted(MANDANT_B.schema()),
                stapelB)).isEqualTo(pruefsumme(stapelB));
        assertThat(anzahl(MANDANT_A, "stapel", stapelB)).isZero();
        assertThat(anzahl(MANDANT_B, "stapel", stapelA)).isZero();
        assertThat(anzahl(MANDANT_A, "stapel_seite", stapelA)).isEqualTo(1);
        assertThat(anzahl(MANDANT_B, "stapel_seite", stapelB)).isEqualTo(1);
    }

    @Wenn("ich Dokumente mit Stapel und Seitenbereich für beide Mandanten speichere")
    public void dokumentVerweiseSpeichern() throws SQLException {
        stapelEintragen(MANDANT_A, stapelA, 2);
        stapelEintragen(MANDANT_B, stapelB, 2);
        dokumentEintragen(MANDANT_A, stapelA, dokumentA);
        dokumentEintragen(MANDANT_B, stapelB, dokumentB);
        seiteEintragen(MANDANT_A, stapelA, 1, dokumentA, 1);
        seiteEintragen(MANDANT_B, stapelB, 1, dokumentB, 1);
    }

    @Dann("verweisen Dokumente und Stapelseiten auf den jeweiligen Stapel und Seitenbereich")
    public void dokumentVerweisePruefen() throws SQLException {
        assertThat(dokumentStapel(MANDANT_A, dokumentA)).isEqualTo(stapelA);
        assertThat(dokumentStapel(MANDANT_B, dokumentB)).isEqualTo(stapelB);
        assertThat(dokumentBereich(MANDANT_A, dokumentA)).containsExactly(1, 2);
        assertThat(dokumentBereich(MANDANT_B, dokumentB)).containsExactly(1, 2);
        assertThat(anzahl(MANDANT_A, "dokument", dokumentB)).isZero();
        assertThat(anzahl(MANDANT_B, "dokument", dokumentA)).isZero();
        assertThat(seitenDokument(MANDANT_A, stapelA)).isEqualTo(dokumentA);
        assertThat(seitenDokument(MANDANT_B, stapelB)).isEqualTo(dokumentB);
    }

    @After
    public void testdatenEntfernen() throws SQLException {
        if (!testmandantenBereit) {
            return;
        }
        for (MandantCode mandant : List.of(MANDANT_A, MANDANT_B)) {
            loeschen(mandant, "stapel_seite", "stapel_id", mandant.equals(MANDANT_A) ? stapelA : stapelB);
            loeschen(mandant, "dokument", "id", mandant.equals(MANDANT_A) ? dokumentA : dokumentB);
            loeschen(mandant, "stapel", "id", mandant.equals(MANDANT_A) ? stapelA : stapelB);
        }
    }

    private boolean mandantRegistriert(MandantCode mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private void stapelEintragen(MandantCode mandant, UUID id, int seiten) throws SQLException {
        String sql = """
                insert into %s.stapel
                    (id, profil, eingang, sha256, groesse, s3_schluessel, seiten, inhalt, trenn, ordner, leer,
                     dokumente, status, bilanz_ok, empfangen)
                values (?, 'STAPEL', 'SFTP', ?, 1, ?, ?, ?, 0, 0, 0, 0, 'EMPFANGEN', true, ?)
                """.formatted(mandant.schema());
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, id);
            befehl.setString(2, pruefsumme(id));
            befehl.setString(3, "eingang/stapel/" + id + ".pdf");
            befehl.setInt(4, seiten);
            befehl.setInt(5, seiten);
            befehl.setTimestamp(6, Timestamp.from(Instant.now()));
            befehl.executeUpdate();
        }
    }

    private void dokumentEintragen(MandantCode mandant, UUID stapel, UUID dokument) throws SQLException {
        String sql = """
                insert into %s.dokument
                    (id, quelle, erstellt_von, status, stapel_id, stapel_von, stapel_bis)
                values (?, 'SCAN', 'test', 'EINGANGSKORB', ?, 1, 2)
                """.formatted(mandant.schema());
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, dokument);
            befehl.setObject(2, stapel);
            befehl.executeUpdate();
        }
    }

    private void seiteEintragen(MandantCode mandant, UUID stapel, int nr, UUID dokument, Integer seite)
            throws SQLException {
        String sql = """
                insert into %s.stapel_seite (stapel_id, nr, typ, dokument_id, seite_im_dokument)
                values (?, ?, 'INHALT', ?, ?)
                """.formatted(mandant.schema());
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, stapel);
            befehl.setInt(2, nr);
            befehl.setObject(3, dokument);
            if (seite == null) {
                befehl.setNull(4, java.sql.Types.INTEGER);
            } else {
                befehl.setInt(4, seite);
            }
            befehl.executeUpdate();
        }
    }

    private String wert(String sql, UUID id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getString(1);
            }
        }
    }

    private int anzahl(MandantCode mandant, String tabelle, UUID id) throws SQLException {
        String sql = "select count(*) from %s.%s where id = ?".formatted(mandant.schema(), tabelle);
        if (tabelle.equals("stapel_seite")) {
            sql = "select count(*) from %s.%s where stapel_id = ?".formatted(mandant.schema(), tabelle);
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getInt(1);
            }
        }
    }

    private UUID dokumentStapel(MandantCode mandant, UUID dokument) throws SQLException {
        return UUID.fromString(wert("select stapel_id from %s.dokument where id = ?"
                .formatted(mandant.schema()), dokument));
    }

    private List<Integer> dokumentBereich(MandantCode mandant, UUID dokument) throws SQLException {
        String sql = "select stapel_von, stapel_bis from %s.dokument where id = ?".formatted(mandant.schema());
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, dokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return List.of(zeile.getInt(1), zeile.getInt(2));
            }
        }
    }

    private UUID seitenDokument(MandantCode mandant, UUID stapel) throws SQLException {
        return UUID.fromString(wert("select dokument_id from %s.stapel_seite where stapel_id = ?"
                .formatted(mandant.schema()), stapel));
    }

    private void loeschen(MandantCode mandant, String tabelle, String spalte, UUID id) throws SQLException {
        String sql = "delete from %s.%s where %s = ?".formatted(mandant.schema(), tabelle, spalte);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, id);
            befehl.executeUpdate();
        }
    }

    private static String pruefsumme(UUID id) {
        return id.toString().replace("-", "").repeat(2);
    }
}
