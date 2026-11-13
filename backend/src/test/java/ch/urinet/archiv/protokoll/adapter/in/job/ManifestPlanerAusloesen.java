package ch.urinet.archiv.protokoll.adapter.in.job;

/** Ruft den paketsichtbaren Takt {@link ManifestPlaner#planen()} aus den Szenarien auf, wie es der Scheduler tut. */
public final class ManifestPlanerAusloesen {

    private ManifestPlanerAusloesen() {
    }

    public static void planen(ManifestPlaner planer) {
        planer.planen();
    }
}
