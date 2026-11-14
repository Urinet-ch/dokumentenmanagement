package ch.urinet.archiv.ki.domain.chat;

/** Ausgang einer Antwort ({@code chat_nachricht.status}, nur bei Antworten; design.md «Vertrag Welle 7»). */
public enum ChatStatus {
    OK,
    /** Agent ausgefallen oder Antwort nicht gespeichert; der Stream endete mit {@code fehler}. */
    FEHLER,
    /** Der Client hat den Stream vor dem Ende geschlossen. */
    ABGEBROCHEN
}
