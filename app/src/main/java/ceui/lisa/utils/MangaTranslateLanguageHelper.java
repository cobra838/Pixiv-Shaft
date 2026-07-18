package ceui.lisa.utils;

import android.content.Context;
import android.text.TextUtils;

import java.util.Locale;

import ceui.lisa.R;

public final class MangaTranslateLanguageHelper {

    public static final String[] SOURCE_TAGS = new String[]{
            "auto",
            "ja",
            "zh-CN",
            "zh-TW",
            "en",
            "es",
            "fr",
            "de",
            "pt",
            "it",
            "ko",
            "ru",
            "tr",
    };

    public static final String[] TARGET_TAGS = new String[]{
            "zh-CN",
            "zh-TW",
            "en",
            "es",
            "fr",
            "de",
            "pt",
            "it",
            "ja",
            "ko",
            "ru",
            "tr",
    };

    private MangaTranslateLanguageHelper() {
    }

    public static String normalizeSourceTag(String tag) {
        return normalizeTag(tag, "ja", true);
    }

    public static String normalizeTargetTag(String tag) {
        return normalizeTag(tag, "zh-CN", false);
    }

    public static int indexOf(String[] tags, String saved, int fallback) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equalsIgnoreCase(saved)) {
                return i;
            }
        }
        return fallback;
    }

    public static String[] buildLabels(Context context, String[] tags) {
        String[] labels = new String[tags.length];
        Locale uiLocale = Locale.getDefault();
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
            if ("auto".equalsIgnoreCase(tag)) {
                labels[i] = context.getString(R.string.string_translate_source_auto);
                continue;
            }
            String displayTag;
            if ("zh-CN".equalsIgnoreCase(tag)) {
                displayTag = "zh-Hans";
            } else if ("zh-TW".equalsIgnoreCase(tag)) {
                displayTag = "zh-Hant";
            } else {
                displayTag = tag;
            }
            Locale target = Locale.forLanguageTag(displayTag);
            String label = target.getDisplayName(uiLocale);
            labels[i] = TextUtils.isEmpty(label) ? tag : label;
        }
        return labels;
    }

    private static String normalizeTag(String tag, String fallback, boolean allowAuto) {
        if (TextUtils.isEmpty(tag)) {
            return fallback;
        }
        if (allowAuto && "auto".equalsIgnoreCase(tag)) {
            return "auto";
        }
        for (String allowed : TARGET_TAGS) {
            if (allowed.equalsIgnoreCase(tag)) {
                return allowed;
            }
        }
        return fallback;
    }
}
