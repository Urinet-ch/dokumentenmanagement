package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reihenfolge der Teile «dateien» aus der öffentlichen Multipart-API (Req 3.3, 3.8; Review #104). */
class DokumentUploadTeileTest {

    @Test
    void liefertJeTeilInDerGesendetenReihenfolgeObErAlsDateiKam() {
        var formular = formular(List.of(teil(true), teil(false), teil(true)));

        assertThat(DokumentUploadResource.dateiTeile(formular, "dateien", 2)).containsExactly(true, false, true);
    }

    @Test
    void ohneFeldUndOhneDateienIstDieListeLeer() {
        assertThat(DokumentUploadResource.dateiTeile(formular(List.of()), "arbeitsfassungen", 0)).isEmpty();
    }

    @Test
    void abweichendeZahlDerDateiTeileIstEinFachlicherFehlerStattEines500() {
        var formular = formular(List.of(teil(true), teil(false)));

        assertThatThrownBy(() -> DokumentUploadResource.dateiTeile(formular, "dateien", 2))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        assertThatThrownBy(() -> DokumentUploadResource.dateiTeile(formular(List.of()), "dateien", 1))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static MultipartFormDataInput formular(Collection<FormValue> dateien) {
        return () -> dateien.isEmpty() ? Map.of() : Map.of("dateien", dateien);
    }

    private static FormValue teil(boolean datei) {
        return new FormValue() {
            @Override
            public String getValue() {
                return datei ? null : "Notiz";
            }

            @Override
            public String getCharset() {
                return null;
            }

            @Override
            public FileItem getFileItem() {
                return null;
            }

            @Override
            public boolean isFileItem() {
                return datei;
            }

            @Override
            public String getFileName() {
                return datei ? "beleg.pdf" : null;
            }

            @Override
            public MultivaluedMap<String, String> getHeaders() {
                return new MultivaluedHashMap<>();
            }
        };
    }
}
