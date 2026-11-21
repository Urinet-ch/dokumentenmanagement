package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import ch.urinet.archiv.ablage.domain.MandantAblageAbbau;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandantAbbauTest {

    private static final MandantCode CODE = new MandantCode("vhf-gsk");
    static final MandantAbbau.Anlass ANLASS = new MandantAbbau.Anlass("tester", "Kundenende");
    private static final UUID EXPORT_ID = UUID.fromString("6d60de8a-0f65-47df-96f2-4c7fe5e381cd");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void trockenlaufAendertNichts() {
        Testaufbau t = new Testaufbau(START);

        MandantAbbau.Bericht bericht = t.abbau().ausfuehren(CODE, EXPORT_ID, false, false, ANLASS);

        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.TROCKENLAUF);
        assertThat(bericht.abbauAb()).isEqualTo(START.plusSeconds(30L * 24 * 60 * 60));
        assertThat(bericht.ablage().loeschbar()).isEqualTo(4);
        assertThat(bericht.ablage().geloescht()).isZero();
        assertThat(t.register.status.mandant().aktiv()).isTrue();
        assertThat(t.ablagePlanAufrufe.get()).isEqualTo(1);
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.datenbank.schemaEntfernt).isFalse();
    }

    @Test
    void ersterAusfuehrungsschrittDeaktiviertUndStartetFrist() {
        Testaufbau t = new Testaufbau(START);

        MandantAbbau.Bericht bericht = t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS);

        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.DEAKTIVIERT);
        assertThat(t.register.status.mandant().aktiv()).isFalse();
        assertThat(t.register.status.vollexportBestaetigt()).contains(EXPORT_ID);
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.ablagePlanAufrufe.get()).isZero();
    }

    @Test
    void vorAblaufDerDreissigTageWirdNichtsGeloescht() {
        Testaufbau t = new Testaufbau(START.plusSeconds(29L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");

        MandantAbbau.Bericht bericht = t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS);

        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.WARTEFRIST);
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.ablagePlanAufrufe.get()).isZero();
        assertThat(t.datenbank.schemaEntfernt).isFalse();
    }

    @Test
    void fehlendeNamenssicherungVerhindertAlleLoeschschritte() {
        Testaufbau t = new Testaufbau(START.plusSeconds(30L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");
        t.namenssicherungFehlt = true;

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.datenbank.schemaEntfernt).isFalse();
        assertThat(t.bucketEntferntAufrufe.get()).isZero();
    }

    @Test
    void gesperrteObjekteBleibenUndWiederholungKannAbschliessen() {
        Testaufbau t = new Testaufbau(START.plusSeconds(30L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");
        t.bucketEntfernbar = false;
        t.gesperrteObjekte = List.of(new MandantAblageAbbau.GesperrtesObjekt(
                "manifest/2026-01-01.json", "version-1", START.plusSeconds(365L * 24 * 60 * 60)));

        MandantAbbau.Bericht ersterLauf = t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS);

        assertThat(ersterLauf.zustand()).isEqualTo(MandantAbbau.Zustand.ABBAU_UNVOLLSTAENDIG);
        assertThat(ersterLauf.ablage().gesperrt()).hasSize(1);
        assertThat(t.register.status.abgeschlossen()).isFalse();
        assertThat(t.datenbank.schemaEntfernt).isTrue();

        t.gesperrteObjekte = List.of();
        t.bucketEntfernbar = true;
        MandantAbbau.Bericht wiederholung = t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS);

        assertThat(wiederholung.zustand()).isEqualTo(MandantAbbau.Zustand.ABGESCHLOSSEN);
        assertThat(wiederholung.zustandText()).isEqualTo("ABGESCHLOSSEN");
        assertThat(t.register.status.abgeschlossen()).isTrue();
        assertThat(t.ablageAufrufe.get()).isEqualTo(2);
    }

    @Test
    void abgebauterMandantIstNichtMehrAufloesbar() {
        Testaufbau t = new Testaufbau(START.plusSeconds(30L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");
        // Verzeichnis wie JdbcMandantVerzeichnis: Schema nur, solange der Eintrag aktiv ist
        MandantVerzeichnis verzeichnis = c -> Optional.of(t.register.status.mandant())
                .filter(m -> m.code().equals(c) && m.aktiv()).map(m -> m.schema());
        MandantAusAnmeldung anmeldung = () -> new MandantAusAnmeldung.Gueltig(CODE);
        MandantAufloesung ausAnmeldung = new MandantAufloesung(
                new MandantKontext(null, anmeldung, null), anmeldung, verzeichnis);

        assertThat(t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS).zustand())
                .isEqualTo(MandantAbbau.Zustand.ABGESCHLOSSEN);

        assertThat(verzeichnis.aktivesSchema(CODE)).isEmpty();
        assertThatThrownBy(ausAnmeldung::schema).isInstanceOf(MandantVerweigert.class)
                .hasMessageContaining("unbekannt oder inaktiv");
        assertThatThrownBy(ausAnmeldung::pruefen).isInstanceOf(MandantVerweigert.class);
    }

    @Test
    void bestaetigungMussZuEinemFertigenExportGehoren() {
        Testaufbau t = new Testaufbau(START);
        t.exportStatus = MandantVollexportRepository.Status.OFFEN;

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(MandantFehler.class)
                .hasMessageContaining("nicht abgeschlossen");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
    }

    @Test
    void exportMitAbweichenderDokumentanzahlBlockiertDeaktivierung() {
        Testaufbau t = new Testaufbau(START);
        t.bestandAnzahl = 4;

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(MandantFehler.class).hasMessageContaining("veraltet");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
    }

    @Test
    void exportVorSpaeteremAenderungBlockiertDeaktivierung() {
        Testaufbau t = new Testaufbau(START);
        t.letzteAenderung = Optional.of(START.plusSeconds(1));

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(MandantFehler.class).hasMessageContaining("geändert");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
    }

    @Test
    void fehlendesZipBlockiertDeaktivierungAuchImTrockenlauf() {
        Testaufbau t = new Testaufbau(START);
        t.zipVorhanden = false;

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, false, false, ANLASS))
                .isInstanceOf(MandantFehler.class).hasMessageContaining("ZIP fehlt");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
        assertThat(t.ablagePlanAufrufe.get()).isZero();
    }

    private static Testaufbau fristAbgelaufenMitRegister(String schema, String bucket) {
        Testaufbau t = new Testaufbau(START.plusSeconds(31L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");
        var alt = t.register.status;
        t.register.status = new MandantAbbauRegister.Status(new Mandant(CODE, "Test", schema, bucket,
                alt.mandant().geschaeftsjahresende(), false), alt.deaktiviertAm(), alt.vollexportBestaetigt(), false);
        return t;
    }

    @Test
    void abweichendesSchemaImRegisterBrichtVorJedemLoeschenAb() {
        Testaufbau t = fristAbgelaufenMitRegister("m_urinet", "bucket-vhf-gsk");

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("weicht");
        assertThat(t.datenbank.schemaEntfernt).isFalse();
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.register.status.abgeschlossen()).isFalse();
    }

    @Test
    void abweichenderBucketImRegisterBrichtVorJedemLoeschenAb() {
        Testaufbau t = fristAbgelaufenMitRegister("m_vhf_gsk", "bucket-urinet");

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(t.datenbank.schemaEntfernt).isFalse();
        assertThat(t.ablageAufrufe.get()).isZero();
        assertThat(t.bucketEntferntAufrufe.get()).isZero();
    }

    @Test
    void unbekannterMandantWirdNichtGefundenUndNichtsGeloescht() {
        Testaufbau t = new Testaufbau(START);
        t.register.status = null;

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(MandantFehler.class).hasMessageContaining("nicht gefunden");
        assertThat(t.datenbank.schemaEntfernt).isFalse();
        assertThat(t.ablageAufrufe.get()).isZero();
    }

    @Test
    void ausfuehrungOhneAnlassWirdAbgelehntUndAnlassIstGeprueft() {
        Testaufbau t = new Testaufbau(START);

        assertThatThrownBy(() -> t.abbau().ausfuehren(CODE, EXPORT_ID, true, false, null))
                .isInstanceOf(MandantFehler.class).hasMessageContaining("--wer");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
        assertThatThrownBy(() -> new MandantAbbau.Anlass("", "Grund")).isInstanceOf(MandantFehler.class);
        assertThatThrownBy(() -> new MandantAbbau.Anlass("wer", "a\nb")).isInstanceOf(MandantFehler.class);
    }

    @Test
    void mehrGesperrteObjekteAlsAufgelistetBleibenUnvollstaendig() {
        var liste = new java.util.ArrayList<MandantAblageAbbau.GesperrtesObjekt>();
        for (int i = 0; i < MandantAblageAbbau.MAX_AUFGELISTET; i++) {
            liste.add(new MandantAblageAbbau.GesperrtesObjekt("k" + i, "v", START.plusSeconds(1000)));
        }
        var abbau = new MandantAblageAbbau(0, 0, liste, 150);

        assertThat(abbau.gesperrt()).hasSize(MandantAblageAbbau.MAX_AUFGELISTET);
        assertThat(abbau.gesperrtGesamt()).isEqualTo(150);
        liste.add(liste.getFirst());
        assertThatThrownBy(() -> new MandantAblageAbbau(0, 0, liste, 200)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String[] ausgabe(MandantKommando kommando, List<String> args, int[] exit) {
        var aus = new java.io.ByteArrayOutputStream();
        var fehler = new java.io.ByteArrayOutputStream();
        exit[0] = kommando.ausfuehren(args, new java.io.PrintStream(aus, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(fehler, true, java.nio.charset.StandardCharsets.UTF_8));
        return new String[]{aus.toString(java.nio.charset.StandardCharsets.UTF_8),
                fehler.toString(java.nio.charset.StandardCharsets.UTF_8)};
    }

    private static List<String> abbauArgs() {
        return List.of("abbauen", "vhf-gsk", "--nach-export", EXPORT_ID.toString(), "--ausfuehren",
                "--wer", "tester", "--grund", "Kundenende");
    }

    @Test
    void berichtNenntKeycloakClientHinweiseUndUnvollstaendigerAbbauHatEigenenExitCode() {
        Testaufbau t = new Testaufbau(START.plusSeconds(30L * 24 * 60 * 60));
        t.register.deaktivieren(CODE, EXPORT_ID, START, "tester", "Kundenende");
        t.bucketEntfernbar = false;
        t.gesperrteObjekte = List.of(new MandantAblageAbbau.GesperrtesObjekt(
                "manifest/2026-01-01.json", "version-1", START.plusSeconds(365L * 24 * 60 * 60)));
        var kommando = new MandantKommando(null, null, null, t.abbau());
        int[] exit = new int[1];

        String[] ausgabe = ausgabe(kommando, abbauArgs(), exit);

        assertThat(exit[0]).isEqualTo(MandantKommando.EXIT_UNVOLLSTAENDIG).isNotZero();
        assertThat(ausgabe[0]).contains("ABBAU_UNVOLLSTAENDIG")
                .contains("Mandantenzugang: Mandant deaktiviert, Zuordnung im Archiv entfernt.")
                .contains("Keycloak: gemeinsame Clients bleiben bestehen (Req 8.3).")
                .contains("(z. B. archiv-vhf-gsk) und Benutzer mit mandant=vhf-gsk in der Admin-Konsole von Hand entfernen.")
                .contains("Gesperrte Objekte: 1").contains("7 Tage").contains("auch gelöscht");

        t.gesperrteObjekte = List.of();
        t.bucketEntfernbar = true;
        ausgabe = ausgabe(kommando, abbauArgs(), exit);

        assertThat(exit[0]).isZero();
        assertThat(ausgabe[0]).contains("Mandanten-Abbau: ABGESCHLOSSEN%n".formatted())
                .doesNotContain("ohne Keycloak-Client").doesNotContain("nicht entfernt");
    }

    @Test
    void abweichendesRegisterGibtTechnischenFehlerOhneInhalte() {
        Testaufbau t = fristAbgelaufenMitRegister("m_urinet", "bucket-vhf-gsk");
        int[] exit = new int[1];

        String[] ausgabe = ausgabe(new MandantKommando(null, null, null, t.abbau()), abbauArgs(), exit);

        assertThat(exit[0]).isEqualTo(3);
        assertThat(ausgabe[1]).contains("Technischer Fehler");
        assertThat(t.datenbank.schemaEntfernt).isFalse();
    }

    @Test
    void sicherungsstaendeBisRechnetVomLoeschtagInZuerichZeit() {
        // 30 Tage nach 2026-01-01T00:00Z ist 2026-01-31T00:00Z, in Zürich 01:00 am selben Tag
        Testaufbau t = new Testaufbau(START);

        assertThat(t.abbau().ausfuehren(CODE, EXPORT_ID, false, false, ANLASS).sicherungsstaendeBis())
                .isEqualTo(java.time.LocalDate.of(2036, 1, 31));
        assertThat(MandantAbbau.sicherungsstaendeBis(Instant.parse("2026-03-15T23:30:00Z")))
                .isEqualTo(java.time.LocalDate.of(2036, 3, 16));
    }

    @Test
    void mandantUrinetBrauchtZweiteBestaetigung() {
        Testaufbau t = new Testaufbau(START);
        MandantCode urinet = new MandantCode("urinet");
        t.register.status = new MandantAbbauRegister.Status(
                new Mandant(urinet, "Urinet", "m_urinet", "bucket-urinet", Geschaeftsjahresende.STANDARD, true),
                Optional.empty(), Optional.empty(), false);

        assertThatThrownBy(() -> t.abbau().ausfuehren(urinet, EXPORT_ID, true, false, ANLASS))
                .isInstanceOf(MandantFehler.class)
                .hasMessageContaining("--bestaetigt-urinet");
        assertThat(t.register.status.mandant().aktiv()).isTrue();
    }

    static final class Testaufbau {
        final TestRegister register = new TestRegister();
        final TestDatenbank datenbank = new TestDatenbank();
        final AtomicInteger ablageAufrufe = new AtomicInteger();
        final AtomicInteger ablagePlanAufrufe = new AtomicInteger();
        final AtomicInteger bucketEntferntAufrufe = new AtomicInteger();
        MandantVollexportRepository.Status exportStatus = MandantVollexportRepository.Status.FERTIG;
        List<MandantAblageAbbau.GesperrtesObjekt> gesperrteObjekte = List.of();
        boolean bucketEntfernbar = true;
        boolean namenssicherungFehlt;
        int anzahl = 3;
        int bestandAnzahl = 3;
        Optional<Instant> letzteAenderung = Optional.of(START.minusSeconds(60));
        boolean zipVorhanden = true;
        final Clock uhr;

        Testaufbau(Instant jetzt) {
            uhr = Clock.fixed(jetzt, ZoneOffset.UTC);
            register.status = new MandantAbbauRegister.Status(
                    new Mandant(CODE, "Test", "m_vhf_gsk", "bucket-vhf-gsk", Geschaeftsjahresende.STANDARD, true),
                    Optional.empty(), Optional.empty(), false);
        }

        MandantAbbau abbau() {
            MandantKontext kontext = new MandantKontext(null, null, null) {
                @Override
                public <T> T mit(MandantCode code, java.util.function.Supplier<T> arbeit) {
                    return arbeit.get();
                }
            };
            MandantVollexportRepository exporte = new MandantVollexportRepository() {
                @Override
                public boolean anlegen(UUID id) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<Vollexport> laden(UUID id) {
                    return Optional.of(new Vollexport(id, exportStatus, Optional.of("exporte/x.zip"), Optional.of("v1"),
                            Optional.of("abc"), Optional.of(1L), Optional.of(anzahl), START, Optional.of(START)));
                }

                @Override
                public Bestand bestand() {
                    return new Bestand(bestandAnzahl, letzteAenderung);
                }

                @Override
                public boolean fertig(UUID id, AbgelegteDatei zip, int anzahlDokumente) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean fehler(UUID id, UUID fehlerId) {
                    throw new UnsupportedOperationException();
                }
            };
            AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                    new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                        if (methode.getName().equals("mandantAbbauen")) {
                            ablageAufrufe.incrementAndGet();
                            return new MandantAblageAbbau(4, 4, gesperrteObjekte);
                        }
                        if (methode.getName().equals("exportVorhanden")) {
                            return zipVorhanden;
                        }
                        if (methode.getName().equals("mandantAbbauPlanen")) {
                            ablagePlanAufrufe.incrementAndGet();
                            return new MandantAblageAbbau(0, 4, gesperrteObjekte);
                        }
                        throw new UnsupportedOperationException(methode.getName());
                    });
            MandantBucket bucket = new MandantBucket() {
                @Override
                public void einrichten(String name) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean entfernenWennLeer(String name) {
                    bucketEntferntAufrufe.incrementAndGet();
                    return bucketEntfernbar;
                }
            };
            var namen = new ch.urinet.archiv.benutzer.application.BenutzerNamen() {
                @Override
                public boolean vergeben(String name) { return false; }

                @Override
                public void sperren(String name) { }

                @Override
                public void vorMandantenabbauSichern(String schema) {
                    if (namenssicherungFehlt) {
                        throw new IllegalStateException("Namenssicherung fehlt");
                    }
                }
            };
            return new MandantAbbau(register, exporte, kontext, datenbank, bucket, ablage, namen, "bucket-", uhr);
        }
    }

    static final class TestRegister implements MandantRegister, MandantAbbauRegister {
        MandantAbbauRegister.Status status;

        @Override
        public boolean existiert(MandantCode code) {
            return status != null;
        }

        @Override
        public void eintragen(Mandant mandant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Mandant> aktive() {
            return List.of(status.mandant()).stream().filter(Mandant::aktiv).toList();
        }

        @Override
        public Optional<Instant> erstellt(MandantCode code) {
            return Optional.of(START);
        }

        @Override
        public Optional<MandantAbbauRegister.Status> status(MandantCode code) {
            return Optional.ofNullable(status);
        }

        @Override
        public boolean deaktivieren(MandantCode code, UUID vollexportId, Instant jetzt, String wer, String grund) {
            if (status == null) {
                return false;
            }
            status = new MandantAbbauRegister.Status(new Mandant(status.mandant().code(), status.mandant().name(),
                    status.mandant().schema(), status.mandant().bucket(), status.mandant().geschaeftsjahresende(),
                    false), Optional.of(jetzt), Optional.of(vollexportId), false);
            return true;
        }

        @Override
        public boolean abschliessen(MandantCode code) {
            if (status == null) {
                return false;
            }
            status = new MandantAbbauRegister.Status(
                    status.mandant(), status.deaktiviertAm(), status.vollexportBestaetigt(), true);
            return true;
        }
    }

    static final class TestDatenbank implements MandantDatenbank {
        boolean schemaEntfernt;

        @Override
        public void schemaAnlegen(String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void schemaMigrieren(String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean schemaVorhanden(String schema) {
            return !schemaEntfernt;
        }

        @Override
        public void schemaEntfernen(String schema) {
            schemaEntfernt = true;
        }

        @Override
        public void dokumentartenEinspielen(String schema, List<DokumentartVorlage> arten) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void konfigurationSeedAnlegen(String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void regelnEinspielen(String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void gemeinsamesSchemaBerechtigen() {
            throw new UnsupportedOperationException();
        }
    }
}
