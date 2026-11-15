package ch.urinet.archiv.sicherung.adapter.in.kommando;

/** Das übergebene Vorinventar ist ungültig oder nicht in Bucket-Reihenfolge sortiert. */
final class InventarDateiFehler extends RuntimeException {

    InventarDateiFehler(String meldung) {
        super(meldung);
    }

    InventarDateiFehler(String meldung, Throwable ursache) {
        super(meldung, ursache);
    }
}
