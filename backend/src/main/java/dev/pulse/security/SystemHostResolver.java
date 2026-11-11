package dev.pulse.security;

import java.net.InetAddress;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemHostResolver {

	/** Real DNS through the JVM. Tests replace this with a @Primary bean that has fixed answers. */
	@Bean
	HostResolver hostResolver() {
		return InetAddress::getAllByName;
	}

}
