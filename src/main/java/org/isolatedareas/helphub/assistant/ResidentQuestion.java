package org.isolatedareas.helphub.assistant;

import java.util.List;

/**
 * Something the assistant must ask the resident before it can go on, worded by the service itself
 * (a list to choose from), so it is shown as it is rather than reworded by the model.
 */
public class ResidentQuestion extends IllegalArgumentException {
    public ResidentQuestion(String message) {
        super(message);
    }

    /** "库存里有几种“饮用水”：\n1. 公益饮用水 12 瓶装\n2. …\n请告诉我要哪一种。" */
    static ResidentQuestion whichItem(String asked, List<String> names) {
        StringBuilder text = new StringBuilder("库存里有几种“").append(asked).append("”：");
        for (int i = 0; i < names.size(); i++) text.append("\n").append(i + 1).append(". ").append(names.get(i));
        return new ResidentQuestion(text.append("\n请告诉我要哪一种。").toString());
    }
}
