package ch.urinet.archiv.verarbeitung.adapter.out.jvm;

import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Quelle liest Metaspace, Class Space und den Heap nach der letzten Garbage Collection (Req 16.5). */
class JvmSpeicherQuelleTest {

    private static MemoryPoolMXBean bereich(String name, MemoryType typ, MemoryUsage aktuell, MemoryUsage nachGc) {
        return (MemoryPoolMXBean) Proxy.newProxyInstance(MemoryPoolMXBean.class.getClassLoader(),
                new Class<?>[]{MemoryPoolMXBean.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "getName" -> name;
                    case "getType" -> typ;
                    case "getUsage" -> aktuell;
                    case "getCollectionUsage" -> nachGc;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    @Test
    @Tag("req-16.5")
    void heapZaehltNachGcUndSummiertDieBereiche() {
        JvmSpeicherQuelle quelle = new JvmSpeicherQuelle(() -> List.of(
                bereich("G1 Eden Space", MemoryType.HEAP, new MemoryUsage(0, 900, 900, 1000), new MemoryUsage(0, 0, 0, 1000)),
                bereich("G1 Old Gen", MemoryType.HEAP, new MemoryUsage(0, 800, 900, 1000), new MemoryUsage(0, 300, 300, 1000)),
                bereich("Code Cache", MemoryType.NON_HEAP, new MemoryUsage(0, 5, 5, 10), null),
                bereich("Metaspace", MemoryType.NON_HEAP, new MemoryUsage(0, 150, 160, -1), null),
                bereich("Compressed Class Space", MemoryType.NON_HEAP, new MemoryUsage(0, 20, 30, 100), null)));

        assertThat(quelle.messen()).containsExactlyInAnyOrder(
                new SpeicherMessung("Metaspace", 150, -1),
                new SpeicherMessung("Compressed Class Space", 20, 100),
                new SpeicherMessung("Heap", 300, 2000));
    }

    @Test
    @Tag("req-16.5")
    void heapOhneBekanntesMaximumHatKeines() {
        JvmSpeicherQuelle quelle = new JvmSpeicherQuelle(() -> List.of(
                bereich("Heap", MemoryType.HEAP, new MemoryUsage(0, 5, 5, -1), null)));

        assertThat(quelle.messen()).containsExactly(new SpeicherMessung("Heap", 0, -1));
    }

    @Test
    @Tag("req-16.5")
    void dieEchteJvmLiefertMetaspaceUndHeap() {
        List<SpeicherMessung> messungen = new JvmSpeicherQuelle().messen();

        assertThat(messungen).extracting(SpeicherMessung::pool).contains("Metaspace", "Heap");
        assertThat(messungen).allSatisfy(m -> assertThat(m.benutzt()).isGreaterThanOrEqualTo(0));
    }
}
