package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Negativtests zur Secret-Prüfung der Workflows (Req 16.7, Nachbesserung PR #42). */
class WorkflowSecretsTest {

    private static final Set<String> ERLAUBT = Set.of("ARCHIV_DB_APP");

    @ParameterizedTest
    @ValueSource(strings = {
            "env:\n  X: ${{ secrets.ARCHIV_DB_APP }}",
            "env:\n  X: ${{ secrets.archiv_db_app }}",
            "run: printf '%s' \"$X\" > \"bundle/secrets/archiv_db_app\"",
            "run: ls bundle/secrets",
            "# Eine Datei je Secret"})
    void erlaubt(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, ERLAUBT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "env:\n  X: ${{ toJSON(secrets.*) }}",
            "    secrets: inherit",
            "env:\n  X: ${{ secrets['ARCHIV_DB_APP'] }}",
            "env:\n  X: ${{ toJSON(secrets) }}",
            "env:\n  X: ${{ secrets.KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ secrets . KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ SECRETS.KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ format('{0}', secrets) }}",
            "run: cat bundle/secrets.json",
            "# Secrets im Fliesstext"})
    void unerlaubt(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, ERLAUBT)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"env:\n  X: ${{ secrets.ARCHIV_DB_APP }}", "run: ls bundle/secrets"})
    void ohneFreigabeIstJedesVorkommenEinFund(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, Set.of())).hasSize(1);
    }
}
