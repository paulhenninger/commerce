package com.thirdstone.commerce.platform.events;

/** Where a parcel goes to, or is collected from. */
public record Address(String name, String line1, String city, String postalCode, String country) {

    public Address {
        Contract.text(name, "name", 100);
        Contract.text(line1, "line1", 200);
        Contract.text(city, "city", 100);
        Contract.text(postalCode, "postalCode", 20);
        Contract.text(country, "country", 2);
    }
}
