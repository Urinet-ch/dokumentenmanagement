package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auftrag {@code MANDANT_EXPORT} (Req 8.2): Auslösen, Worker-Lauf, Fehlerpfad, Wiederholung, Download-Adresse. */
class MandantVollexportHandlerTest {

    private static final MandantCode MANDANT = new MandantCode("vhf-gsk");
    private static final UUID ID = UUID.fromString("6d60de8a-0f65-47df-96f2-4c7fe5e381cd");

    @Test
    void erfolgLegtZipAbUndMeldetAnzahlFertig(@TempDir Path temp) throws Exception {
        Testaufbau t = new Testaufbau(Repository.offen());
        t.daten.dokumente = List.of(new MandantVollexportDaten.Dokument(UUID.randomUUID(), "NEU", "BELEG", "{}", List.of()),
                new MandantVollexportDaten.Dokument(new UUID(9, 9), "NEU", "BELEG", "{}", List.of()));

        t.handler(temp).ausfuehren(auftrag(ID));

        assertThat(t.fertigeAnzahl).containsExactly(2);
        assertThat(t.lebenszyklus).isTrue();
        assertThat(t.zielAbgeschlossen).isTrue();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(t.zipBytes.toByteArray()))) {
            List<String> namen = new ArrayList<>();
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                namen.add(e.getName());
            }
            assertThat(namen).contains("metadaten.json", "metadaten.csv", "manifeste.csv", "pruefsummen.sha256");
        }
    }

    @Test
    void fehlerBeimSchreibenSchliesstZielOhneAbschlussUndMeldetNichtFertig(@TempDir Path temp) {
        Testaufbau t = new Testaufbau(Repository.offen());
        t.daten.dokumente = List.of(new MandantVollexportDaten.Dokument(UUID.randomUUID(), "NEU", "BELEG", "{}",
                List.of(new MandantVollexportDaten.Datei("archiv/x", "v1", "0".repeat(64), "dateien/x.bin"))));
        t.sicherungsKopieFehler = true;

        assertThatThrownBy(() -> t.handler(temp).ausfuehren(auftrag(ID))).isInstanceOf(Exception.class);

        assertThat(t.zielAbgeschlossen).isFalse();
        assertThat(t.zielGeschlossen).isTrue();
        assertThat(t.fertigeAnzahl).isEmpty();
    }

    @Test
    void nachLetztemFehlversuchSpeichertFehlerId(@TempDir Path temp) {
        Testaufbau t = new Testaufbau(Repository.offen());
        UUID fehlerId = UUID.randomUUID();

        t.handler(temp).nachLetztemFehlversuch(auftrag(ID), fehlerId);

        assertThat(t.fehler).containsExactly(Map.entry(ID, fehlerId));
    }

    @Test
    void nutzlastOhneExportIdBrichtAbUndMeldetKeinenFehlerZuEinerId(@TempDir Path temp) {
        Testaufbau t = new Testaufbau(Repository.offen());
        Auftrag ohne = new Auftrag(1, MANDANT, MandantVollexport.TYP, new Nutzlast(Map.of()), 0);

        assertThatThrownBy(() -> t.handler(temp).ausfuehren(ohne))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Export-ID");
        assertThatThrownBy(() -> t.handler(temp).nachLetztemFehlversuch(ohne, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(t.fehler).isEmpty();
    }

    @Test
    void fehlenderOderSchonAbgeschlossenerExportSchreibtNichts(@TempDir Path temp) throws Exception {
        Testaufbau fehlt = new Testaufbau(Optional.empty());
        assertThatThrownBy(() -> fehlt.handler(temp).ausfuehren(auftrag(ID)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fehlt");

        for (var status : List.of(MandantVollexportRepository.Status.FERTIG,
                MandantVollexportRepository.Status.FEHLER)) {
            Testaufbau t = new Testaufbau(Optional.of(Repository.export(status)));
            t.handler(temp).ausfuehren(auftrag(ID));
            assertThat(t.lebenszyklus).as(status.name()).isFalse();
            assertThat(t.fertigeAnzahl).as(status.name()).isEmpty();
        }
    }

    @Test
    void wiederholungNachAbbruchSchreibtNeuesZipUndMeldetEinmalFertig(@TempDir Path temp) throws Exception {
        Testaufbau t = new Testaufbau(Repository.offen());
        t.daten.dokumente = List.of(new MandantVollexportDaten.Dokument(UUID.randomUUID(), "NEU", "BELEG", "{}",
                List.of(new MandantVollexportDaten.Datei("archiv/x", "v1", "0".repeat(64), "dateien/x.bin"))));
        t.sicherungsKopieFehler = true;
        assertThatThrownBy(() -> t.handler(temp).ausfuehren(auftrag(ID))).isInstanceOf(Exception.class);

        t.sicherungsKopieFehler = false;
        t.daten.dokumente = List.of();
        t.handler(temp).ausfuehren(auftrag(ID));

        assertThat(t.fertigeAnzahl).containsExactly(0);
    }

    @Test
    void anfordernLegtExportAnUndReihtAuftragMitExportIdEin() {
        List<Nutzlast> eingereiht = new ArrayList<>();
        AuftragService auftraege = new AuftragService(null, null, null, null) {
            @Override
            public long einreihen(AuftragTyp typ, Nutzlast nutzlast) {
                assertThat(typ).isEqualTo(MandantVollexport.TYP);
                eingereiht.add(nutzlast);
                return 1;
            }
        };
        List<UUID> angelegt = new ArrayList<>();
        MandantVollexport vollexport = new MandantVollexport(new Repository(Optional.empty()) {
            @Override
            public boolean anlegen(UUID id) {
                angelegt.add(id);
                return true;
            }
        }, auftraege, null);

        UUID id = vollexport.anfordern();

        assertThat(angelegt).containsExactly(id);
        assertThat(eingereiht).hasSize(1);
        assertThat(eingereiht.getFirst().uuid(MandantVollexport.EXPORT_ID)).contains(id);
    }

    @Test
    void herunterladenGibtNurFuerFertigenExportEineUrl() {
        var fertig = new MandantVollexportRepository.Vollexport(ID, MandantVollexportRepository.Status.FERTIG,
                Optional.of("exporte/" + ID + ".zip"), Optional.of("v1"), Optional.of("a".repeat(64)),
                Optional.of(10L), Optional.of(1), Instant.now(), Optional.of(Instant.now()));
        List<String> urls = new ArrayList<>();
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (p, m, a) -> {
                    assertThat(m.getName()).isEqualTo("exportUrl");
                    urls.add((String) a[1] + "@" + a[2]);
                    return new VorsignierteUrl(java.net.URI.create("https://s3.example/x"), (Instant) a[3]);
                });

        assertThat(new MandantVollexport(new Repository(Optional.of(fertig)), null, ablage)
                .herunterladen(MANDANT, ID).url()).isNotNull();
        assertThat(urls).containsExactly("exporte/" + ID + ".zip@v1");
        assertThatThrownBy(() -> new MandantVollexport(new Repository(Optional.of(
                Repository.export(MandantVollexportRepository.Status.OFFEN))), null, ablage)
                .herunterladen(MANDANT, ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MandantVollexport(new Repository(Optional.empty()), null, ablage)
                .herunterladen(MANDANT, ID)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Auftrag auftrag(UUID exportId) {
        return new Auftrag(5, MANDANT, MandantVollexport.TYP, new Nutzlast(Map.of(MandantVollexport.EXPORT_ID, exportId)), 0);
    }

    private static class Repository implements MandantVollexportRepository {
        private final Optional<Vollexport> export;

        Repository(Optional<Vollexport> export) {
            this.export = export;
        }

        static Optional<Vollexport> offen() {
            return Optional.of(export(Status.OFFEN));
        }

        static Vollexport export(Status status) {
            return new Vollexport(ID, status, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Instant.now(), Optional.empty());
        }

        @Override
        public boolean anlegen(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Vollexport> laden(UUID id) {
            return export;
        }

        @Override
        public Bestand bestand() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean fertig(UUID id, AbgelegteDatei zip, int anzahlDokumente) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean fehler(UUID id, UUID fehlerId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestDaten implements MandantVollexportDaten {
        private List<Dokument> dokumente = List.of();

        @Override
        public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
            return dokumente.stream().filter(d -> nach.isEmpty() || d.id().compareTo(nach.get()) > 0).limit(anzahl)
                    .toList();
        }

        @Override
        public List<Manifestdatei> manifeste() {
            return List.of();
        }
    }

    private static final class Testaufbau {
        private final Optional<MandantVollexportRepository.Vollexport> export;
        private final TestDaten daten = new TestDaten();
        private final List<Integer> fertigeAnzahl = new ArrayList<>();
        private final List<Map.Entry<UUID, UUID>> fehler = new ArrayList<>();
        private final ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        private boolean lebenszyklus;
        private boolean zielAbgeschlossen;
        private boolean zielGeschlossen;
        private boolean sicherungsKopieFehler;

        private Testaufbau(Optional<MandantVollexportRepository.Vollexport> export) {
            this.export = export;
        }

        private MandantVollexportHandler handler(Path temp) {
            MandantVollexport speichern = new MandantVollexport(new Repository(export), null, null) {
                @Override
                public boolean fertig(Auftrag auftrag, UUID id, AbgelegteDatei zip, int anzahlDokumente) {
                    fertigeAnzahl.add(anzahlDokumente);
                    return true;
                }

                @Override
                public boolean fehler(UUID id, UUID fehlerId) {
                    fehler.add(Map.entry(id, fehlerId));
                    return true;
                }
            };
            AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                    new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                        case "exportLebenszyklusSichern" -> {
                            lebenszyklus = true;
                            yield null;
                        }
                        case "exportSchreiben" -> new Exportziel() {
                            @Override
                            public OutputStream strom() {
                                return zipBytes;
                            }

                            @Override
                            public AbgelegteDatei abschliessen() {
                                zielAbgeschlossen = true;
                                return new AbgelegteDatei("exporte/" + ID + ".zip", "v1", "a".repeat(64),
                                        zipBytes.size(), Optional.empty());
                            }

                            @Override
                            public void close() {
                                zielGeschlossen = true;
                            }
                        };
                        case "sicherungsKopie" -> {
                            if (sicherungsKopieFehler) {
                                throw new IllegalStateException("synthetischer Lesefehler");
                            }
                            yield new Kopie("0".repeat(64), 0);
                        }
                        default -> throw new UnsupportedOperationException(methode.getName());
                    });
            return new MandantVollexportHandler(speichern, new Repository(export), daten, ablage,
                    (mandant, strom) -> {}, temp);
        }
    }
}
