package com.thirdstone.commerce.billing;

/** The provider could not be asked, or did not answer: nobody knows what happened, so try again later. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
