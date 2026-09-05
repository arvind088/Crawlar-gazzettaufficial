package it.legislation.eli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EliConfiguration {

    /**
     * The domain this platform publishes ELI identifiers on. Override with
     * {@code legal.eli.base-uri} in application.properties, or with the
     * {@code LEGAL_ELI_BASE_URI} environment variable in a deployment.
     */
    @Bean
    public EliUriService eliUriService(
            @Value("${legal.eli.base-uri:https://osservatorio-eli.example.it}") String baseUri
    ) {
        return new EliUriService(baseUri);
    }
}
