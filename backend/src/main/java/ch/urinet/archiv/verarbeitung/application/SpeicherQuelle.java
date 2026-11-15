package ch.urinet.archiv.verarbeitung.application;

import java.util.List;

/** Driven Port: liest den Stand der Speicherbereiche der eigenen JVM (Metaspace, Class Space, Heap nach GC). */
public interface SpeicherQuelle {

    List<SpeicherMessung> messen();
}
