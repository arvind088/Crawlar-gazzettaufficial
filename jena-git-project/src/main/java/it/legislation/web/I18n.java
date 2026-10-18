package it.legislation.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The interface in English and Italian.
 *
 * <p>Texts live in {@code i18n/messages_en.properties} and
 * {@code i18n/messages_it.properties} (UTF-8). The language of a request is
 * chosen with {@code ?lang=it} or {@code ?lang=en} and remembered in a cookie
 * ({@link WebConfig}); without either, the browser's preferred language
 * decides. English is the default, also outside a web request (tests, tools).
 *
 * <p>The data itself (titles, citations, dates) is Italian in both languages.
 */
public final class I18n {

    public static final String ENGLISH = "en";
    public static final String ITALIAN = "it";

    private static final Map<String, Properties> TEXTS = new ConcurrentHashMap<>();

    private I18n() {
    }

    /** "it" or "en" for the current request. */
    public static String lang() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale != null && ITALIAN.equals(locale.getLanguage()) ? ITALIAN : ENGLISH;
    }

    public static boolean italian() {
        return ITALIAN.equals(lang());
    }

    /** The text for {@code key}, with {0}, {1}… replaced by the arguments as given (not escaped). */
    public static String t(String key, Object... args) {
        String text = texts(lang()).getProperty(key);
        if (text == null) {
            text = texts(ENGLISH).getProperty(key, key);
        }
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /** One or many: {@code key.one} or {@code key.many}, with the count as {0}. */
    public static String count(String key, long n, String formatted) {
        return t(key + (n == 1 ? ".one" : ".many"), formatted);
    }

    /** A number in the current language: 1,116 or 1.116. */
    public static String number(long n) {
        return String.format(italian() ? Locale.ITALY : Locale.ENGLISH, "%,d", n);
    }

    private static Properties texts(String lang) {
        return TEXTS.computeIfAbsent(lang, l -> {
            Properties p = new Properties();
            try (InputStream in = I18n.class.getResourceAsStream("/i18n/messages_" + l + ".properties")) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return p;
        });
    }
}
