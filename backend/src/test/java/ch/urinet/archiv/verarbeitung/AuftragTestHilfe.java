package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Gemeinsame Schritte der Tests zur Auftragsschlange: einreihen wie der Worker, abarbeiten, Zeilen lesen. */
@ApplicationScoped
public class AuftragTestHilfe {

    /** Zustand einer Zeile in {@code public.auftrag}; Wartezeit in Sekunden ab jetzt, falls {@code nicht_vor} gesetzt. */
    public record Zeile(String mandant, String status, int versuche, String fehler, Long wartezeitSekunden,
                        boolean beendet, boolean lebenszeichenNachStart, boolean nachbearbeitet,
                        Long lebenszeichenAlterMs) {

        /** Fehler-ID aus {@code fehler} ({@code <art> fehler-id=<uuid>}). */
        public String fehlerId() {
            return fehler.substring(fehler.indexOf('=') + 1);
        }
    }

    // lazy: in Testprofilen ohne Datenbank ist die Datasource inaktiv
    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantKontext kontext;

    @Inject
    AuftragService service;

    @Inject
    AuftragArbeiter arbeiter;

    private final ExecutorService threads = Executors.newCachedThreadPool();

    /** Mandanten anlegen, Test-Aufträge löschen, Mitschriften der Test-Handler leeren. */
    public void vorbereiten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        ausfuehren("delete from public.auftrag where typ like 'TEST\\_%'");
        TestHandler.zuruecksetzen();
    }

    /** Reiht im Kontext des Mandanten ein, in einem eigenen Thread ohne Request-Kontext wie der Worker. */
    public long einreihen(String mandant, AuftragTyp typ) throws Exception {
        return threads.submit(() -> kontext.mit(new MandantCode(mandant),
                () -> service.einreihen(typ, Nutzlast.leer()))).get(10, TimeUnit.SECONDS);
    }

    /** Holt und bearbeitet, bis kein fälliger Auftrag mehr da ist. */
    public void abarbeiten() throws Exception {
        for (int runde = 0; runde < 20; runde++) {
            List<CompletableFuture<Void>> laeufe = arbeiter.abholen();
            if (laeufe.isEmpty()) {
                return;
            }
            CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        }
        throw new IllegalStateException("Auftragsschlange wird nicht leer");
    }

    public CompletableFuture<Void> einenStarten() {
        List<CompletableFuture<Void>> laeufe = arbeiter.abholen();
        if (laeufe.size() != 1) {
            throw new IllegalStateException("Erwartet 1 Auftrag, geholt " + laeufe.size());
        }
        return laeufe.getFirst();
    }

    public Zeile zeile(long id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select mandant, status, versuche, fehler, "
                        + "case when nicht_vor is null then null else extract(epoch from nicht_vor - now())::bigint end, "
                        + "beendet is not null, coalesce(lebenszeichen > gestartet, false), nachbearbeitet is not null, "
                        + "(extract(epoch from now() - lebenszeichen) * 1000)::bigint "
                        + "from public.auftrag where id = ?")) {
            abfrage.setLong(1, id);
            try (ResultSet z = abfrage.executeQuery()) {
                if (!z.next()) {
                    throw new IllegalStateException("Auftrag " + id + " fehlt");
                }
                return new Zeile(z.getString(1), z.getString(2), z.getInt(3), z.getString(4),
                        z.getObject(5) == null ? null : z.getLong(5), z.getBoolean(6), z.getBoolean(7),
                        z.getBoolean(8), z.getObject(9) == null ? null : z.getLong(9));
            }
        }
    }

    /** Wert eines SQL-Ausdrucks über die Zeile des Auftrags, z. B. {@code nachbearbeitung_versuche}. */
    public Object wert(long id, String ausdruck) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + ausdruck + " from public.auftrag where id = ?")) {
            abfrage.setLong(1, id);
            try (ResultSet z = abfrage.executeQuery()) {
                z.next();
                return z.getObject(1);
            }
        }
    }

    /** Ändert einen Auftrag direkt, z. B. um einen abgestürzten Worker nachzustellen. */
    public void aendern(long id, String setzen) throws SQLException {
        ausfuehren("update public.auftrag set " + setzen + " where id = " + id);
    }

    /** Fügt einen Auftrag mit roher Nutzlast ein, an {@code Nutzlast} vorbei (z. B. eine unlesbare Zeile). */
    public long rohEinreihen(String mandant, AuftragTyp typ, String nutzlastJson) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into public.auftrag (mandant, typ, nutzlast) "
                        + "values (?, ?, cast(? as jsonb)) returning id")) {
            einfuegen.setString(1, mandant);
            einfuegen.setString(2, typ.wert());
            einfuegen.setString(3, nutzlastJson);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    /** Hält gerade eine Transaktion die Zeilensperre des Auftrags ({@code for update nowait} scheitert)? */
    public boolean gesperrt(long id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.auftrag where id = ? for update nowait")) {
            abfrage.setLong(1, id);
            abfrage.executeQuery().close();
            return false;
        } catch (SQLException e) {
            if ("55P03".equals(e.getSQLState())) {
                return true;
            }
            throw e;
        }
    }

    /** Führt eine Änderung aus und liefert die Zahl betroffener Zeilen. */
    public int ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            return anweisung.executeUpdate();
        }
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
