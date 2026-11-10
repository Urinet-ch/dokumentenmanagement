package ch.urinet.archiv.gemeinsam;

import org.jboss.logmanager.ExtLogRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Schneidet alles mit, was die Anwendung tatsächlich loggt (Root-Logger, nach den Stufen aus
 * {@code application.properties}), samt Stacktraces – für Prüfungen nach Req 16.7: kein Wert, kein Inhalt im Log.
 */
public final class LogMitschnitt implements AutoCloseable {

    private final List<LogRecord> eintraege = new CopyOnWriteArrayList<>();
    private final SimpleFormatter format = new SimpleFormatter();
    private final Handler mithoerer = new Handler() {
        @Override
        public void publish(LogRecord eintrag) {
            eintraege.add(eintrag);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private LogMitschnitt() {
        mithoerer.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(mithoerer);
    }

    public static LogMitschnitt starten() {
        return new LogMitschnitt();
    }

    public List<LogRecord> eintraege() {
        return List.copyOf(eintraege);
    }

    /** Alle Einträge als Text: Logger, formatierte Nachricht und Stacktrace samt Ursachen. */
    public String volltext() {
        StringBuilder text = new StringBuilder();
        for (LogRecord eintrag : eintraege) {
            text.append(eintrag.getLoggerName()).append(' ').append(nachricht(eintrag)).append('\n');
            if (eintrag.getThrown() != null) {
                StringWriter stack = new StringWriter();
                eintrag.getThrown().printStackTrace(new PrintWriter(stack));
                text.append(stack).append('\n');
            }
        }
        return text.toString();
    }

    public String nachricht(LogRecord eintrag) {
        return eintrag instanceof ExtLogRecord ext ? ext.getFormattedMessage() : format.formatMessage(eintrag);
    }

    @Override
    public void close() {
        Logger.getLogger("").removeHandler(mithoerer);
    }
}
