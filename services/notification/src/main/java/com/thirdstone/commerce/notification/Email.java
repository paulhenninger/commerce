package com.thirdstone.commerce.notification;

/** A rendered email: who, which template, what it says. */
record Email(String recipient, String template, String subject, String body) {
}
