package dev.pulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PulseApplication {

	public static void main(String[] args) {
		SpringApplication.run(PulseApplication.class, args);
	}

}
