package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenLintRepository.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenLintSpeichern;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcWissenLintTest {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");

    @Inject
    JdbcWissenLint lint;
    @Inject
    WissenLintSpeichern speichern;
    @Inject
    MandantKontext kontext;
    @Inject
    MandantProvisionierung provisionierung;
    @Inject
    Instance<EntityManager> entityManager;
    @Inject
    AgroalDataSource datenbank;

    @Test
    void fortschrittBerichtUndPaarzeitenBleibenImMandanten() throws Exception {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            try (var verbindung = datenbank.getConnection();
                 var abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, code.wert());
                try (var zeilen = abfrage.executeQuery()) {
                    if (!zeilen.next()) {
                        provisionierung.anlegen(code.wert(), null, null);
                    }
                }
            }
        }
        var laufenderTest = CompletableFuture.runAsync(() -> kontext.mit(URINET,
                () -> QuarkusTransaction.requiringNew().run(() -> {
            UUID id = lint.anlegen("process:wissen-lint", "synthetisch");
            var lauf = lint.laden(id).orElseThrow();
            UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
            var paar = new WissensLint.PaarId(a, b);
            for (UUID seite : List.of(a, b)) {
                entityManager.get().createNativeQuery("""
                        insert into wissensseite
                            (id, pfad, typ, titel, beschreibung, tags, status, vertraulichkeit, inhalt, version,
                             erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                        values (:id, :pfad, 'Konzept', 'Probe', 'Probe', array['probe', 'test'],
                                'AKTIV', 'NORMAL', '# Probe', 1, 'probe', now(), 'probe', now())
                        """).setParameter("id", seite).setParameter("pfad", "konzepte/probe-" + seite).executeUpdate();
            }
            UUID dokument = UUID.randomUUID();
            entityManager.get().createNativeQuery("""
                    insert into dokument (id, art, titel, quelle, vertraulichkeit, erstellt_von, status,
                                          bestaetigt, bestaetigt_von, seiten)
                    values (:id, 'PROJEKTDOKUMENT', 'Probe', 'UPLOAD', 'NORMAL', 'probe', 'ARCHIVIERT',
                            now(), 'probe', 1)
                    """).setParameter("id", dokument).executeUpdate();
            entityManager.get().createNativeQuery("""
                    insert into wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von)
                    values (:seite, 'q-probe', :dokument, 1)
                    """).setParameter("seite", a).setParameter("dokument", dokument).executeUpdate();
            assertThat(speichern.planen(LocalDate.now()).paare()).anyMatch(p -> p.id().equals(paar));
            assertThat(speichern.paarLaden(paar)).isPresent();
            entityManager.get().createNativeQuery("update dokument set vertraulichkeit = 'PERSONAL' where id = :id")
                    .setParameter("id", dokument).executeUpdate();
            assertThat(speichern.paarLaden(paar)).isEmpty();
            entityManager.get().createNativeQuery("""
                    insert into wissens_einstellung (id, lint_budget_tokens, monatsbudget_tokens)
                    values (1, 100, 1000000)
                    on conflict (id) do update set lint_budget_tokens = 100, monatsbudget_tokens = 1000000
                    """).executeUpdate();
            entityManager.get().createNativeQuery("""
                    insert into ki_verbrauch (zweck, modell, tokens_ein, tokens_aus, ingest_entwurf_id)
                    values ('WISSEN_LINT', 'synthetisch', 60, 10, :entwurf)
                    """).setParameter("entwurf", lauf.entwurfId()).executeUpdate();
            assertThat(speichern.budgetReservieren(lauf.entwurfId(), 31, Instant.now()).reserviert()).isFalse();
            var reservierung = speichern.budgetReservieren(lauf.entwurfId(), 30, Instant.now());
            assertThat(reservierung.reserviert()).isTrue();
            assertThat(speichern.budgetReservieren(lauf.entwurfId(), 1, Instant.now()).reserviert()).isFalse();
            speichern.budgetFreigeben(reservierung.id());
            var stand = new Fortschritt(List.of(new WissensLint.Befund(a, "VERWAIST", null)),
                    List.of(), 0, List.of(), 0, List.of(paar), WissensStufe.INTERN, null, false, List.of(), List.of());
            lint.fortschritt(id, "PAAR", stand, 10, 4);
            assertThat(lint.laden(id).orElseThrow().fortschritt()).isEqualTo(stand);
            assertThat(lint.lintVerbrauch(lauf.entwurfId())).isEqualTo(70);
            lint.abschliessen(id, stand, 10, 4, false, null, false);
            assertThat(lint.laden(id)).isEmpty();
            assertThat(lint.paarZeiten()).containsKey(paar);
            Object anzahl = entityManager.get().createNativeQuery("""
                    select (angaben->>'VERWAIST')::integer from wissens_log
                    where aktion = 'LINT' and seiten = array[cast(:id as uuid)]
                    """).setParameter("id", a).getSingleResult();
            assertThat(((Number) anzahl).intValue()).isEqualTo(1);

            UUID fremd = UUID.randomUUID();
            UUID fremderEntwurf = UUID.randomUUID();
            entityManager.get().createNativeQuery("""
                    insert into m_vhf_gsk.wissens_entwurf
                        (id, anlass, status, schritt, ki_modell, erstellt_von, erstellt)
                    values (:entwurf, 'LINT', 'IN_ARBEIT', 'PLANEN', 'synthetisch', 'probe', now())
                    """).setParameter("entwurf", fremderEntwurf).executeUpdate();
            entityManager.get().createNativeQuery("""
                    insert into m_vhf_gsk.wissens_lint (id, gestartet, ausgeloest_von, befunde, entwurf_id)
                    values (:id, now(), 'probe', '[]'::jsonb, :entwurf)
                    """).setParameter("id", fremd).setParameter("entwurf", fremderEntwurf).executeUpdate();
            assertThat(lint.laden(fremd)).isEmpty();
            QuarkusTransaction.setRollbackOnly();
        })));
        assertThatThrownBy(() -> laufenderTest.get(2, TimeUnit.MINUTES))
                .hasStackTraceContaining("setRollbackOnly called from");
    }
}
