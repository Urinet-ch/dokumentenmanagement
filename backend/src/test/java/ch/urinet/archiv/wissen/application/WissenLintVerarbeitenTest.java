package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.WissenLintRepository.AuftragSeite;
import ch.urinet.archiv.wissen.application.WissenLintRepository.Bezug;
import ch.urinet.archiv.wissen.application.WissenLintRepository.Fortschritt;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensLint.PaarId;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLintVerarbeitenTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void paareMitUnausgefuehrtemSeitenauftragGeltenNichtAlsGeprueft() {
        var ausgefuehrt = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var offen = new AuftragSeite(C, 1, 1, List.of(new Bezug(A, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var stand = new Fortschritt(List.of(), List.of(), 2, List.of(ausgefuehrt, offen), 1,
                List.of(new PaarId(A, B), new PaarId(A, C)), null, null, false, List.of(), List.of());

        assertThat(WissenLintVerarbeiten.ohneUnausgefuehrte(stand)).containsExactly(new PaarId(A, B));
    }

    @Test
    void nachVollstaendigemLaufBleibenAllePaareGeprueft() {
        var seite = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var stand = new Fortschritt(List.of(), List.of(), 1, List.of(seite), 1,
                List.of(new PaarId(A, B)), null, null, false, List.of(), List.of());

        assertThat(WissenLintVerarbeiten.ohneUnausgefuehrte(stand)).containsExactly(new PaarId(A, B));
    }

    @Test
    void uebersprungenerSeitenauftragEntferntSeinePaareAusGeprueft() {
        var a = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var c = new AuftragSeite(C, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var stand = new Fortschritt(List.of(), List.of(), 2, List.of(a, c), 0,
                List.of(new PaarId(A, B), new PaarId(B, C)), null, null, false, List.of(), List.of());

        var weiter = WissenLintVerarbeiten.hinweis(stand, "x", false);

        assertThat(weiter.seitenIndex()).isEqualTo(1);
        assertThat(weiter.geprueft()).containsExactly(new PaarId(B, C));
        assertThat(WissenLintVerarbeiten.ohneUnausgefuehrte(stand)).isEmpty();
    }

    @Test
    void letzterFehlversuchSchliesstNurAusgefuehrtePaareAlsGeprueftAb() {
        var ausgefuehrt = new AuftragSeite(A, 1, 1, List.of(new Bezug(B, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var offen = new AuftragSeite(C, 1, 1, List.of(new Bezug(A, 1, 1)), WissensStufe.NORMAL, List.of("x"));
        var fortschritt = new Fortschritt(List.of(), List.of(), 2, List.of(ausgefuehrt, offen), 1,
                List.of(new PaarId(A, B), new PaarId(A, C)), WissensStufe.NORMAL, null, false, List.of(), List.of());
        var repository = new AbschlussRepository(new WissenLintRepository.Lauf(A, B, "test", "test",
                "SEITE", 10, 5, fortschritt));
        var auftraege = new AuftragService(null, null, null, null) {
            @Override public boolean nachfolgerVorhanden(Auftrag auftrag, String schluessel) { return false; }
        };
        var ingest = WissenIngestPort.class.cast(Proxy.newProxyInstance(WissenIngestPort.class.getClassLoader(),
                new Class<?>[]{WissenIngestPort.class}, (proxy, methode, args) -> {
                    throw new AssertionError("Unerwarteter Aufruf: " + methode.getName());
                }));
        var speichern = new WissenLintSpeichern(repository, ingest, null, null, null, auftraege, null, 100, 30);
        var auftrag = new Auftrag(1, new MandantCode("urinet"), WissenLintSpeichern.TYP, Nutzlast.leer(), 3);

        speichern.nachFehlversuch(auftrag, A);

        assertThat(repository.unvollstaendig).isTrue();
        assertThat(repository.abgeschlossen.geprueft()).containsExactly(new PaarId(A, B));
        assertThat(repository.abgeschlossen.befunde()).extracting(WissensLint.Befund::code)
                .contains("LINT_UNVOLLSTAENDIG");
    }

    /** Spec 04, Req 22.9: auch der Schritt ABSCHLUSS speichert nach dem Abschalten nichts und endet mit dem Grund. */
    @Test
    @org.junit.jupiter.api.Tag("req-22.9")
    void abschlussNachDemAbschaltenSpeichertNichtsUndEndetMitDemGrund() {
        var aenderung = new WissenIngestPort.NeueAenderung(C, 1, "AENDERN", A, "regeln/probe", 1, null, "Lint",
                List.of(), List.of(), List.of(), List.of(), null, null);
        var fortschritt = new Fortschritt(List.of(), List.of(), 0, List.of(), 0, List.of(), WissensStufe.NORMAL, null,
                false, List.of(), List.of(aenderung));
        var repository = new AbschlussRepository(new WissenLintRepository.Lauf(A, B, "test", "test",
                "ABSCHLUSS", 10, 5, fortschritt));
        var auftraege = new AuftragService(null, null, null, null) {
            @Override public void anspruchSichern(Auftrag auftrag) { }
            @Override public boolean nachfolgerVorhanden(Auftrag auftrag, String schluessel) { return false; }
        };
        var ingest = WissenIngestPort.class.cast(Proxy.newProxyInstance(WissenIngestPort.class.getClassLoader(),
                new Class<?>[]{WissenIngestPort.class}, (proxy, methode, args) -> {
                    throw new AssertionError("Unerwarteter Aufruf: " + methode.getName());
                }));
        var speichern = new WissenLintSpeichern(repository, ingest, null, null, null, auftraege, null, 100, 30);
        var auftrag = new Auftrag(1, new MandantCode("urinet"), WissenLintSpeichern.TYP, Nutzlast.leer(), 1);

        new WissenLintVerarbeiten(speichern, null, null, null, null, ch.urinet.archiv.wissen.FesteWissensfreischaltung.aus())
                .ausfuehren(auftrag, A);

        assertThat(repository.unvollstaendig).isTrue();
        assertThat(repository.hinweis).isEqualTo(Wissensfreischaltung.GRUND_AUSGESCHALTET);
        assertThat(repository.abgeschlossen.aenderungen()).isEmpty();
    }

    private static final class AbschlussRepository implements WissenLintRepository {
        private final Lauf lauf;
        private Fortschritt abgeschlossen;
        private boolean unvollstaendig;
        private String hinweis;

        private AbschlussRepository(Lauf lauf) { this.lauf = lauf; }
        @Override public Optional<Lauf> laden(UUID id) { return id.equals(lauf.id()) ? Optional.of(lauf) : Optional.empty(); }
        @Override public void abschliessen(UUID id, Fortschritt stand, long ein, long aus, boolean unvollstaendig,
                                          String hinweis, boolean hatAenderungen) {
            assertThat(id).isEqualTo(lauf.id());
            assertThat(ein).isEqualTo(10);
            assertThat(aus).isEqualTo(5);
            assertThat(hatAenderungen).isFalse();
            this.abgeschlossen = stand;
            this.unvollstaendig = unvollstaendig;
            this.hinweis = hinweis;
        }
        @Override public void budgetsperre() { throw new UnsupportedOperationException(); }
        @Override public long lintVerbrauch(UUID id) { throw new UnsupportedOperationException(); }
        @Override public List<Wissensseite> seitenLaden() { throw new UnsupportedOperationException(); }
        @Override public UUID anlegen(String akteur, String modell) { throw new UnsupportedOperationException(); }
        @Override public boolean zeitplanBereits(Instant termin) { throw new UnsupportedOperationException(); }
        @Override public void fortschritt(UUID id, String schritt, Fortschritt stand, long ein, long aus) {
            throw new UnsupportedOperationException();
        }
        @Override public Map<PaarId, Instant> paarZeiten() { throw new UnsupportedOperationException(); }
    }
}
