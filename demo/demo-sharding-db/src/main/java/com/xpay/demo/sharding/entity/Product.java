package com.xpay.demo.sharding.entity;

import java.io.Serializable;
import java.math.BigDecimal;

public class Product implements Serializable {
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer inStock;//库存

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getInStock() {
        return inStock;
    }

    public void setInStock(Integer inStock) {
        this.inStock = inStock;
    }
}
