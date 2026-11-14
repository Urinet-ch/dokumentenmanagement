package ch.urinet.archiv.gemeinsam.domain;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * String-Konstanten einer eigenen Klasse, gelesen aus dem Konstantenpool ihrer Klassendatei (JVMS §4.4), je Klasse
 * einmal. Für {@link Ursachen#ketteMitMeldung}: Eine Meldung, die dort steht, ist fester Text im Code und enthält keine
 * Laufzeitwerte. Ist die Klassendatei nicht lesbar, gilt keine Meldung als Konstante (fail closed).
 */
final class Konstanten {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int UTF8 = 1;
    private static final int STRING = 8;
    private static final Map<String, Set<String>> GELESEN = new ConcurrentHashMap<>();

    private Konstanten() {
    }

    static boolean enthaelt(String klasse, String text) {
        return GELESEN.computeIfAbsent(klasse, Konstanten::lesen).contains(text);
    }

    private static Set<String> lesen(String klasse) {
        String pfad = klasse.replace('.', '/') + ".class";
        try (InputStream roh = Konstanten.class.getClassLoader().getResourceAsStream(pfad)) {
            return roh == null ? Set.of() : strings(new DataInputStream(new BufferedInputStream(roh)));
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    private static Set<String> strings(DataInputStream ein) throws IOException {
        if (ein.readInt() != MAGIC) {
            return Set.of();
        }
        ein.skipNBytes(4); // Versionen
        int anzahl = ein.readUnsignedShort();
        String[] utf8 = new String[anzahl];
        List<Integer> strings = new ArrayList<>();
        for (int i = 1; i < anzahl; i++) {
            int tag = ein.readUnsignedByte();
            switch (tag) {
                case UTF8 -> utf8[i] = ein.readUTF();
                case STRING -> strings.add(ein.readUnsignedShort());
                case 7, 16, 19, 20 -> ein.skipNBytes(2);
                case 15 -> ein.skipNBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> ein.skipNBytes(4);
                case 5, 6 -> {
                    ein.skipNBytes(8);
                    i++; // long und double belegen zwei Plätze
                }
                default -> {
                    return Set.of();
                }
            }
        }
        return strings.stream().filter(nr -> nr < anzahl && utf8[nr] != null).map(nr -> utf8[nr])
                .collect(Collectors.toUnmodifiableSet());
    }
}
