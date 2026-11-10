package dev.pulse.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Stops Pulse from being used to reach private networks (SSRF: server-side
 * request forgery). Without it, a user could add "http://169.254.169.254/" and
 * make our server fetch the cloud provider's secret metadata, or scan our own
 * internal services.
 *
 * <p>Rules (PLAN.md, "SSRF protection"):
 * <ul>
 * <li>scheme http or https; a host; no user:password@ part</li>
 * <li>port 80, 443 or 1024-65535</li>
 * <li>every address the host resolves to must be public</li>
 * </ul>
 * Called when a URL is saved and again right before each check, because DNS
 * can change in between (DNS rebinding).
 */
@Component
public class SsrfGuard {

	private final HostResolver resolver;

	public SsrfGuard(HostResolver resolver) {
		this.resolver = resolver;
	}

	/** Returns the parsed URL and its (all public) addresses, or throws with a reason a user can act on. */
	public SafeUrl check(String url) {
		URI uri = parse(url);
		String host = uri.getHost();
		checkPort(uri);

		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(stripBrackets(host));
		}
		catch (UnknownHostException ex) {
			throw new UnsafeUrlException("The hostname \"" + host + "\" could not be found.");
		}
		if (addresses.length == 0) {
			throw new UnsafeUrlException("The hostname \"" + host + "\" could not be found.");
		}
		for (InetAddress address : addresses) {
			if (!isPublic(address)) {
				throw new UnsafeUrlException("This URL points to a private or reserved network address, "
						+ "which Pulse does not check.");
			}
		}
		return new SafeUrl(uri, List.of(addresses));
	}

	private static URI parse(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		}
		catch (URISyntaxException | NullPointerException ex) {
			throw new UnsafeUrlException("Enter a valid URL, for example https://example.com/health.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new UnsafeUrlException("The URL must start with http:// or https://.");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new UnsafeUrlException("Enter a valid URL, for example https://example.com/health.");
		}
		if (uri.getRawUserInfo() != null) {
			throw new UnsafeUrlException("Remove the user name and password from the URL.");
		}
		return uri;
	}

	private static void checkPort(URI uri) {
		int port = uri.getPort();
		if (port == -1 || port == 80 || port == 443 || (port >= 1024 && port <= 65535)) {
			return;
		}
		throw new UnsafeUrlException("Port " + port + " is not allowed. Use 80, 443 or a port from 1024 to 65535.");
	}

	private static String stripBrackets(String host) {
		return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
	}

	/** True only for addresses on the public internet. */
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = b[0] & 0xff;
			int second = b[1] & 0xff;
			return !(first == 0                                   // 0.0.0.0/8 "this network"
					|| (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
					|| (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24 protocol assignments
					|| (first == 198 && (second == 18 || second == 19))  // 198.18.0.0/15 benchmarking
					|| first >= 240);                                  // 240.0.0.0/4 reserved + broadcast
		}
		if (address instanceof Inet6Address) {
			int first = b[0] & 0xff;
			return (first & 0xfe) != 0xfc;                             // fc00::/7 unique local
		}
		return false;
	}

	public record SafeUrl(URI uri, List<InetAddress> addresses) {
	}

	public static class UnsafeUrlException extends RuntimeException {

		public UnsafeUrlException(String message) {
			super(message);
		}

	}

}
