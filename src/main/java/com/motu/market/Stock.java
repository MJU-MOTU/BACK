package com.motu.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stocks")
public class Stock {

	@Id
	@Column(length = 6)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Market market;

	protected Stock() {
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public Market getMarket() {
		return market;
	}

}
