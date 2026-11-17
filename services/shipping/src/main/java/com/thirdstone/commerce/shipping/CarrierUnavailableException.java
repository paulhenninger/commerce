package com.thirdstone.commerce.shipping;

public class CarrierUnavailableException extends RuntimeException {

    public CarrierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
