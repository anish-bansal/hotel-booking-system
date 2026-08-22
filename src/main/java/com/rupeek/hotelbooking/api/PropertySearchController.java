package com.rupeek.hotelbooking.api;

import com.rupeek.hotelbooking.api.dto.SearchRequest;
import com.rupeek.hotelbooking.api.dto.SearchResponse;
import com.rupeek.hotelbooking.application.PropertySearchService;
import com.rupeek.hotelbooking.domain.search.PropertySearchCriteria;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Property discovery. */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertySearchController {

    private final PropertySearchService searchService;

    public PropertySearchController(PropertySearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        PropertySearchCriteria criteria = PropertySearchCriteria.in(request.city())
                .locality(request.locality())
                .stay(DateRange.of(request.checkIn(), request.checkOut()))
                .guests(request.guests())
                .priceBetween(toMoney(request.minNightlyPrice()), toMoney(request.maxNightlyPrice()))
                .withAmenities(request.amenities())
                .minStarRating(request.minStarRating())
                .build();

        return SearchResponse.from(searchService.search(criteria));
    }

    private static Money toMoney(BigDecimal amount) {
        return amount == null ? null : Money.of(amount, Money.INR);
    }
}
