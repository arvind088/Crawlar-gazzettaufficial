package it.legislation.eli;

import java.io.IOException;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.legislation.eli.EliResolutionService.ResolvedResource;
import it.legislation.web.ActCatalog;
import it.legislation.web.ActPages;

/**
 * Makes every act dereferenceable at its own address.
 *
 * <pre>
 * GET /eli/id/2005/05/16/005G0104/sg
 *     Accept: text/html    -&gt; the Act details page, rendered from live SPARQL queries
 *     Accept: text/turtle  -&gt; the same description as RDF
 * </pre>
 *
 * <p>Deeper paths resolve too, so an expression such as
 * {@code /eli/id/2005/05/16/005G0104/sg/ita/original} has its own page as well.
 */
@RestController
public class EliResolutionController {

    private static final MediaType TURTLE = MediaType.parseMediaType("text/turtle;charset=UTF-8");
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    private final EliResolutionService resolutionService;
    private final EliHtmlRenderer renderer;
    private final EliUriService uriService;
    private final ActCatalog catalog;
    private final ActPages actPages;

    public EliResolutionController(
            EliResolutionService resolutionService,
            EliHtmlRenderer renderer,
            EliUriService uriService,
            ActCatalog catalog,
            ActPages actPages
    ) {
        this.resolutionService = resolutionService;
        this.renderer = renderer;
        this.uriService = uriService;
        this.catalog = catalog;
        this.actPages = actPages;
    }

    @GetMapping("/eli/id/**")
    public ResponseEntity<String> resolve(
            HttpServletRequest request,
            @RequestParam(name = "format", required = false) String format
    ) throws IOException {
        Optional<String> maybePath = uriService.pathOf(request.getRequestURI());
        if (maybePath.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML)
                    .body(renderer.renderNotFound(request.getRequestURI()));
        }

        String path = maybePath.get();
        boolean wantsRdf = wantsRdf(format, request.getHeader(HttpHeaders.ACCEPT));
        Optional<ResolvedResource> resolved = resolutionService.resolve(path);

        if (resolved.isEmpty()) {
            if (wantsRdf) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("No resource with this ELI identifier: " + uriService.uriForPath(path) + "\n");
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML)
                    .body(renderer.renderNotFound(path));
        }

        ResolvedResource resource = resolved.get();
        if (wantsRdf) {
            return ResponseEntity.ok()
                    .contentType(TURTLE)
                    .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                    .body(resolutionService.toTurtle(resource));
        }

        // An act gets the Act details page; its versions, formats and anything
        // else with an ELI address get the generic page.
        String page = catalog.isAct(resource.storedUri())
                ? catalog.details(resource.storedUri()).map(act -> actPages.details(act, path))
                        .orElseGet(() -> renderer.render(resource))
                : renderer.render(resource);
        return ResponseEntity.ok()
                .contentType(HTML)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                .body(page);
    }

    /**
     * Content negotiation. An explicit {@code ?format=} always wins, so the
     * Turtle view can be linked to from a browser; otherwise the Accept header
     * decides, and a browser's {@code text/html} preference keeps the page.
     */
    static boolean wantsRdf(String format, String acceptHeader) {
        if (format != null && !format.isBlank()) {
            String requested = format.trim().toLowerCase(java.util.Locale.ROOT);
            return requested.equals("ttl") || requested.equals("turtle") || requested.equals("rdf");
        }
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String accept = acceptHeader.toLowerCase(java.util.Locale.ROOT);
        if (accept.contains("text/html")) {
            return false;
        }
        return accept.contains("text/turtle")
                || accept.contains("application/x-turtle")
                || accept.contains("application/rdf+xml")
                || accept.contains("application/n-triples");
    }
}
