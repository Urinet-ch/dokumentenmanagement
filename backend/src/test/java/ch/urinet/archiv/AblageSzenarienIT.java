package ch.urinet.archiv;

import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Nur die Ablage-Szenarien ({@code features/ablage/}), für den Lauf gegen Ceph RGW (.github/workflows/ceph.yml,
 * Profil {@code ceph}). Der Name endet auf {@code IT}: Surefire führt ihn nur mit {@code -Dtest=AblageSzenarienIT} aus,
 * in der Standard-CI laufen dieselben Szenarien über {@link SzenarienTest} gegen MinIO.
 */
@QuarkusTest
@CucumberOptions(features = "classpath:features/ablage", glue = "ch.urinet.archiv", tags = "not @browser")
public class AblageSzenarienIT extends CucumberQuarkusTest {
}
