package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wächter für Req 2.6: Jede Ressourcen-Methode erklärt ihre Aktion ({@link ErfordertAktion}) oder ist ausdrücklich als
 * {@link AlleArchivRollen} bzw. {@link Oeffentlich} markiert, und ihr {@code @RolesAllowed} nennt genau die Rollen,
 * für die die Rechtematrix nicht 403 ergibt. Geprüft werden alle Klassen mit {@code @Path} (main und test).
 */
class EndpunktWaechterTest {

    private static final List<Class<? extends Annotation>> HTTP =
            List.of(GET.class, POST.class, PUT.class, PATCH.class, DELETE.class, HEAD.class, OPTIONS.class);
    private static final Set<String> ALLE = Arrays.stream(Rolle.values()).map(Rolle::code).collect(Collectors.toSet());

    @Test
    void jederEndpunktFragtDieRegelAb() {
        List<Class<?>> ressourcen = new ClassFileImporter().importPackages("ch.urinet.archiv").stream()
                .filter(k -> k.isAnnotatedWith(Path.class) && !k.getName().contains("EndpunktWaechterTest"))
                .map(JavaClass::reflect).toList();

        assertThat(ressourcen).as("mindestens die Probe-Ressource").isNotEmpty();
        assertThat(ressourcen.stream().flatMap(k -> verstoesse(k).stream())).isEmpty();
    }

    @Test
    void gegenprobeErkenntVerstoesse() {
        assertThat(verstoesse(OhneErklaerung.class)).singleElement().asString().contains("keine Erklärung");
        assertThat(verstoesse(FalscheRollen.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(ZweiErklaerungen.class)).singleElement().asString().contains("genau eine");
        assertThat(verstoesse(OeffentlichMitRollen.class)).singleElement().asString().contains("@PermitAll");
        assertThat(verstoesse(AlleOhneTreuhaender.class)).singleElement().asString().contains("@RolesAllowed");
        assertThat(verstoesse(Korrekt.class)).isEmpty();
    }

    static List<String> verstoesse(Class<?> ressource) {
        List<String> verstoesse = new ArrayList<>();
        for (Method methode : ressource.getDeclaredMethods()) {
            if (HTTP.stream().noneMatch(methode::isAnnotationPresent)) {
                continue;
            }
            String name = ressource.getSimpleName() + "." + methode.getName();
            ErfordertAktion aktion = methode.getAnnotation(ErfordertAktion.class);
            boolean alle = methode.isAnnotationPresent(AlleArchivRollen.class);
            boolean oeffentlich = methode.isAnnotationPresent(Oeffentlich.class);
            long erklaerungen = Stream.of(aktion != null, alle, oeffentlich).filter(b -> b).count();
            if (erklaerungen == 0) {
                verstoesse.add(name + ": keine Erklärung (@ErfordertAktion, @AlleArchivRollen oder @Oeffentlich)");
                continue;
            }
            if (erklaerungen > 1) {
                verstoesse.add(name + ": genau eine Erklärung erlaubt");
                continue;
            }
            RolesAllowed rollen = methode.getAnnotation(RolesAllowed.class);
            if (oeffentlich) {
                if (rollen != null || !methode.isAnnotationPresent(PermitAll.class)) {
                    verstoesse.add(name + ": @Oeffentlich verlangt @PermitAll ohne @RolesAllowed");
                }
                continue;
            }
            Set<String> erwartet = alle ? ALLE : Rechtematrix.zugelassen(aktion.value()).stream().map(Rolle::code)
                    .collect(Collectors.toSet());
            Set<String> ist = rollen == null ? Set.of() : Set.of(rollen.value());
            if (!ist.equals(erwartet)) {
                verstoesse.add(name + ": @RolesAllowed " + ist + " statt " + erwartet);
            }
        }
        return verstoesse;
    }

    static class OhneErklaerung {
        @GET
        @RolesAllowed("inhaber")
        public void lesen() {
        }
    }

    static class FalscheRollen {
        @POST
        @ErfordertAktion(Aktion.A1)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void erfassen() {
        }
    }

    static class ZweiErklaerungen {
        @GET
        @AlleArchivRollen
        @ErfordertAktion(Aktion.A2)
        @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    static class OeffentlichMitRollen {
        @GET
        @Oeffentlich
        @RolesAllowed("inhaber")
        public void lesen() {
        }
    }

    static class AlleOhneTreuhaender {
        @GET
        @AlleArchivRollen
        @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
        public void lesen() {
        }
    }

    static class Korrekt {
        @PUT
        @ErfordertAktion(Aktion.A11)
        @RolesAllowed("inhaber")
        public void dokumentartAendern() {
        }

        @GET
        @Oeffentlich
        @PermitAll
        public void oeffentlich() {
        }

        public void keinEndpunkt() {
        }
    }
}
