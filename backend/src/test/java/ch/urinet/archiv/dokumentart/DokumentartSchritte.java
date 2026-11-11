package ch.urinet.archiv.dokumentart;

import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Dann;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 9.1 und 9.2: Stammdaten der Dokumentarten (Entscheid Fabian 10.11.2026). */
public class DokumentartSchritte {

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Dann("stimmt der Seed von «urinet» Feld für Feld mit der Stammdaten-Tabelle in design.md überein")
    public void seedWieTabelle() throws IOException {
        assertThat(DokumentartSeedTest.seedUrinet()).containsExactlyElementsOf(StammdatenTabelle.ausDesign());
    }

    @Dann("stimmen die Dokumentarten im Schema «{word}» Feld für Feld mit der Stammdaten-Tabelle in design.md überein")
    public void schemaWieTabelle(String schema) throws IOException, SQLException {
        assertThat(schema).matches("m_[a-z0-9_]+");
        List<StammdatenTabelle.Art> arten = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); Statement abfrage = verbindung.createStatement();
             ResultSet zeilen = abfrage.executeQuery("select code, name, gruppen, pflichtfelder, vertraulichkeit, "
                     + "aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv, beschreibung from " + schema + ".dokumentart")) {
            while (zeilen.next()) {
                arten.add(new StammdatenTabelle.Art(zeilen.getString("code"), zeilen.getString("name"),
                        texte(zeilen.getArray("gruppen")), texte(zeilen.getArray("pflichtfelder")),
                        zeilen.getString("vertraulichkeit"), zeilen.getObject("aufbewahrung_jahre", Integer.class),
                        zeilen.getString("aufbewahrung_ab"), zeilen.getBoolean("gesetzliche_pflicht"),
                        zeilen.getBoolean("aktiv"), zeilen.getString("beschreibung")));
            }
        }
        assertThat(arten).containsExactlyInAnyOrderElementsOf(StammdatenTabelle.ausDesign());
    }

    private static List<String> texte(Array feld) throws SQLException {
        return feld == null ? List.of() : List.of((String[]) feld.getArray());
    }
}
