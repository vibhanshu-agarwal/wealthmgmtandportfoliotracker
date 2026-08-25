package com.wealth.portfolio;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.dto.AssetCatalogResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

/**
 * Read-only asset discovery. Served by portfolio-service; gateway routing already belongs to Wave 2.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetCatalogController {

    private static final String CACHE_CONTROL = "private, no-cache";

    private final SupportedCatalog catalog;

    public AssetCatalogController(SupportedCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<AssetCatalogResponse> listAssets(
            @RequestHeader(X_USER_ID_HEADER) String userId,
            WebRequest webRequest) {
        String catalogVersion = catalog.version();

        // Spring's checkNotModified covers weak tags and validator lists; "*" is handled
        // explicitly because some dispatch paths do not treat it as a match for GET.
        if (ifNoneMatchIsStar(webRequest) || webRequest.checkNotModified(catalogVersion)) {
            return notModified(catalogVersion);
        }

        AssetCatalogResponse body = new AssetCatalogResponse(
                catalogVersion,
                catalog.all().stream()
                        .map(entry -> new AssetCatalogResponse.AssetEntry(
                                entry.ticker(),
                                entry.name(),
                                entry.aliases(),
                                entry.assetClass(),
                                entry.quoteCurrency(),
                                entry.lifecycleStatus()))
                        .toList());

        return ResponseEntity.ok()
                .eTag(catalogVersion)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(body);
    }

    private static boolean ifNoneMatchIsStar(WebRequest webRequest) {
        String header = webRequest.getHeader(HttpHeaders.IF_NONE_MATCH);
        return StringUtils.hasText(header) && "*".equals(header.trim());
    }

    private static ResponseEntity<AssetCatalogResponse> notModified(String catalogVersion) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(catalogVersion)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .build();
    }
}
