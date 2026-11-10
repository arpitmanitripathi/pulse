package dev.pulse.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived access tokens. Checking them on each request is done by
 * Spring Security's bearer-token filter (see SecurityConfig).
 */
@Service
public class JwtService {

	public static final String ISSUER = "pulse";

	private final JwtEncoder encoder;
	private final AuthProperties properties;
	private final Clock clock;

	public JwtService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public IssuedToken issueAccessToken(UUID userId) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt);
	}

	public record IssuedToken(String value, Instant expiresAt) {
	}

}
