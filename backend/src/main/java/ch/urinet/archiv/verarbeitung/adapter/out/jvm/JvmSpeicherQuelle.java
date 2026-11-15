package ch.urinet.archiv.verarbeitung.adapter.out.jvm;

import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import ch.urinet.archiv.verarbeitung.application.SpeicherQuelle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Liest über {@code java.lang.management} den Metaspace, den Compressed Class Space und den Heap. Der Heap zählt, was
 * die letzte Garbage Collection übrig liess ({@code getCollectionUsage}, summiert über die Heap-Bereiche), nicht den
 * momentanen Stand, der zwischen zwei Collections ohnehin ansteigt; sein Maximum ist {@code -Xmx}. Metaspace ohne
 * {@code -XX:MaxMetaspaceSize} meldet kein Maximum (-1).
 */
@ApplicationScoped
public class JvmSpeicherQuelle implements SpeicherQuelle {

    public static final String METASPACE = "Metaspace";
    public static final String CLASS_SPACE = "Compressed Class Space";
    public static final String HEAP = "Heap";

    private final Supplier<List<MemoryPoolMXBean>> bereiche;

    @Inject
    public JvmSpeicherQuelle() {
        this(ManagementFactory::getMemoryPoolMXBeans);
    }

    JvmSpeicherQuelle(Supplier<List<MemoryPoolMXBean>> bereiche) {
        this.bereiche = bereiche;
    }

    @Override
    public List<SpeicherMessung> messen() {
        List<SpeicherMessung> ergebnis = new ArrayList<>();
        long heapNachGc = 0;
        long heapMaximum = 0;
        boolean heapMaximumBekannt = true;
        boolean heapGesehen = false;
        for (MemoryPoolMXBean bereich : bereiche.get()) {
            if (bereich.getType() == MemoryType.HEAP) {
                heapGesehen = true;
                MemoryUsage nachGc = bereich.getCollectionUsage();
                heapNachGc += nachGc == null ? 0 : Math.max(0, nachGc.getUsed());
                long max = bereich.getUsage() == null ? -1 : bereich.getUsage().getMax();
                if (max > 0) {
                    heapMaximum += max;
                } else {
                    heapMaximumBekannt = false;
                }
            } else if (METASPACE.equals(bereich.getName()) || CLASS_SPACE.equals(bereich.getName())) {
                MemoryUsage stand = bereich.getUsage();
                if (stand != null) {
                    ergebnis.add(new SpeicherMessung(bereich.getName(), stand.getUsed(), stand.getMax()));
                }
            }
        }
        if (heapGesehen) {
            ergebnis.add(new SpeicherMessung(HEAP, heapNachGc, heapMaximumBekannt ? heapMaximum : -1));
        }
        return ergebnis;
    }
}
