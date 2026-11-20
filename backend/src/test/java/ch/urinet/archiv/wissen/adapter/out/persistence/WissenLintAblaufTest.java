package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.adapter.out.ki.JacksonWissenLintSerialisierung;
import ch.urinet.archiv.wissen.application.DiffDienst;
import ch.urinet.archiv.wissen.application.WissenLintSpeichern;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import ch.urinet.archiv.wissen.application.WissenLintVerarbeiten;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class WissenLintAblaufTest {
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;
    @Inject Instance<EntityManager> entityManager;
    @Inject WissenLintStarten starten;
    @Inject WissenLintSpeichern speichern;
    @Inject DiffDienst diff;
    @Inject JacksonWissenLintSerialisierung json;

    @BeforeEach
    void mandantAnlegen() throws Exception {
        try (var verbindung = datenbank.getConnection();
             var abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, MANDANT.wert());
            try (var zeilen = abfrage.executeQuery()) {
                if (!zeilen.next()) {
                    provisionierung.anlegen(MANDANT.wert(), null, null);
                }
            }
        }
    }

    @Test
    void gemischtesPaarErzeugtEinenEntwurfOhneDokumentOderSeitenZuAendern() {
        ablauf(false);
    }

    @Test
    void nachPersoenlichkeitswechselKeinWeitererKiAufrufUndKeinVorschlag() {
        ablauf(true);
    }

    private void ablauf(boolean personalNachPaar) {
        var laufenderTest = CompletableFuture.runAsync(() -> kontext.mit(MANDANT,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    EntityManager em = entityManager.get();
                    UUID dokumentA = dokument(em, "NORMAL");
                    UUID dokumentB = dokument(em, "INTERN");
                    seite(em, A, "lint-a", "NORMAL", "q-a", dokumentA);
                    seite(em, B, "lint-b", "INTERN", "q-b", dokumentB);
                    @SuppressWarnings("unchecked")
                    List<String> vorher = em.createNativeQuery("""
                            select row_to_json(d)::text from dokument d where id in (:a, :b) order by id
                            """).setParameter("a", dokumentA).setParameter("b", dokumentB).getResultList();
                    UUID lintId = starten.manuell("probe");
                    AtomicInteger aufrufe = new AtomicInteger();
                    KiPort ki = new TestKi(aufrufe, () -> {
                        if (personalNachPaar) {
                            em.createNativeQuery("update dokument set vertraulichkeit = 'PERSONAL' where id = :id")
                                    .setParameter("id", dokumentA).executeUpdate();
                        }
                    });
                    KiVerbrauchPort verbrauch = new KiVerbrauchPort() {
                        @Override
                        public void erfassen(String zweck, KiPort.Verbrauch v) {
                            throw new AssertionError("Entwurf muss zugeordnet werden");
                        }

                        @Override
                        public void erfassen(String zweck, KiPort.Verbrauch v, UUID entwurf) {
                            em.createNativeQuery("""
                                    insert into ki_verbrauch (zweck, modell, tokens_ein, tokens_aus, ingest_entwurf_id)
                                    values (:zweck, 'synthetisch', 10, 5, :entwurf)
                                    """).setParameter("zweck", zweck).setParameter("entwurf", entwurf).executeUpdate();
                        }
                    };
                    var verarbeitung = new WissenLintVerarbeiten(speichern, ki, verbrauch, diff, json);
                    int schritte = 0;
                    while (speichern.laden(lintId).isPresent() && schritte++ < 15) {
                        @SuppressWarnings("unchecked")
                        List<Object[]> offene = em.createNativeQuery("""
                                select id, versuche from public.auftrag
                                where typ = 'WISSEN_LINT' and nutzlast->>'lintId' = :id and status = 'OFFEN'
                                order by id
                                """).setParameter("id", lintId.toString()).getResultList();
                        assertThat(offene).hasSize(1);
                        long auftragId = ((Number) offene.getFirst()[0]).longValue();
                        int versuche = ((Number) offene.getFirst()[1]).intValue();
                        em.createNativeQuery("update public.auftrag set status = 'LAEUFT' where id = :id")
                                .setParameter("id", auftragId).executeUpdate();
                        var auftrag = new Auftrag(auftragId, MANDANT, WissenLintSpeichern.TYP,
                                new Nutzlast(Map.of(WissenLintSpeichern.LINT_ID, lintId)), versuche);
                        int vorherigeAufrufe = aufrufe.get();
                        verarbeitung.ausfuehren(auftrag, lintId);
                        assertThat(aufrufe.get() - vorherigeAufrufe).isBetween(0, 1);
                        em.createNativeQuery("update public.auftrag set status = 'FERTIG' where id = :id")
                                .setParameter("id", auftragId).executeUpdate();
                    }
                    assertThat(speichern.laden(lintId)).isEmpty();
                    assertThat(aufrufe).hasValue(personalNachPaar ? 1 : 2);
                    @SuppressWarnings("unchecked")
                    List<Object[]> aenderungen = em.createNativeQuery("""
                            select a.seite_id, a.vertraulichkeit_kontext, a.basis_version, a.inhalt
                            from wissens_lint l join wissens_aenderung a on a.entwurf_id = l.entwurf_id
                            where l.id = :id
                            """).setParameter("id", lintId).getResultList();
                    if (personalNachPaar) {
                        assertThat(aenderungen).isEmpty();
                    } else {
                        assertThat(aenderungen).hasSize(1);
                        assertThat(aenderungen.getFirst()[0]).isEqualTo(B);
                        assertThat(aenderungen.getFirst()[1]).isEqualTo("INTERN");
                        assertThat(((Number) aenderungen.getFirst()[2]).intValue()).isEqualTo(1);
                        assertThat(aenderungen.getFirst()[3].toString()).contains("[^q-a]", "[^q-b]");
                    }
                    @SuppressWarnings("unchecked")
                    List<String> nachher = em.createNativeQuery("""
                            select row_to_json(d)::text from dokument d where id in (:a, :b) order by id
                            """).setParameter("a", dokumentA).setParameter("b", dokumentB).getResultList();
                    if (!personalNachPaar) {
                        assertThat(nachher).isEqualTo(vorher);
                    }
                    assertThat(((Number) em.createNativeQuery("""
                            select count(*) from wissensseite where id in (:a, :b) and version = 1
                            """).setParameter("a", A).setParameter("b", B).getSingleResult()).intValue()).isEqualTo(2);
                    assertThat(((Number) em.createNativeQuery("""
                            select count(*) from ki_verbrauch v join wissens_lint l
                                on v.ingest_entwurf_id = l.entwurf_id
                            where l.id = :id and v.zweck = 'WISSEN_LINT'
                            """).setParameter("id", lintId).getSingleResult()).intValue())
                            .isEqualTo(personalNachPaar ? 1 : 2);
                    QuarkusTransaction.setRollbackOnly();
                })));
        assertThatThrownBy(() -> laufenderTest.get(2, TimeUnit.MINUTES))
                .hasStackTraceContaining("setRollbackOnly called from");
    }

    private static UUID dokument(EntityManager em, String vertraulichkeit) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
                insert into dokument (id, art, titel, quelle, vertraulichkeit, erstellt_von, status,
                                      bestaetigt, bestaetigt_von, seiten, volltext)
                values (:id, 'WISSENSQUELLE', 'Synthetische Quelle', 'UPLOAD', :stufe, 'probe',
                        'ARCHIVIERT', now(), 'probe', 1, 'Synthetischer Volltext')
                """).setParameter("id", id).setParameter("stufe", vertraulichkeit).executeUpdate();
        return id;
    }

    private static void seite(EntityManager em, UUID id, String pfad, String stufe, String schluessel, UUID dokument) {
        em.createNativeQuery("""
                insert into wissensseite (id, pfad, typ, titel, beschreibung, tags, status, vertraulichkeit,
                    vertraulichkeit_mindest, inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                values (:id, :pfad, 'Konzept', 'Probe', 'Probe', array['lintprobe', 'synthetisch'], 'AKTIV',
                    :stufe, :stufe, :inhalt, 1, 'probe', now(), 'probe', now())
                """).setParameter("id", id).setParameter("pfad", "konzepte/" + pfad).setParameter("stufe", stufe)
                .setParameter("inhalt", "Aussage. [^" + schluessel + "]").executeUpdate();
        em.createNativeQuery("""
                insert into wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von)
                values (:seite, :schluessel, :dokument, 1)
                """).setParameter("seite", id).setParameter("schluessel", schluessel)
                .setParameter("dokument", dokument).executeUpdate();
    }

    private record TestKi(AtomicInteger aufrufe, Runnable nachPaar) implements KiPort {
        @Override public Antwort metadaten(Anfrage anfrage) { throw new UnsupportedOperationException(); }
        @Override public String antwortschema() { return ""; }
        @Override public List<String> artCodes() { return List.of(); }

        @Override
        public WissensAntwort wissensIngest(WissensAnfrage anfrage) {
            aufrufe.incrementAndGet();
            WissensInhalt inhalt = switch (anfrage.schritt()) {
                case LINT_PAAR -> new LintPaar(List.of(new LintWiderspruch(
                        A.toString(), "q-a", B.toString(), "q-b", "Zwei Angaben unterscheiden sich.")),
                        List.of(), List.of());
                case SEITE -> new Seite("Probe", "Probe", List.of("lintprobe", "synthetisch"),
                        "Aussage. [^q-b]\n\n> ⚠️ Widerspruch: Beide Angaben unterscheiden sich. [^q-a] [^q-b]",
                        List.of(new Widerspruch("q-b", "q-a", "Unterschied")), List.of(), "Prüfung");
                default -> throw new AssertionError("Unerwarteter KI-Schritt");
            };
            if (anfrage.schritt() == WissensSchritt.LINT_PAAR) {
                nachPaar.run();
            }
            return new WissensGueltig(inhalt, new Verbrauch("synthetisch", 10, 5));
        }
    }
}
