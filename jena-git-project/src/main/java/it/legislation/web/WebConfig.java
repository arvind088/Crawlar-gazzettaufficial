package it.legislation.web;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Language of the pages: {@code ?lang=it} or {@code ?lang=en} switches and is
 * remembered for a year in the {@code lang} cookie; without a choice, the
 * browser's Accept-Language header decides (see {@link I18n}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver();
        resolver.setCookieName("lang");
        resolver.setCookieMaxAge((int) Duration.ofDays(365).getSeconds());
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor change = new LocaleChangeInterceptor();
        change.setParamName("lang");
        change.setIgnoreInvalidLocale(true);
        registry.addInterceptor(change);
    }
}
