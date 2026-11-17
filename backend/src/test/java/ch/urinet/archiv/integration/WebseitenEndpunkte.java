package ch.urinet.archiv.integration;

import ch.urinet.archiv.integration.adapter.in.rest.webseite.WebseitenErfassungResource;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WebseitenEndpunkte {
    record Endpunkt(String verb, String pfad, String medientyp, WebseitenZugang.Art art) {
    }

    private WebseitenEndpunkte() {
    }

    static List<Endpunkt> alle() {
        List<Endpunkt> endpunkte = new ArrayList<>();
        new ClassFileImporter().importPackages("ch.urinet.archiv").stream()
                .filter(k -> k.getName().startsWith("ch.urinet.archiv.integration.adapter.in.")
                        && k.isAnnotatedWith(Path.class))
                .map(k -> k.reflect()).forEach(klasse -> {
                    for (Method methode : klasse.getMethods()) {
                        WebseitenZugang zugang = methode.getAnnotation(WebseitenZugang.class);
                        if (zugang == null) {
                            continue;
                        }
                        for (var annotation : methode.getAnnotations()) {
                            HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
                            if (http == null) {
                                continue;
                            }
                            Path teil = methode.getAnnotation(Path.class);
                            Consumes typ = methode.getAnnotation(Consumes.class);
                            endpunkte.add(new Endpunkt(http.value(), klasse.getAnnotation(Path.class).value()
                                    + (teil == null ? "" : teil.value()),
                                    typ == null ? "application/json" : typ.value()[0], zugang.value()));
                        }
                    }
                });
        if (endpunkte.stream().noneMatch(e -> e.pfad().equals("/public/v1/erfassung/dokumentarten"))) {
            throw new AssertionError("Das öffentliche Inventar muss die neuen Hilfslisten enthalten: "
                    + WebseitenErfassungResource.class.getName());
        }
        return endpunkte.stream().sorted(Comparator.comparing(Endpunkt::pfad).thenComparing(Endpunkt::verb)).toList();
    }
}
