package com.motu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MotuBackApplication {

	public static void main(String[] args) {
		SpringApplication.run(MotuBackApplication.class, args);
	}

}
