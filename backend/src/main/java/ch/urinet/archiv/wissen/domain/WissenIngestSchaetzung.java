package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Vorabschätzung der Ein- und Ausgabetokens eines Ingests (Spec 04, Req 13.4) nach der Formel aus design.md, «Ingest»:
 * {@code (Zeichen Text + Index + ueberblick + 2 × max-seiten × seite-max-zeichen + Anzahl Aufrufe × Systemprompt)
 * / 3 × 1.3}. Index und {@code ueberblick} gehen mit ihren Obergrenzen ein ({@code index-max-zeichen},
 * {@code seite-max-zeichen}, {@code ueberblick} nur bei {@code NORMAL}), die Anzahl Aufrufe ist Abschnitte +
 * {@code PLANUNG} + {@code max-seiten} × {@code SEITE} + {@code QUELLE}. Ganzzahlig und aufgerundet; ein Überlauf
 * sättigt bei {@link Long#MAX_VALUE}, damit eine riesige Quelle nie als klein gilt.
 */
public final class WissenIngestSchaetzung {

    private WissenIngestSchaetzung() {
    }

    /**
     * @param volltext            {@code dokument.volltext} im Format von {@code pdftotext} (jede Seite endet mit
     *                            {@code \f}, siehe {@link Seitentext})
     * @param seiten              {@code dokument.seiten}; muss zur Zahl der Seiten im Volltext passen
     * @param systempromptZeichen Länge des Systemprompts eines Teilauftrags in Zeichen
     */
    public static Schaetzung schaetzen(String volltext, int seiten, Integer seiteVon, Integer seiteBis,
                                        boolean normal, WissenIngestGrenzen grenzen, int systempromptZeichen) {
        List<String> pdfSeiten = Seitentext.von(volltext, seiten)
                .orElseThrow(() -> new IllegalArgumentException("Seitenzahl passt nicht zum Volltext"))
                .seiten();
        if (systempromptZeichen < 0) {
            throw new IllegalArgumentException("Systemprompt mit negativer Länge");
        }
        int von = seiteVon == null ? 1 : seiteVon;
        int bis = seiteBis == null ? seiten : seiteBis;
        if (von < 1 || bis < von || bis > seiten) {
            throw new IllegalArgumentException("Ungültiger Seitenbereich");
        }

        long textZeichen = 0;
        long abschnitte = 0;
        long laufend = 0;
        int grenze = grenzen.abschnittZeichen();
        for (String seite : pdfSeiten.subList(von - 1, bis)) {
            int zeichen = seite.length();
            textZeichen = add(textZeichen, zeichen);
            if (zeichen == 0) {
                continue;
            }
            // Abschnitte enden an Seitengrenzen (Req 5.3 a); eine Seite über der Grenze wird geteilt.
            if (zeichen > grenze) {
                abschnitte = add(abschnitte, (laufend > 0 ? 1 : 0) + Math.ceilDiv(zeichen, grenze));
                laufend = 0;
            } else if (laufend + zeichen > grenze) {
                abschnitte = add(abschnitte, 1);
                laufend = zeichen;
            } else {
                laufend += zeichen;
            }
        }
        if (laufend > 0) {
            abschnitte = add(abschnitte, 1);
        }
        if (textZeichen == 0) {
            throw new IllegalArgumentException("Der Seitenbereich enthält keinen Volltext");
        }

        long aufrufe = add(add(abschnitte, grenzen.maxSeiten()), 2);
        long gesamtZeichen = add(add(add(add(textZeichen, grenzen.indexMaxZeichen()),
                        normal ? grenzen.seiteMaxZeichen() : 0),
                multiply(multiply(2L, grenzen.maxSeiten()), grenzen.seiteMaxZeichen())),
                multiply(aufrufe, systempromptZeichen));
        long tokens = gesamtZeichen == Long.MAX_VALUE ? Long.MAX_VALUE
                : saettigend(() -> Math.ceilDiv(Math.multiplyExact(gesamtZeichen, 13L), 30L));
        return new Schaetzung(tokens, textZeichen, aufrufe, abschnitte);
    }

    private static long add(long a, long b) {
        return saettigend(() -> Math.addExact(a, b));
    }

    private static long multiply(long a, long b) {
        return saettigend(() -> Math.multiplyExact(a, b));
    }

    private static long saettigend(LongSupplier rechnung) {
        try {
            return rechnung.getAsLong();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public record Schaetzung(long tokens, long textZeichen, long aufrufe, long abschnitte) {
    }
}
