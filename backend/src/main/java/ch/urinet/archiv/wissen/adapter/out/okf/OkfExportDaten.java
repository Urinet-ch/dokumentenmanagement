package ch.urinet.archiv.wissen.adapter.out.okf;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenOkfExportPort;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class OkfExportDaten implements WissenOkfExportPort {
    private record Huelle(Map<UUID, WissensStufe> stufen, Map<UUID, Set<UUID>> dokumente) {
    }

    private final Instance<AgroalDataSource> datenbank;
    private final MandantAufloesung aufloesung;
    private final String instanz;

    public OkfExportDaten(Instance<AgroalDataSource> datenbank, MandantAufloesung aufloesung,
                           @ConfigProperty(name = "archiv.wissen.instanz-id") String instanz) {
        this.datenbank = datenbank;
        this.aufloesung = aufloesung;
        this.instanz = instanz;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void schreiben(MandantCode mandant, OutputStream ziel) throws IOException, InterruptedException {
        if (!aufloesung.ausKontext().equals(mandant)) {
            throw new IllegalStateException("Mandantenkontext des Wissensexports stimmt nicht");
        }
        String schema = aufloesung.schema();
        try (Connection c = datenbank.get().getConnection()) {
            int vorherigeIsolation = c.getTransactionIsolation();
            String vorherigesSchema = c.getSchema();
            boolean vorherNurLesen = c.isReadOnly();
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                c.setReadOnly(true);
                c.setAutoCommit(false);
                c.setSchema(schema);
                schreiben(c, mandant, ziel);
                c.commit();
            } finally {
                c.rollback();
                c.setAutoCommit(true);
                c.setSchema(vorherigesSchema);
                c.setReadOnly(vorherNurLesen);
                c.setTransactionIsolation(vorherigeIsolation);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konsistenter Wissensexport nicht möglich", e);
        }
    }

    private void schreiben(Connection c, MandantCode mandant, OutputStream ziel)
            throws SQLException, IOException, InterruptedException {
        Map<UUID, Wissensseite> seiten = seiten(c);
        Map<UUID, List<Quelleneintrag>> quellen = quellen(c);
        Map<UUID, Set<UUID>> dokumenteJeSeite = new HashMap<>();
        Map<UUID, Set<UUID>> quellseitenJeSeite = new HashMap<>();
        Map<UUID, WissensStufe> mindeststufen = mindeststufen(c);
        Map<UUID, WissensStufe> dokumentStufen = new HashMap<>();
        Map<UUID, OkfSchreiber.Dokument> dokumente = new HashMap<>();
        for (var eintrag : quellen.entrySet()) {
            for (Quelleneintrag quelle : eintrag.getValue()) {
                if (quelle.dokumentId() != null) {
                    dokumenteJeSeite.computeIfAbsent(eintrag.getKey(), k -> new HashSet<>()).add(quelle.dokumentId());
                    dokumentStufen.put(quelle.dokumentId(), dokumentStufe(c, quelle.dokumentId()));
                    if (!dokumente.containsKey(quelle.dokumentId())) {
                        dokumente.put(quelle.dokumentId(), dokument(c, quelle.dokumentId()));
                    }
                } else {
                    if (!seiten.containsKey(quelle.quellSeiteId())) {
                        throw new IllegalStateException("Quellseite für Export fehlt");
                    }
                    quellseitenJeSeite.computeIfAbsent(eintrag.getKey(), k -> new HashSet<>())
                            .add(quelle.quellSeiteId());
                }
            }
        }
        Huelle gespeicherteHuelle = gespeicherteHuelle(c);
        WissensGraph graph = new WissensGraph(dokumenteJeSeite, quellseitenJeSeite, dokumentStufen, mindeststufen);
        Map<UUID, OkfSchreiber.Seitenverweis> verweise = new HashMap<>();
        seiten.values().forEach(s -> verweise.put(s.id(),
                new OkfSchreiber.Seitenverweis(s.id(), s.pfad(), s.titel(), s.freigegebenAm())));
        Map<UUID, OkfSchreiber.Dokument> exportDokumente = Map.copyOf(dokumente);
        Map<UUID, OkfSchreiber.Seitenverweis> exportVerweise = Map.copyOf(verweise);
        List<OkfSchreiber.Seite> ausgabe = new ArrayList<>();
        for (Wissensseite s : seiten.values()) {
            WissensStufe stufe = graph.wirksameStufe(s.id(), graph.dokumente(s.id()))
                    .hoechste(gespeicherteHuelle.stufen().getOrDefault(s.id(), WissensStufe.NORMAL))
                    .hoechste(WissensStufe.valueOf(s.vertraulichkeit().name()));
            if (!gespeicherteHuelle.dokumente().getOrDefault(s.id(), Set.of())
                    .containsAll(graph.dokumente(s.id()))) {
                stufe = WissensStufe.INTERN;
            }
            Wissensseite aktuell = new Wissensseite(s.id(), s.pfad(), s.typ(), s.titel(), s.beschreibung(), s.tags(),
                    s.status(), s.veraltetAb(), quellen.getOrDefault(s.id(), List.of()),
                    Wissensseite.Vertraulichkeit.valueOf(stufe.name()), s.inhalt(), s.version(), s.erzeugtVon(),
                    s.erzeugtAm(), s.freigegebenVon(), s.freigegebenAm(), s.quellePruefen(), s.widerspruchOffen(),
                    s.wissensquelleId());
            ausgabe.add(new OkfSchreiber.Seite(aktuell, exportDokumente, exportVerweise));
        }
        Set<String> pfade = seiten.values().stream().map(Wissensseite::pfad).collect(Collectors.toUnmodifiableSet());
        OkfSchreiber.Paket paket = new OkfSchreiber.Paket(mandant, "urinet-archiv", "lokal",
                Instant.now(), instanz, pfade);
        try (PreparedStatement sql = c.prepareStatement("select zeit, aktion, seiten from wissens_log "
                + "order by zeit desc, id desc")) {
            sql.setFetchSize(100);
            try (ResultSet rs = sql.executeQuery()) {
                OkfSchreiber.schreiben(paket, ausgabe, () -> logIterator(rs, seiten), ziel);
            }
        }
    }

    private Map<UUID, Wissensseite> seiten(Connection c) throws SQLException {
        Map<UUID, Wissensseite> seiten = new LinkedHashMap<>();
        try (Statement sql = c.createStatement(); ResultSet rs = sql.executeQuery("""
                select id, pfad, typ, titel, beschreibung, tags, status, veraltet_ab, vertraulichkeit, inhalt,
                       version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am, quelle_pruefen,
                       widerspruch_offen, wissensquelle_id
                from wissensseite order by pfad limit 201
                """)) {
            while (rs.next()) {
                UUID id = rs.getObject(1, UUID.class);
                Array tags = rs.getArray(6);
                try {
                    String typ = rs.getString(3);
                    Seitentyp seitentyp = java.util.Arrays.stream(Seitentyp.values())
                            .filter(wert -> wert.datenbankwert().equals(typ)).findFirst()
                            .orElseThrow(() -> new IllegalStateException("Ungültiger Wissensseitentyp"));
                    seiten.put(id, new Wissensseite(id, rs.getString(2), seitentyp, rs.getString(4), rs.getString(5),
                            List.of((String[]) tags.getArray()), Wissensseite.Status.valueOf(rs.getString(7)),
                            rs.getObject(8, java.time.LocalDate.class), List.of(),
                            Wissensseite.Vertraulichkeit.valueOf(rs.getString(9)), rs.getString(10), rs.getInt(11),
                            rs.getString(12), zeit(rs, 13), rs.getString(14), zeit(rs, 15), rs.getBoolean(16),
                            rs.getBoolean(17), rs.getObject(18, UUID.class)));
                } finally {
                    tags.free();
                }
            }
        }
        if (seiten.size() > 200) {
            throw new IllegalStateException("Wissensbasis überschreitet die Importgrenze von 200 Seiten");
        }
        return seiten;
    }

    private Map<UUID, List<Quelleneintrag>> quellen(Connection c) throws SQLException {
        Map<UUID, List<Quelleneintrag>> ergebnis = new HashMap<>();
        try (Statement sql = c.createStatement(); ResultSet rs = sql.executeQuery("""
                select seite_id, schluessel, dokument_id, seite_von, seite_bis, auszug, quell_seite_id
                from wissensseite_quelle order by seite_id, schluessel
                """)) {
            while (rs.next()) {
                ergebnis.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Quelleneintrag(rs.getString(2), rs.getObject(3, UUID.class),
                                rs.getObject(4, Integer.class), rs.getObject(5, Integer.class), rs.getString(6),
                                rs.getObject(7, UUID.class)));
            }
        }
        return ergebnis;
    }

    private Map<UUID, WissensStufe> mindeststufen(Connection c) throws SQLException {
        Map<UUID, WissensStufe> stufen = new HashMap<>();
        try (Statement sql = c.createStatement(); ResultSet rs = sql.executeQuery(
                "select id, vertraulichkeit_mindest from wissensseite")) {
            while (rs.next()) {
                stufen.put(rs.getObject(1, UUID.class), WissensStufe.valueOf(rs.getString(2)));
            }
        }
        return stufen;
    }

    private Huelle gespeicherteHuelle(Connection c) throws SQLException {
        Map<UUID, WissensStufe> stufen = new HashMap<>();
        Map<UUID, Set<UUID>> dokumente = new HashMap<>();
        try (Statement sql = c.createStatement(); ResultSet rs = sql.executeQuery("""
                select h.seite_id, d.id, d.vertraulichkeit, d.bestaetigt, d.status
                from wissensseite_dokument h left join dokument d on d.id = h.dokument_id
                """)) {
            while (rs.next()) {
                WissensStufe stufe = stufe(rs, 2);
                UUID seite = rs.getObject(1, UUID.class);
                stufen.merge(seite, stufe, WissensStufe::hoechste);
                dokumente.computeIfAbsent(seite, k -> new HashSet<>()).add(rs.getObject(2, UUID.class));
            }
        }
        return new Huelle(stufen, dokumente);
    }

    private WissensStufe dokumentStufe(Connection c, UUID id) throws SQLException {
        try (PreparedStatement sql = c.prepareStatement(
                "select id, vertraulichkeit, bestaetigt, status from dokument where id = ?")) {
            sql.setObject(1, id);
            try (ResultSet rs = sql.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Dokumentquelle für Export fehlt");
                }
                return stufe(rs, 1);
            }
        }
    }

    private WissensStufe stufe(ResultSet rs, int spalte) throws SQLException {
        String vertraulichkeit = rs.getString(spalte + 1);
        if (rs.getObject(spalte) == null || rs.getObject(spalte + 2) == null
                || !"ARCHIVIERT".equals(rs.getString(spalte + 3)) || "PERSONAL".equals(vertraulichkeit)) {
            throw new IllegalStateException("Dokument der Wissenshülle ist nicht exportierbar");
        }
        return WissensStufe.valueOf(vertraulichkeit);
    }

    private OkfSchreiber.Dokument dokument(Connection c, UUID id) throws SQLException {
        try (PreparedStatement sql = c.prepareStatement("""
                select d.titel, d.geaendert, f.sha256
                from dokument d join dokument_datei f on f.dokument_id = d.id
                where d.id = ? and f.rolle = 'ORIGINAL' and f.status = 'AKTIV'
                """)) {
            sql.setObject(1, id);
            try (ResultSet rs = sql.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Archivierte Originalquelle für Export fehlt");
                }
                OkfSchreiber.Dokument dokument = new OkfSchreiber.Dokument(id, rs.getString(3), rs.getString(1),
                        zeit(rs, 2));
                if (rs.next()) {
                    throw new IllegalStateException("Archivierte Originalquelle ist nicht eindeutig");
                }
                return dokument;
            }
        }
    }

    private static Instant zeit(ResultSet rs, int spalte) throws SQLException {
        return rs.getObject(spalte, OffsetDateTime.class).toInstant();
    }

    private Iterator<OkfSchreiber.Logzeile> logIterator(ResultSet rs, Map<UUID, Wissensseite> seiten) {
        return new Iterator<>() {
            private boolean geladen;
            private boolean vorhanden;

            @Override
            public boolean hasNext() {
                if (!geladen) {
                    try {
                        vorhanden = rs.next();
                        geladen = true;
                    } catch (SQLException e) {
                        throw new IllegalStateException("Wissenslog für Export nicht lesbar", e);
                    }
                }
                return vorhanden;
            }

            @Override
            public OkfSchreiber.Logzeile next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                try {
                    Array ids = rs.getArray(3);
                    try {
                        List<String> titel = new ArrayList<>();
                        for (UUID id : (UUID[]) ids.getArray()) {
                            Wissensseite seite = seiten.get(id);
                            if (seite != null) {
                                titel.add(seite.titel());
                            }
                        }
                        return new OkfSchreiber.Logzeile(zeit(rs, 1), rs.getString(2), String.join(", ", titel));
                    } finally {
                        ids.free();
                        geladen = false;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("Wissenslog für Export nicht lesbar", e);
                }
            }
        };
    }
}
