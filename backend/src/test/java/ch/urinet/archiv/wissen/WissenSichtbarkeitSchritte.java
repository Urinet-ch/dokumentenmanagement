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
import ch.urinet.archiv.wissen.application.WissenHuelleRepository;
import ch.urinet.archiv.wissen.application.WissenSichtbarkeit;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    WissenHuelleRepository huellenRepository;

    @Inject
    WissenSichtbarkeit sichtbarkeit;

    @Inject
    Berechtigung berechtigung;

    private final UUID seite = UUID.randomUUID();
    private final UUID quellseite = UUID.randomUUID();
    private final UUID dokument = UUID.randomUUID();
    private final UUID fremdeSeite = UUID.randomUUID();
    private final Map<String, Boolean> sichtbarkeiten = new HashMap<>();
    private final Map<String, Integer> huellenGroessen = new HashMap<>();
    private final Map<String, Object> isolation = new HashMap<>();
    private final Map<String, String> uebersichtStufen = new HashMap<>();
    private final Map<String, WissensStufe> fassungStufen = new HashMap<>();
    private final Map<String, Set<WissensPruefung.Code>> fassungBefunde = new HashMap<>();

    @Wenn("rekursive Wissenshüllen und Sichtbarkeit in beiden Mandanten bei gleichzeitigen Daten des anderen Mandanten geprüft werden")
    public void sichtbarkeitPruefen() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            String anderer = code.equals("urinet") ? "vhf-gsk" : "urinet";
            imMandantenKontext(code, () -> {
                testdatenAnlegen(code);
                fremdeDatenAnlegen(anderer);
                huellen.versionFreigeben(seite, 1);
                huellen.versionFreigeben(seite, 2);
                huellenGroessen.put(code + "/seite", huellengroesse(code, seite));
                huellenGroessen.put(code + "/quellseite", huellengroesse(code, quellseite));
                isolationErfassen(code, anderer);
                rollenSichtbarkeitErfassen(code, "normal", seite, null);
                rollenSichtbarkeitErfassen(code, "normal", quellseite, null);
                rollenSichtbarkeitErfassen(code, "version-ohne-stufe", seite, 2);
                rollenSichtbarkeitErfassen(code, "version-ohne-huelle", seite, 3);

                if (code.equals("vhf-gsk")) {
                    mindeststufeManuellErhoehen();
                    huellen.dokumentSichtbarkeitGeaendert(dokument);
                    rollenSichtbarkeitErfassen(code, "mindeststufe", seite, null);
                    rollenSichtbarkeitErfassen(code, "version-normal", seite, 1);

                    quellvertraulichkeitErhoehen(code, dokument);
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

    @Dann("wirken Hülle, Vertraulichkeit und Seiten des anderen Mandanten nicht im eigenen Mandanten")
    public void mandantenGetrennt() {
        for (String code : List.of("urinet", "vhf-gsk")) {
            assertThat(isolation).as(code)
                    .containsEntry(code + "/fremde-huelle", 0)
                    .containsEntry(code + "/fremde-stufe", "INTERN")
                    .containsEntry(code + "/eigene-stufe", "NORMAL")
                    .containsEntry(code + "/fremde-seite", false);
        }
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
        versionNurMitA3("version-ohne-stufe");
    }

    @Dann("ist eine Version ohne gespeicherte Hülle nur für Rollen mit Recht A3 sichtbar")
    public void versionOhneHuelle() {
        versionNurMitA3("version-ohne-huelle");
    }

    @Dann("sieht «mitarbeiter» im Mandanten «{word}» nach manueller Erhöhung und Neuberechnung die aktuelle Seite nicht")
    public void manuellInterneSeite(String code) {
        assertThat(sichtbarkeiten).containsEntry(schluessel(code, "mindeststufe", seite, Rolle.MITARBEITER), false);
        for (Rolle rolle : List.of(Rolle.INHABER, Rolle.TREUHAENDER, Rolle.GESCHAEFTSSTELLE)) {
            assertThat(sichtbarkeiten).containsEntry(schluessel(code, "mindeststufe", seite, rolle), true);
        }
    }

    @Dann("sieht «mitarbeiter» dann auch die frühere normale Version der Seite nicht mehr")
    public void alteVersionFolgtDerSeite() {
        for (Rolle rolle : ROLLEN) {
            assertThat(sichtbarkeiten).as(rolle.code()).containsEntry(
                    schluessel("vhf-gsk", "version-normal", seite, rolle), rolle != Rolle.MITARBEITER);
        }
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

    @Wenn("eine Übersicht aus einer normalen Quelle freigegeben wird und die Quelle danach intern wird")
    public void uebersichtNeuBerechnen() throws Exception {
        imMandantenKontext("urinet", () -> {
            UUID uebersicht = UUID.randomUUID();
            UUID quelle = UUID.randomUUID();
            String schema = new MandantCode("urinet").schema();
            try (Connection verbindung = datenbank.get().getConnection()) {
                dokumentEinfuegen(verbindung, schema, quelle, "NORMAL");
                // Pfad ist eindeutig: setzt voraus, dass kein Test `ueberblick` in urinet committet.
                seiteEinfuegen(verbindung, schema, uebersicht, "ueberblick", "NORMAL");
                quellEintragen(verbindung, schema, uebersicht, "quelle", quelle, null);
                versionEinfuegen(verbindung, schema, uebersicht, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\"}");
            }
            huellen.versionFreigeben(uebersicht, 1);
            uebersichtStufen.put("normal", stufeLesen("urinet", uebersicht));
            for (Rolle rolle : ROLLEN) {
                sichtbarkeiten.put("ueberblick/normal/" + rolle.code(),
                        sichtbarkeit.aktuelleSeiteSichtbar(benutzer(rolle), uebersicht));
            }

            quellvertraulichkeitErhoehen("urinet", quelle);
            huellen.dokumentSichtbarkeitGeaendert(quelle);
            uebersichtStufen.put("intern", stufeLesen("urinet", uebersicht));
            for (Rolle rolle : ROLLEN) {
                sichtbarkeiten.put("ueberblick/intern/" + rolle.code(),
                        sichtbarkeit.aktuelleSeiteSichtbar(benutzer(rolle), uebersicht));
            }
        });
    }

    @Dann("ist die gespeicherte Vertraulichkeit der Übersicht zuerst NORMAL und alle Rollen mit Recht A15 sehen sie")
    public void uebersichtNormal() {
        assertThat(uebersichtStufen).containsEntry("normal", "NORMAL");
        for (Rolle rolle : ROLLEN) {
            assertThat(sichtbarkeiten).containsEntry("ueberblick/normal/" + rolle.code(), true);
        }
    }

    @Dann("speichert die Neuberechnung danach die wirksame Vertraulichkeit INTERN und «mitarbeiter» sieht die Übersicht nicht")
    public void uebersichtWirksamIntern() {
        assertThat(uebersichtStufen).containsEntry("intern", "INTERN");
        for (Rolle rolle : ROLLEN) {
            assertThat(sichtbarkeiten).containsEntry("ueberblick/intern/" + rolle.code(), rolle != Rolle.MITARBEITER);
        }
    }

    @Wenn("für die Übersicht eine Fassung mit einer intern eingestuften Quellseite und eine Fassung mit einer normalen Quellseite geprüft werden")
    public void uebersichtFassungenPruefen() throws Exception {
        imMandantenKontext("urinet", () -> {
            UUID interneQuellseite = UUID.randomUUID();
            UUID normaleQuellseite = UUID.randomUUID();
            UUID internesDokument = UUID.randomUUID();
            UUID normalesDokument = UUID.randomUUID();
            String schema = new MandantCode("urinet").schema();
            try (Connection verbindung = datenbank.get().getConnection()) {
                dokumentEinfuegen(verbindung, schema, internesDokument, "INTERN");
                dokumentEinfuegen(verbindung, schema, normalesDokument, "NORMAL");
                seiteEinfuegen(verbindung, schema, interneQuellseite, "probe/" + UUID.randomUUID(), "NORMAL");
                seiteEinfuegen(verbindung, schema, normaleQuellseite, "probe/" + UUID.randomUUID(), "NORMAL");
                quellEintragen(verbindung, schema, interneQuellseite, "quelle", internesDokument, null);
                quellEintragen(verbindung, schema, normaleQuellseite, "quelle", normalesDokument, null);
                versionEinfuegen(verbindung, schema, interneQuellseite, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\"}");
                versionEinfuegen(verbindung, schema, normaleQuellseite, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\"}");
            }
            huellen.versionFreigeben(interneQuellseite, 1);
            huellen.versionFreigeben(normaleQuellseite, 1);
            WissensGraph graph = huellenRepository.graphLaden();
            Set<UUID> seiten = Set.of(interneQuellseite, normaleQuellseite);
            fassungPruefen("intern", graph, interneQuellseite, seiten);
            fassungPruefen("normal", graph, normaleQuellseite, seiten);
        });
    }

    @Dann("ergibt die Fassung mit der internen Quellseite die wirksame Vertraulichkeit INTERN und den Befund «STUFE_UEBERBLICK»")
    public void uebersichtFassungIntern() {
        assertThat(fassungStufen).containsEntry("intern", WissensStufe.INTERN);
        assertThat(fassungBefunde.get("intern")).containsExactly(WissensPruefung.Code.STUFE_UEBERBLICK);
    }

    @Dann("ergibt die Fassung mit der normalen Quellseite die wirksame Vertraulichkeit NORMAL und keinen Befund")
    public void uebersichtFassungNormal() {
        assertThat(fassungStufen).containsEntry("normal", WissensStufe.NORMAL);
        assertThat(fassungBefunde.get("normal")).isEmpty();
    }

    @Wenn("eine normale Seite mit einer Quelle ohne gespeicherte Hülle gelesen wird")
    public void seiteOhneHuelleLesen() throws Exception {
        imMandantenKontext("vhf-gsk", () -> {
            String schema = new MandantCode("vhf-gsk").schema();
            try (Connection verbindung = datenbank.get().getConnection()) {
                dokumentEinfuegen(verbindung, schema, dokument, "NORMAL");
                seiteEinfuegen(verbindung, schema, seite, "probe/" + UUID.randomUUID(), "NORMAL");
                quellEintragen(verbindung, schema, seite, "dokument", dokument, null);
            }
            rollenSichtbarkeitErfassen("vhf-gsk", "ohne-huelle", seite, null);
            for (Rolle rolle : ROLLEN) {
                sichtbarkeiten.put("ohne-huelle/liste/" + rolle.code(),
                        sichtbarkeit.sichtbareSeiten(benutzer(rolle)).contains(seite));
            }
        });
    }

    @Dann("sehen nur die Rollen mit Recht A3 die Seite ohne Hülle")
    public void seiteOhneHuelleNurMitA3() {
        for (Rolle rolle : ROLLEN) {
            boolean erwartet = rolle != Rolle.MITARBEITER;
            assertThat(sichtbarkeiten).as(rolle.code())
                    .containsEntry(schluessel("vhf-gsk", "ohne-huelle", seite, rolle), erwartet)
                    .containsEntry("ohne-huelle/liste/" + rolle.code(), erwartet);
        }
    }

    // Vorgabe für 14.2: Seitenstufe der Prüfung ist die Mindeststufe, die wirksame Stufe kommt aus dem Graphen.
    private void fassungPruefen(String name, WissensGraph graph, UUID quellseiteId, Set<UUID> seiten) {
        WissensStufe wirksam = graph.wirksameStufeNachAnnahme(Set.of(), Set.of(quellseiteId),
                WissensStufe.NORMAL, WissensStufe.NORMAL);
        Wissensseite fassung = new Wissensseite(null, "ueberblick", Seitentyp.SYNTHESE, "Überblick",
                "Synthetische Übersicht", List.of("synthetisch"), Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag("q", null, null, null, null, quellseiteId)),
                Wissensseite.Vertraulichkeit.NORMAL, "Aussage.[^q]", 1, "human:probe", null, "human:probe", null,
                false, false, null);
        WissensPruefung.Kontext pruefkontext = new WissensPruefung.Kontext(20_000, null, Set.of(), Map.of(), seiten,
                Set.of(), Set.of(), Set.of(), false, false, false, Wissensseite.Vertraulichkeit.NORMAL,
                Wissensseite.Vertraulichkeit.valueOf(wirksam.name()));
        fassungStufen.put(name, wirksam);
        fassungBefunde.put(name, WissensPruefung.pruefen(fassung, pruefkontext).befunde().stream()
                .map(WissensPruefung.Befund::code).collect(Collectors.toSet()));
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
            dokumentEinfuegen(verbindung, schema, dokument, "NORMAL");
            seiteEinfuegen(verbindung, schema, seite, "probe/" + UUID.randomUUID(), "NORMAL");
            seiteEinfuegen(verbindung, schema, quellseite, "probe/" + UUID.randomUUID(), "NORMAL");
            quellEintragen(verbindung, schema, seite, "dokument", dokument, null);
            quellEintragen(verbindung, schema, seite, "zyklus", null, quellseite);
            quellEintragen(verbindung, schema, quellseite, "zyklus", null, seite);
            versionEinfuegen(verbindung, schema, seite, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\"}");
            versionEinfuegen(verbindung, schema, seite, 2, "{}");
            versionEinfuegen(verbindung, schema, seite, 3, "{\"vertraulichkeit_mindest\":\"NORMAL\"}");
        }
    }

    // Gleiche IDs im anderen Mandanten, aber intern eingestuft; dazu eine Seite, die es nur dort gibt.
    private void fremdeDatenAnlegen(String code) throws Exception {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection()) {
            dokumentEinfuegen(verbindung, schema, dokument, "INTERN");
            seiteEinfuegen(verbindung, schema, seite, "probe/" + UUID.randomUUID(), "INTERN");
            seiteEinfuegen(verbindung, schema, fremdeSeite, "probe/" + UUID.randomUUID(), "NORMAL");
            quellEintragen(verbindung, schema, seite, "dokument", dokument, null);
        }
    }

    private void isolationErfassen(String code, String anderer) throws Exception {
        isolation.put(code + "/fremde-huelle", huellengroesse(anderer, seite));
        isolation.put(code + "/fremde-stufe", stufeLesen(anderer, seite));
        isolation.put(code + "/eigene-stufe", stufeLesen(code, seite));
        isolation.put(code + "/fremde-seite", sichtbarkeit.aktuelleSeiteSichtbar(benutzer(Rolle.INHABER), fremdeSeite));
    }

    private void versionNurMitA3(String phase) {
        for (String code : List.of("urinet", "vhf-gsk")) {
            for (Rolle rolle : ROLLEN) {
                assertThat(sichtbarkeiten).as(code + " " + rolle.code())
                        .containsEntry(schluessel(code, phase, seite, rolle), rolle != Rolle.MITARBEITER);
            }
        }
    }

    private static void dokumentEinfuegen(Connection verbindung, String schema, UUID id, String vertraulichkeit)
            throws Exception {
        try (PreparedStatement p = verbindung.prepareStatement("""
                insert into %s.dokument (id, quelle, vertraulichkeit, erstellt_von, status, bestaetigt,
                                         bestaetigt_von)
                values (?, 'UPLOAD', ?, 'synthetisch', 'ARCHIVIERT', ?, 'inhaber')
                """.formatted(schema))) {
            p.setObject(1, id);
            p.setString(2, vertraulichkeit);
            p.setObject(3, ZEIT);
            p.executeUpdate();
        }
    }

    private static void versionEinfuegen(Connection verbindung, String schema, UUID seiteId, int version,
                                         String daten) throws Exception {
        try (PreparedStatement p = verbindung.prepareStatement("""
                insert into %s.wissensseite_version
                    (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit)
                values (?, ?, cast(? as jsonb), 'synthetischer Inhalt', 'human:probe', 'human:probe', ?)
                """.formatted(schema))) {
            p.setObject(1, seiteId);
            p.setInt(2, version);
            p.setString(3, daten);
            p.setObject(4, ZEIT);
            p.executeUpdate();
        }
    }

    private static void seiteEinfuegen(Connection verbindung, String schema, UUID id, String pfad, String stufe)
            throws Exception {
        try (PreparedStatement p = verbindung.prepareStatement("""
                insert into %s.wissensseite
                    (id, pfad, typ, titel, beschreibung, vertraulichkeit, vertraulichkeit_mindest, inhalt, version,
                     erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                values (?, ?, 'Konzept', 'Synthetische Probe', 'Beschreibung', ?, ?,
                        'synthetischer Inhalt', 1, 'human:probe', ?, 'human:probe', ?)
                """.formatted(schema))) {
            p.setObject(1, id);
            p.setString(2, pfad);
            p.setString(3, stufe);
            p.setString(4, stufe);
            p.setObject(5, ZEIT);
            p.setObject(6, ZEIT);
            p.executeUpdate();
        }
    }

    private static void quellEintragen(Connection verbindung, String schema, UUID seiteId, String schluessel,
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

    private void quellvertraulichkeitErhoehen(String code, UUID dokumentId) throws Exception {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement(
                     "update " + schema + ".dokument set vertraulichkeit = 'INTERN' where id = ?")) {
            p.setObject(1, dokumentId);
            p.executeUpdate();
        }
    }

    private String stufeLesen(String code, UUID seiteId) throws Exception {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement p = verbindung.prepareStatement(
                     "select vertraulichkeit from " + schema + ".wissensseite where id = ?")) {
            p.setObject(1, seiteId);
            try (ResultSet rs = p.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
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
