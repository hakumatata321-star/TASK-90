package com.example.ricms.dto.response;

/**
 * Wraps the result of a placeOrder call so the controller can distinguish
 * between a brand-new order (HTTP 201) and an idempotency replay (HTTP 200).
 */
public record OrderPlacementResult(OrderResponse order, boolean replay) {}
