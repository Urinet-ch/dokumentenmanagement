package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.application.KontaktTreffer;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auftrag {@code CSV_VORSCHAU}: Abbruch bei neuer Zuordnung und offene Zeilen (Aufgabe 4.2, Review PR #66). */
class CsvVorschauHandlerTest {

    private static final Zuordnung ZUORDNUNG = new Zuordnung(null, Map.of(ImportFeld.NAME, 0));
    private static final Zuordnung NEUE_ZUORDNUNG = new Zuordnung(null, Map.of(ImportFeld.NAME, 0, ImportFeld.PLZ, 1));

    private final ImportLauf lauf = new ImportLauf(UUID.randomUUID(), ImportArt.KONTAKT, false, "kontakte.csv",
            "0".repeat(64), "UTF-8", ';', new Kopfzeile(List.of("Name", "PLZ")), 3, 0, 0,
            LaufStatus.VORSCHAU_AUSSTEHEND, "inhaber", Instant.now()).mit(ZUORDNUNG);
    private final Speicher repository = new Speicher(lauf);
    private final CsvVorschauHandler handler = new CsvVorschauHandler(new CsvVorschauSpeichern(repository,
            new LeeresRegister(), new AuftragService(schlange(), null, null, null), new BigDecimal("0.6")));
    private final Auftrag auftrag = new Auftrag(1, new MandantCode("urinet"), CsvVorschauHandler.TYP,
            new Nutzlast(Map.of(CsvVorschauHandler.LAUF_ID, lauf.id())), 0);

    @Test
    void ohneStoerungSchliesstDerAuftragDieVorschauAb() throws Exception {
        handler.ausfuehren(auftrag);

        assertThat(repository.lauf.status()).isEqualTo(LaufStatus.VORSCHAU);
        assertThat(repository.lauf.vorschau()).isEqualTo(new ImportLauf.Zaehler(3, 0, 0, 0, 0, 0));
    }

    @Test
    void eineNeueZuordnungWaehrendDesLaufsBrichtDenAuftragOhneSchreibenAb() throws Exception {
        repository.nachBloecken = 1;
        repository.stapel = 2;

        handler.ausfuehren(auftrag);

        assertThat(repository.geschrieben).containsExactly(2, 3);
        assertThat(repository.lauf.status()).isEqualTo(LaufStatus.VORSCHAU_AUSSTEHEND);
        assertThat(repository.lauf.zuordnung()).isEqualTo(NEUE_ZUORDNUNG);
        assertThat(repository.zeilen.get(4).ergebnis()).isEqualTo(Ergebnis.OFFEN);
    }

    @Test
    void offeneZeilenBeiUnveraendertemLaufLassenDenVersuchScheitern() {
        repository.verloren = 4;

        assertThatThrownBy(() -> handler.ausfuehren(auftrag))
                .isInstanceOf(CsvVorschauSpeichern.OffeneZeilen.class)
                .hasMessageContaining(lauf.id().toString()).hasMessageContaining(" 1 ");
        assertThat(repository.lauf.status()).isEqualTo(LaufStatus.VORSCHAU_AUSSTEHEND);
    }

    private static AuftragSchlange schlange() {
        return (AuftragSchlange) Proxy.newProxyInstance(AuftragSchlange.class.getClassLoader(),
                new Class<?>[]{AuftragSchlange.class}, (proxy, methode, argumente) -> {
                    if (methode.getName().equals("anspruchSperren")) {
                        return true;
                    }
                    throw new UnsupportedOperationException(methode.getName());
                });
    }

    /**
     * Zeilen im Speicher. {@code nachBloecken}: Nach so vielen geschriebenen Blöcken setzt eine neue Zuordnung die
     * Zeilen zurück (wie {@code CsvImport.zuordnen} in einer anderen Transaktion). {@code verloren}: Das Schreiben
     * dieser Zeile geht verloren, sie bleibt offen.
     */
    private static final class Speicher implements ImportRepository {

        final TreeMap<Integer, ImportZeile> zeilen = new TreeMap<>();
        final List<Integer> geschrieben = new ArrayList<>();
        ImportLauf lauf;
        int nachBloecken = -1;
        int stapel = Integer.MAX_VALUE;
        int verloren = -1;
        private int bloecke;

        Speicher(ImportLauf lauf) {
            this.lauf = lauf;
            for (int nr = 2; nr <= 4; nr++) {
                zeilen.put(nr, new ImportZeile(nr, List.of("Firma " + nr, "6000"), Ergebnis.OFFEN, null));
            }
        }

        @Override
        public Optional<ImportLauf> lauf(UUID id) {
            return Optional.of(lauf);
        }

        @Override
        public Optional<ImportLauf> laufSperren(UUID id) {
            return Optional.of(lauf);
        }

        @Override
        public int breite(UUID laufId) {
            return 2;
        }

        @Override
        public List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl, int zeichen) {
            return zeilen.tailMap(nachNr, false).values().stream().limit(Math.min(anzahl, stapel)).toList();
        }

        @Override
        public void abgleichSchreiben(UUID laufId, List<Abgeglichen> abgeglichen) {
            for (Abgeglichen a : abgeglichen) {
                geschrieben.add(a.zeile().nr());
                if (a.zeile().nr() != verloren) {
                    zeilen.put(a.zeile().nr(), a.zeile());
                }
            }
            if (++bloecke == nachBloecken) {
                zeilen.replaceAll((nr, z) -> new ImportZeile(nr, z.werte(), Ergebnis.OFFEN, null));
                lauf = lauf.mit(NEUE_ZUORDNUNG);
            }
        }

        @Override
        public int doppelteMarkieren(UUID laufId, String meldung) {
            return 0;
        }

        @Override
        public ImportLauf.Zaehler zaehlen(UUID laufId) {
            Map<Ergebnis, Integer> anzahl = new EnumMap<>(Ergebnis.class);
            zeilen.values().forEach(z -> anzahl.merge(z.ergebnis(), 1, Integer::sum));
            return new ImportLauf.Zaehler(anzahl.getOrDefault(Ergebnis.NEU, 0),
                    anzahl.getOrDefault(Ergebnis.GEAENDERT, 0), anzahl.getOrDefault(Ergebnis.UNVERAENDERT, 0),
                    anzahl.getOrDefault(Ergebnis.DUBLETTE, 0), anzahl.getOrDefault(Ergebnis.FEHLER, 0),
                    anzahl.getOrDefault(Ergebnis.UEBERSPRUNGEN, 0));
        }

        @Override
        public void laufAktualisieren(ImportLauf lauf) {
            this.lauf = lauf;
        }

        @Override
        public void laufAnlegen(ImportLauf lauf) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void zeilenAnlegen(UUID laufId, List<ImportZeile> zeilen) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ImportZeile> zeilenSeite(UUID laufId, int seite, int groesse) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void zuordnungSpeichern(GespeicherteZuordnung zuordnung) {
            throw new UnsupportedOperationException();
        }
    }

    /** Keine Kontakte: Jede Zeile ist neu. */
    private static final class LeeresRegister implements KontaktRegister {

        @Override
        public List<KontaktTreffer> mitSchluesseln(Collection<String> crmIds, Collection<String> emails,
                                                   List<String> namen, List<String> plz) {
            return List.of();
        }

        @Override
        public Map<String, List<UUID>> aehnlichsteJe(Collection<String> namen, BigDecimal untergrenze, int anzahl) {
            return Map.of();
        }

        @Override
        public List<Kontakt> suchen(String nameNormalisiert, int anzahl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Kontakt> finden(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Kontakt anlegen(KontaktAngaben angaben,
                                                               String nameNormalisiert, String quelle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Kontakt> aendern(UUID id, int version,
                KontaktAngaben angaben, String nameNormalisiert) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<UUID> mitEmail(String email) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<UUID> mitUid(String uid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<UUID> mitIban(String iban) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartnerKandidat> aehnlichste(String nameNormalisiert,
                                                                                 BigDecimal untergrenze, int anzahl) {
            throw new UnsupportedOperationException();
        }
    }
}
