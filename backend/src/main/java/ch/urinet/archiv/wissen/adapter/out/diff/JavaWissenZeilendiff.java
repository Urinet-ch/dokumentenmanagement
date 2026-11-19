package ch.urinet.archiv.wissen.adapter.out.diff;

import ch.urinet.archiv.wissen.application.WissenZeilendiff;
import com.github.difflib.DiffUtils;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class JavaWissenZeilendiff implements WissenZeilendiff {
    @Override
    public List<Aenderung> vergleichen(String alt, String neu) {
        Objects.requireNonNull(alt, "alt");
        Objects.requireNonNull(neu, "neu");
        return DiffUtils.diff(zeilen(alt), zeilen(neu)).getDeltas().stream()
                .map(delta -> new Aenderung(switch (delta.getType()) {
                    case INSERT -> Art.EINFUEGEN;
                    case DELETE -> Art.ENTFERNEN;
                    case CHANGE -> Art.AENDERN;
                    case EQUAL -> throw new IllegalStateException("Unerwarteter unveränderter Diffabschnitt");
                }, delta.getSource().getPosition(), delta.getTarget().getPosition(),
                        delta.getSource().getLines(), delta.getTarget().getLines()))
                .toList();
    }

    private List<String> zeilen(String text) {
        // Zeilenenden normalisieren (kein Scheindiff); ein Schlussumbruch bleibt bewusst sichtbar
        String normal = text.replace("\r\n", "\n").replace('\r', '\n');
        return normal.isEmpty() ? List.of() : Arrays.asList(normal.split("\n", -1));
    }
}
