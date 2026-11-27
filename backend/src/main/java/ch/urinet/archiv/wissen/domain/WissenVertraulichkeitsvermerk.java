package ch.urinet.archiv.wissen.domain;

import java.util.regex.Pattern;

public final class WissenVertraulichkeitsvermerk {
    private static final Pattern INTERN = Pattern.compile(
            "(?ium)^\\h*(?:(?:vertraulichkeitsvermerk|vertraulichkeit)\\h*[:=]\\h*)?"
                    + "(?:intern|nicht\\h+f(?:ü|ue|u)r\\h+dritte)\\h*$");

    private WissenVertraulichkeitsvermerk() {
    }

    public static boolean istIntern(String volltext) {
        return volltext != null && INTERN.matcher(volltext.replace('\f', '\n')).find();
    }
}
