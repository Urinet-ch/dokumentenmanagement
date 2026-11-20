package ch.urinet.archiv.wissen.adapter.out.zeitplan;

import ch.urinet.archiv.wissen.application.WissenZeitplan;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class QuartzWissenZeitplan implements WissenZeitplan {
    @Override
    public boolean gueltig(String ausdruck) {
        try {
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse(ausdruck).validate();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
