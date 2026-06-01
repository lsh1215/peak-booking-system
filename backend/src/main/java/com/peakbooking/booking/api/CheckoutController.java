package com.peakbooking.booking.api;

import com.peakbooking.booking.api.dto.CheckoutResponse;
import com.peakbooking.booking.application.CheckoutApplicationService;
import com.peakbooking.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final CheckoutApplicationService checkoutApplicationService;

    public CheckoutController(CheckoutApplicationService checkoutApplicationService) {
        this.checkoutApplicationService = checkoutApplicationService;
    }

    @GetMapping("/{productId}")
    public ApiResponse<CheckoutResponse> checkout(
            @RequestHeader("X-User-Id") long userId,
            @PathVariable long productId
    ) {
        CheckoutApplicationService.CheckoutResult result = checkoutApplicationService.checkout(userId, productId);
        return ApiResponse.ok(CheckoutResponse.from(result));
    }
}
