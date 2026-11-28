package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 04, Req 22.6 (design.md «REST»): Jede Operation, deren Pfadvorlage einen Abschnitt mit «wissen» enthält, fällt
 * unter die Sperre des {@code ZugangFilter}, auch künftige: Ein neuer Pfad, den {@code Wissenspfade} nicht erfasst
 * (etwa {@code /dokumente/{id}/als-wissen}), lässt diesen Test scheitern. Ausgenommen ist nur die Webseiten-Schnittstelle
 * unter {@code /public/}, deren Wissenschat sich selbst nach der Freischaltung richtet (Req 22.8).
 */
@Tag("req-22.6")
class WissenspfadeVertragTest {

    private static final Set<Class<? extends Annotation>> HTTP = Set.of(GET.class, POST.class, PUT.class,
            DELETE.class, PATCH.class);

    private record Operation(Class<?> klasse, Method methode, String vorlage) {
    }

    @Test
    void jedeWissensoperationIstGesperrt() {
        List<Operation> operationen = operationen();
        assertThat(operationen).as("Operationen gefunden").hasSizeGreaterThan(100);
        List<Operation> wissen = operationen.stream()
                .filter(o -> o.vorlage().toLowerCase(Locale.ROOT).contains("wissen"))
                .filter(o -> !o.vorlage().startsWith("/public/"))
                .toList();
        assertThat(wissen).as("Wissensoperationen").hasSizeGreaterThan(15);
        assertThat(wissen).allSatisfy(o -> {
            assertThat(ZugangFilter.wissenspfad(o.klasse(), o.methode())).as(o.vorlage()).isTrue();
            assertThat(o.methode().isAnnotationPresent(Oeffentlich.class)).as(o.vorlage() + " öffentlich").isFalse();
        });
        assertThat(wissen).extracting(Operation::vorlage).contains("/wissensseiten", "/wissensbasis/einstellungen",
                "/wissen/quellen", "/wissensentwuerfe/analyse", "/dokumente/{id}/wissensquelle",
                "/dokumente/{id}/wissen-ingest");
    }

    @Test
    void andereOperationenSindNichtGesperrt() {
        List<Operation> operationen = operationen();
        assertThat(operationen).filteredOn(o -> o.vorlage().equals("/public/v1/wissen/chat")).isNotEmpty()
                .allSatisfy(o -> assertThat(ZugangFilter.wissenspfad(o.klasse(), o.methode())).isFalse());
        assertThat(operationen).filteredOn(o -> o.vorlage().equals("/mandant/funktionen")
                        || o.vorlage().equals("/chat")
                        || o.vorlage().startsWith("/dokumente") && !o.vorlage().contains("wissen"))
                .isNotEmpty()
                .allSatisfy(o -> assertThat(ZugangFilter.wissenspfad(o.klasse(), o.methode())).as(o.vorlage())
                        .isFalse());
    }

    private static List<Operation> operationen() {
        List<Operation> liste = new ArrayList<>();
        for (JavaClass klasse : new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ch.urinet.archiv")) {
            if (!klasse.isAnnotatedWith(jakarta.ws.rs.Path.class)) {
                continue;
            }
            Class<?> typ = klasse.reflect();
            for (Method methode : typ.getDeclaredMethods()) {
                if (HTTP.stream().anyMatch(methode::isAnnotationPresent)) {
                    liste.add(new Operation(typ, methode, ZugangFilter.vorlage(typ, methode)));
                }
            }
        }
        return liste;
    }
}
