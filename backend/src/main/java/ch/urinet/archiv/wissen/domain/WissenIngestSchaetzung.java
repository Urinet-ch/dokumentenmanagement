package ch.urinet.archiv.wissen.domain;

/** Konservative Vorabschätzung der Eingabe- und Ausgabetokens für einen Ingest (Spec 04, Req 13.4). */
public final class WissenIngestSchaetzung {

    private static final double TOKENS_PRO_ZEICHEN = 1.3 / 3.0;

    private WissenIngestSchaetzung() {
    }

    public static Schaetzung schaetzen(String volltext, int seiten, Integer seiteVon, Integer seiteBis,
                                        boolean normal, WissenIngestGrenzen grenzen) {
        if (volltext == null || seiten < 1) {
            throw new IllegalArgumentException("Volltext und Seitenzahl sind erforderlich");
        }
        String[] pdfSeiten = volltext.split("\\f", -1);
        if (pdfSeiten.length != seiten) {
            throw new IllegalArgumentException("Seitenzahl passt nicht zum Volltext");
        }
        int von = seiteVon == null ? 1 : seiteVon;
        int bis = seiteBis == null ? seiten : seiteBis;
        if (von < 1 || bis < von || bis > seiten) {
            throw new IllegalArgumentException("Ungültiger Seitenbereich");
        }

        long textZeichen = 0;
        long abschnitte = 0;
        for (int i = von - 1; i < bis; i++) {
            int zeichen = pdfSeiten[i].length();
            textZeichen = add(textZeichen, zeichen);
            if (zeichen > 0) {
                abschnitte = add(abschnitte, Math.ceilDiv((long) zeichen, grenzen.abschnittZeichen()));
            }
        }
        if (textZeichen == 0) {
            throw new IllegalArgumentException("Der Seitenbereich enthält keinen Volltext");
        }

        int anzahlSeiten = Math.min(bis - von + 1, grenzen.maxSeiten());
        long aufrufe = add(add(abschnitte, anzahlSeiten), 2);
        long indexUndUeberblick = add(grenzen.indexMaxZeichen(),
                normal ? grenzen.seiteMaxZeichen() : 0);
        long seitenTexte = multiply(multiply(2L, anzahlSeiten), grenzen.seiteMaxZeichen());
        long prompts = multiply(aufrufe, grenzen.werkzeugMaxZeichen());
        long gesamtZeichen = add(add(add(textZeichen, indexUndUeberblick), seitenTexte), prompts);
        long tokens = (long) Math.ceil(gesamtZeichen * TOKENS_PRO_ZEICHEN);
        return new Schaetzung(tokens, textZeichen, anzahlSeiten, abschnitte);
    }

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long multiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public record Schaetzung(long tokens, long textZeichen, int seiten, long abschnitte) {
    }
}
