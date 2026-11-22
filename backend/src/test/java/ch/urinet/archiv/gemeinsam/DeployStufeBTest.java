package ch.urinet.archiv.gemeinsam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DeployStufeBTest {

    private static JsonNode dienste;

    @BeforeAll
    static void composeRendern(@TempDir Path temp) throws Exception {
        Path ausgabe = temp.resolve("compose.json");
        Process prozess = new ProcessBuilder("docker", "compose", "--env-file", "/dev/null",
                "-f", "deploy/infomaniak/compose.yml", "-f", "deploy/infomaniak/compose.stufe-b.yml",
                "config", "--format", "json")
                .directory(GemeinsamSchritte.REPO.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ausgabe.toFile())
                .start();
        assertThat(prozess.waitFor(30, TimeUnit.SECONDS)).as("Compose-Konfiguration").isTrue();
        assertThat(prozess.exitValue()).as("Compose-Konfiguration: %s", Files.readString(ausgabe)).isZero();
        dienste = new ObjectMapper().readTree(ausgabe.toFile()).path("services");
    }

    @Test
    void haertungUndBudget() {
        BigDecimal cpus = BigDecimal.ZERO;
        long bytes = 0;
        for (String name : new String[]{"api", "worker", "db", "kc"}) {
            JsonNode dienst = dienste.path(name);
            assertThat(dienst.isMissingNode()).as(name).isFalse();
            assertThat(dienst.path("user").asText()).as(name).matches("[1-9]\\d*(:\\d+)?");
            assertThat(dienst.path("read_only").asBoolean()).as(name).isTrue();
            assertThat(dienst.path("cap_drop").toString()).as(name).contains("\"ALL\"");
            assertThat(dienst.path("security_opt").toString()).as(name).contains("no-new-privileges");
            assertThat(dienst.path("mem_limit").asLong()).as(name).isPositive();
            assertThat(dienst.path("cpus").decimalValue()).as(name).isPositive();
            assertThat(dienst.has("ports") || dienst.has("privileged") || dienst.has("cap_add")
                    || dienst.has("network_mode") || dienst.has("env_file")).as(name).isFalse();
            bytes += dienst.path("mem_limit").asLong();
            cpus = cpus.add(dienst.path("cpus").decimalValue());
        }
        assertThat(bytes + 256L * 1024 * 1024).isLessThanOrEqualTo(3200L * 1024 * 1024);
        assertThat(cpus.add(new BigDecimal("0.25"))).isLessThanOrEqualTo(new BigDecimal("4.5"));
    }

    @Test
    void getrennteRollenUndSichereStartreihenfolge() {
        JsonNode api = dienste.path("api");
        JsonNode worker = dienste.path("worker");
        assertThat(api.path("environment").path("ARCHIV_ROLLE").asText()).isEqualTo("api");
        assertThat(worker.path("environment").path("ARCHIV_ROLLE").asText()).isEqualTo("worker");
        for (JsonNode dienst : new JsonNode[]{api, worker}) {
            JsonNode env = dienst.path("environment");
            assertThat(env.path("ARCHIV_STUFE").asText()).isEqualTo("B");
            assertThat(env.path("ARCHIV_ABLAGE_BUCKET_PRAEFIX").asText()).isEqualTo("urinet-archiv-");
            assertThat(env.path("ARCHIV_ABLAGE_MANIFEST_SPERRMODUS").asText()).isEqualTo("COMPLIANCE");
            assertThat(env.has("ARCHIV_ABLAGE_MAX_SPERRFRIST")).isFalse();
            assertThat(env.has("ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST")).isFalse();
        }
        assertThat(worker.path("depends_on").path("api").path("condition").asText()).isEqualTo("service_healthy");
        assertThat(worker.path("environment").path("ARCHIV_WORKER_PARALLEL").asText()).isEqualTo("1");
        assertThat(worker.path("environment").path("QUARKUS_DATASOURCE_JDBC_MAX_SIZE").asText()).isEqualTo("8");
        assertThat(api.path("environment").path("QUARKUS_DATASOURCE_JDBC_MAX_SIZE").asText()).isEqualTo("16");
        assertThat(worker.path("networks").has("intern")).isTrue();
        assertThat(worker.path("networks").has("web")).isFalse();
        assertThat(worker.path("secrets").toString()).doesNotContain("archiv_db_owner");
        assertThat(dienste.path("db").path("command").toString()).contains("max_connections=50");
    }
}
