package ch.urinet.archiv.driveimport;

import java.util.Locale;
import java.util.Set;

/**
 * Entscheidet allein nach dem Dateinamen, ob das Werkzeug eine Datei ans Archiv sendet (Req 12.5, 12.6). Alles,
 * was nicht ausdrücklich übersprungen wird, geht ans Archiv; ob es die Datei annimmt, entscheidet das Archiv.
 */
record Einordnung(Weg weg, String grund) {

    enum Weg {
        /** ans Archiv senden */
        SENDEN,
        /** Google-Format: übersprungen, Liste «bitte als PDF exportieren» (Req 12.5) */
        GOOGLE,
        /** übersprungen, «nicht übernommen» mit Grund (Req 12.6) */
        NICHT_UEBERNOMMEN
    }

    static final Set<String> GOOGLE = Set.of(
            "gdoc", "gsheet", "gslides", "gdraw", "gform", "gmap", "gsite", "gjam", "gtable");
    static final Set<String> OFFICE = Set.of(
            "doc", "docx", "docm", "dot", "dotx", "dotm", "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm",
            "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odt", "ods", "odp", "odg", "rtf", "pages",
            "numbers", "key");
    static final Set<String> EMAIL = Set.of("eml", "msg", "mbox");
    static final Set<String> ARCHIV = Set.of("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz");
    static final Set<String> SYSTEM = Set.of("desktop.ini", "thumbs.db", "icon\r");

    static final String GRUND_GOOGLE = "Google-Format, bitte in Google Drive als PDF exportieren";
    static final String GRUND_OFFICE = "Office-Datei, in Phase 1 nicht übernommen (bitte als PDF exportieren)";
    static final String GRUND_EMAIL = "E-Mail-Datei, in Phase 1 nicht übernommen";
    static final String GRUND_ARCHIV = "Archivdatei (ZIP o. ä.), in Phase 1 nicht übernommen (bitte entpacken)";
    static final String GRUND_SYSTEM = "versteckte Datei oder Systemdatei";

    static Einordnung von(String dateiname) {
        String name = dateiname.toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.startsWith("~$") || SYSTEM.contains(name)) {
            return new Einordnung(Weg.NICHT_UEBERNOMMEN, GRUND_SYSTEM);
        }
        int punkt = name.lastIndexOf('.');
        String endung = punkt < 0 ? "" : name.substring(punkt + 1);
        if (GOOGLE.contains(endung)) {
            return new Einordnung(Weg.GOOGLE, GRUND_GOOGLE);
        }
        if (OFFICE.contains(endung)) {
            return new Einordnung(Weg.NICHT_UEBERNOMMEN, GRUND_OFFICE);
        }
        if (EMAIL.contains(endung)) {
            return new Einordnung(Weg.NICHT_UEBERNOMMEN, GRUND_EMAIL);
        }
        if (ARCHIV.contains(endung)) {
            return new Einordnung(Weg.NICHT_UEBERNOMMEN, GRUND_ARCHIV);
        }
        return new Einordnung(Weg.SENDEN, null);
    }
}
