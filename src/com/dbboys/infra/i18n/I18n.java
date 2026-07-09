package com.dbboys.infra.i18n;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class I18n {
    private static final String BUNDLE_BASE = "com.dbboys.infra.i18n.messages";
    private static final ObjectProperty<Locale> localeProperty = new SimpleObjectProperty<>(Locale.CHINA);
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASE, localeProperty.get());

    static {
        localeProperty.addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                bundle = ResourceBundle.getBundle(BUNDLE_BASE, newVal);
            }
        });
    }

    private I18n() {}

    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public static String t(String key, String fallback) {
        try {
            String value = bundle.getString(key);
            return value == null || value.isEmpty() ? fallback : value;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    public static void setLocale(Locale newLocale) {
        if (newLocale == null) {
            return;
        }
        bundle = ResourceBundle.getBundle(BUNDLE_BASE, newLocale);
        localeProperty.set(newLocale);
    }

    public static Locale getLocale() {
        return localeProperty.get();
    }

    public static ReadOnlyObjectProperty<Locale> localeProperty() {
        return localeProperty;
    }

    public static StringBinding bind(String key) {
        return Bindings.createStringBinding(() -> t(key), localeProperty);
    }

    public static StringBinding bind(String key, String fallback) {
        return Bindings.createStringBinding(() -> t(key, fallback), localeProperty);
    }
}
