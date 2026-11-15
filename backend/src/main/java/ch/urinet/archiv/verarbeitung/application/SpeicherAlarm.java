package ch.urinet.archiv.verarbeitung.application;

/**
 * Driven Port: meldet dem Inhaber knappen Speicher der JVM (Req 16.5). Die Nachricht nennt nur Bereich, Prozent und
 * Mengen, nie Inhalte oder Betriebsangaben (Req 16.7).
 */
public interface SpeicherAlarm {

    /** {@code true} nur, wenn die Mail zugestellt wurde; ohne Empfänger {@code false}. */
    boolean speicherKnapp(SpeicherUeberwachung.Stufe stufe, SpeicherMessung messung);
}
