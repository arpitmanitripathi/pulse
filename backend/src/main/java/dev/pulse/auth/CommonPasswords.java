package dev.pulse.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Passwords that are too common to allow (NIST SP 800-63B: refuse known passwords instead of
 * forcing symbols). The list ships with the app (resources/common-passwords.txt), so no
 * password ever leaves the server. Compared case-insensitively.
 */
@Component
public class CommonPasswords {

	static final String RESOURCE = "/common-passwords.txt";

	private final Set<String> passwords;

	public CommonPasswords() {
		this.passwords = load();
	}

	public boolean contains(String password) {
		return passwords.contains(password.toLowerCase(Locale.ROOT));
	}

	int size() {
		return passwords.size();
	}

	private static Set<String> load() {
		try (InputStream in = CommonPasswords.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the build");
			}
			Set<String> set = new HashSet<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					if (!line.isBlank() && !line.startsWith("#")) {
						set.add(line.toLowerCase(Locale.ROOT));
					}
				}
			}
			return Set.copyOf(set);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
