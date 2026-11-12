package io.github.themusicianone.ticketing_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TicketingBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketingBackendApplication.class, args);
	}

}
