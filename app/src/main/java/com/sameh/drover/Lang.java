package com.sameh.drover;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

final class Lang {

    static final String PREFS = "vextro";
    static final String KEY = "lang";
    static final String AR = "ar";
    static final String EN = "en";

    private Lang() {
    }

    static String current(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = sp.getString(KEY, AR);
        return EN.equals(code) ? EN : AR;
    }

    static void set(Context context, String code) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, EN.equals(code) ? EN : AR)
                .apply();
    }

    static Context wrap(Context base) {
        Locale locale = new Locale(current(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
}
