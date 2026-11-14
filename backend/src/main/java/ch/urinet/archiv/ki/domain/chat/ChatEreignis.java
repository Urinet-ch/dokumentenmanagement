package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ereignis eines Durchgangs des Chat-Agenten (Spec 02, Aufgabe 7.3; design.md «Chat-Agent», «Vertrag Welle 7»). Der
 * Agent liefert sie in dieser Reihenfolge: je Werkzeugaufruf {@link WerkzeugStart} und {@link WerkzeugErgebnis},
 * dazwischen und danach {@link Text}, zuletzt genau eines von {@link Ende} oder {@link Ausfall}.
 */
public sealed interface ChatEreignis {

    /** Ein Werkzeugaufruf beginnt (SSE {@code werkzeug}). */
    record WerkzeugStart(String name) implements ChatEreignis {
        public WerkzeugStart {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Ergebnis eines Werkzeugaufrufs, nur so weit es für Quellen und Speicherung nötig ist: nie Dokumentinhalte.
     *
     * @param parameter  Parameter des Aufrufs als JSON-Werte (Text, Zahl, Wahrheitswert, Liste, Objekt oder leer)
     * @param dokumente  gelieferte Dokumente mit Titel (bei {@code dokumente_suchen} die Treffer in Reihenfolge)
     * @param datenstand Datenstand der Zahlenwerkzeuge ({@code kennzahl}, {@code offene_posten}, {@code fristen},
     *                   {@code vollstaendigkeit})
     */
    record WerkzeugErgebnis(String name, Map<String, Object> parameter, List<QuellDokument> dokumente,
                            Optional<Datenstand> datenstand) implements ChatEreignis {
        public WerkzeugErgebnis {
            Objects.requireNonNull(name, "name");
            parameter = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameter, "parameter")));
            dokumente = List.copyOf(dokumente);
            Objects.requireNonNull(datenstand, "datenstand");
        }
    }

    /** Teil der Antwort (SSE {@code text}). */
    record Text(String delta) implements ChatEreignis {
        public Text {
            Objects.requireNonNull(delta, "delta");
        }
    }

    /** Die Antwort ist vollständig. */
    record Ende(int tokensEin, int tokensAus) implements ChatEreignis {
        public Ende {
            if (tokensEin < 0 || tokensAus < 0) {
                throw new IllegalArgumentException("Tokens negativ");
            }
        }
    }

    /**
     * Der Agent konnte nicht (weiter) antworten, z. B. Modell nicht erreichbar.
     *
     * @param grund einfacher Klassenname der Ursache, nie eine Meldung (Spec 01, Req 16.7)
     */
    record Ausfall(String grund) implements ChatEreignis {
        public Ausfall {
            Objects.requireNonNull(grund, "grund");
        }
    }

    /** Dokument in einem Werkzeugergebnis. */
    record QuellDokument(UUID id, String titel) {
        public QuellDokument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(titel, "titel");
        }
    }

    /** «Datenstand: &lt;Zeitpunkt&gt;, &lt;n&gt; Belege» (Requirement 4.1). */
    record Datenstand(Instant zeitpunkt, int belege) {
        public Datenstand {
            Objects.requireNonNull(zeitpunkt, "zeitpunkt");
            if (belege < 0) {
                throw new IllegalArgumentException("Belege negativ");
            }
        }
    }
}
