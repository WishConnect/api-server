package com.wishconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WishConnectApplication {

	public static void main(String[] args) {
		SpringApplication.run(WishConnectApplication.class, args);
	}

}
