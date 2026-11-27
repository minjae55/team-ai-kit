package kr.remerge.stylehub.domain.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartAddRequest(
        @NotNull Long optionId,
        @NotNull @Min(1) Integer quantity,
        @NotNull CartType cartType   // NORMAL, NEGOTIATION
) {}
