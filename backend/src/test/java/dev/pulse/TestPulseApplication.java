package dev.pulse;

import org.springframework.boot.SpringApplication;

public class TestPulseApplication {

	public static void main(String[] args) {
		SpringApplication.from(PulseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
