package ch.urinet.archiv.gemeinsam;

import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;

import static org.assertj.core.api.Assertions.assertThat;

public class FehlerantwortLogSchritte {
    private LogMitschnitt log;

    @Angenommen("der Logmitschnitt für eine Fehlerantwort läuft")
    public void starten() {
        log = LogMitschnitt.starten();
    }

    @Dann("das Fehlerantwort-Log enthält weder «{}» noch einen Stacktrace")
    public void ohneInhalt(String marker) {
        String text = log.volltext();
        assertThat(text.contains(marker)).as("Log enthält den synthetischen Marker").isFalse();
        assertThat(text.contains("\tat ") || text.contains("Caused by:")).as("Log enthält einen Stacktrace").isFalse();
    }

    @After
    public void schliessen() {
        if (log != null) {
            log.close();
            log = null;
        }
    }
}
