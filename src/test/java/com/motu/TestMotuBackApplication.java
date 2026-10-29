package com.motu;

import org.springframework.boot.SpringApplication;

public class TestMotuBackApplication {

	public static void main(String[] args) {
		SpringApplication.from(MotuBackApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
