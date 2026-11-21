package ch.urinet.archiv.benutzer.adapter.out.persistence;

import liquibase.database.Database;
import liquibase.exception.CustomPreconditionFailedException;
import liquibase.precondition.CustomPrecondition;

public class NamenssperreMigrationBereit implements CustomPrecondition {
    @Override
    public void check(Database database) throws CustomPreconditionFailedException {
        if (NamenssperreHash.ausKonfiguration().isEmpty()) {
            throw new CustomPreconditionFailedException("Namenssperre-Salz fehlt; Bestandsmigration ausstehend");
        }
    }
}
