package main;

import java.math.BigDecimal;

public class Item {
    public String upc;
    public String description;
    public BigDecimal price;

    public Item(String upc, String description, BigDecimal price) {
        this.upc = upc;
        this.description = description;
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }
}