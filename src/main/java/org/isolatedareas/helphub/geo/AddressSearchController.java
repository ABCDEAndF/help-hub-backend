package org.isolatedareas.helphub.geo;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Where a typed address is on the map, so residents need not find it by dragging. */
@RestController
@RequestMapping("/api/resident/places")
public class AddressSearchController {
    private final AddressSearchService search;

    public AddressSearchController(AddressSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    SearchResult search(@RequestParam(defaultValue = "") String keyword) {
        String text = keyword.length() > 80 ? keyword.substring(0, 80) : keyword;
        return new SearchResult(search.available(), search.search(text));
    }

    /** available=false: no map key is configured, so the resident picks the point on the map instead. */
    public record SearchResult(boolean available, List<AddressSearchService.Place> places) {
    }
}
