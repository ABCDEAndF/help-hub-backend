package org.isolatedareas.helphub.inventory;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Pinyin initials and ordering for item names, so residents can jump to a letter.
 * The JDK's Chinese collator orders common characters by pinyin; each letter is found by
 * comparing against the first character of that letter's range. The "公益" prefix every
 * charity item shares is ignored, otherwise nearly everything would sit under G.
 */
public final class PinyinIndex {
    private static final Collator CHINESE = Collator.getInstance(Locale.CHINA);
    private static final String LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ";
    private static final String[] BOUNDARIES = {
        "阿", "芭", "擦", "搭", "蛾", "发", "噶", "哈", "击", "喀", "垃", "妈",
        "拿", "哦", "啪", "期", "然", "撒", "塌", "挖", "昔", "压", "匝"};
    public static final Comparator<String> ORDER = Comparator.comparing(PinyinIndex::sortKey, CHINESE);

    private PinyinIndex() {
    }

    public static String sortKey(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.startsWith("公益") && trimmed.length() > 2 ? trimmed.substring(2).trim() : trimmed;
    }

    public static String initial(String name) {
        String key = sortKey(name);
        if (key.isEmpty()) return "#";
        char first = key.charAt(0);
        if (first < 128) return Character.isLetter(first) ? String.valueOf(Character.toUpperCase(first)) : "#";
        String character = String.valueOf(first);
        if (CHINESE.compare(character, BOUNDARIES[0]) < 0) return "#";
        for (int index = BOUNDARIES.length - 1; index >= 0; index--) {
            if (CHINESE.compare(character, BOUNDARIES[index]) >= 0) return String.valueOf(LETTERS.charAt(index));
        }
        return "#";
    }
}
