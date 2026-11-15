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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Liest über {@code java.lang.management} den Metaspace, den Compressed Class Space und den Heap. Der Heap zählt, was
 * die letzte Garbage Collection übrig liess ({@code getCollectionUsage}, summiert über die Heap-Bereiche), nicht den
 * momentanen Stand, der zwischen zwei Collections ohnehin ansteigt; sein Maximum ist {@code -Xmx}
 * (aus {@code MemoryMXBean.getHeapMemoryUsage().getMax()}; die Pools von G1 melden zum Teil kein Maximum, darum nicht die
 * Summe der Pool-Maxima). Metaspace ohne
 * {@code -XX:MaxMetaspaceSize} meldet kein Maximum (-1).
 */
@ApplicationScoped
public class JvmSpeicherQuelle implements SpeicherQuelle {

    public static final String METASPACE = "Metaspace";
    public static final String CLASS_SPACE = "Compressed Class Space";
    public static final String HEAP = "Heap";

    private final Supplier<List<MemoryPoolMXBean>> bereiche;
    private final LongSupplier heapMaximum;

    @Inject
    public JvmSpeicherQuelle() {
        this(ManagementFactory::getMemoryPoolMXBeans,
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
    }

    JvmSpeicherQuelle(Supplier<List<MemoryPoolMXBean>> bereiche, LongSupplier heapMaximum) {
        this.bereiche = bereiche;
        this.heapMaximum = heapMaximum;
    }

    @Override
    public List<SpeicherMessung> messen() {
        List<SpeicherMessung> ergebnis = new ArrayList<>();
        long heapNachGc = 0;
        boolean heapGesehen = false;
        for (MemoryPoolMXBean bereich : bereiche.get()) {
            if (bereich.getType() == MemoryType.HEAP) {
                heapGesehen = true;
                MemoryUsage nachGc = bereich.getCollectionUsage();
                heapNachGc += nachGc == null ? 0 : Math.max(0, nachGc.getUsed());
            } else if (METASPACE.equals(bereich.getName()) || CLASS_SPACE.equals(bereich.getName())) {
                MemoryUsage stand = bereich.getUsage();
                if (stand != null) {
                    ergebnis.add(new SpeicherMessung(bereich.getName(), stand.getUsed(), stand.getMax()));
                }
            }
        }
        if (heapGesehen) {
            ergebnis.add(new SpeicherMessung(HEAP, heapNachGc, Math.max(-1, heapMaximum.getAsLong())));
        }
        return ergebnis;
    }
}
