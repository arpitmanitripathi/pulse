package dev.pulse.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stops Pulse from being used to reach private networks (SSRF: server-side
 * request forgery). Without it, a user could add "http://169.254.169.254/" and
 * make our server fetch the cloud provider's secret metadata, or scan our own
 * internal services.
 *
 * <p>Rules (docs/DESIGN.md, "SSRF protection"):
 * <ul>
 * <li>scheme http or https; a host; no user:password@ part</li>
 * <li>port 80, 443 or 1024-65535</li>
 * <li>every address the host resolves to must be public</li>
 * </ul>
 * Called when a URL is saved and again right before each check, because DNS
 * can change in between (DNS rebinding).
 *
 * <p>The load-test stack names one trusted host ({@code pulse.ssrf.trusted-hosts}), its local
 * WireMock target. Only the address check is skipped for that exact name; every URL rule
 * still applies, IP addresses can never be trusted, and the list is empty everywhere else.
 */
@Component
public class SsrfGuard {

	static final String PRIVATE_ADDRESS = "This URL points to a private or reserved network address, "
			+ "which Pulse does not check.";

	private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);
	/** An IPv4 address, or anything with a colon (IPv6, possibly in brackets). */
	private static final Pattern IP_LITERAL = Pattern.compile("^\\[?[0-9a-fA-F:.]*:[0-9a-fA-F:.]*\\]?$|^[0-9.]+$");

	private final HostResolver resolver;
	private final Predicate<InetAddress> allowedAddress;
	private final Set<String> trustedHosts;

	@Autowired
	public SsrfGuard(HostResolver resolver, SsrfProperties properties) {
		this(resolver, SsrfGuard::isPublic, properties.trustedHosts());
	}

	public SsrfGuard(HostResolver resolver) {
		this(resolver, SsrfGuard::isPublic, List.of());
	}

	/** For tests that need a fake website on 127.0.0.1; production always uses {@link #isPublic}. */
	public SsrfGuard(HostResolver resolver, Predicate<InetAddress> allowedAddress) {
		this(resolver, allowedAddress, List.of());
	}

	private SsrfGuard(HostResolver resolver, Predicate<InetAddress> allowedAddress, List<String> trusted) {
		this.resolver = resolver;
		this.allowedAddress = allowedAddress;
		this.trustedHosts = trusted == null ? Set.of() : Set.copyOf(trusted.stream()
			.map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList());
		for (String host : trustedHosts) {
			if (IP_LITERAL.matcher(host).matches()) {
				throw new IllegalStateException("pulse.ssrf.trusted-hosts may only contain host names, not \"" + host + "\"");
			}
		}
		if (!trustedHosts.isEmpty()) {
			log.warn("ssrf.trusted_hosts={} — private addresses of these hosts are allowed. Load testing only, never in production.",
					trustedHosts);
		}
	}

	/** Full check: URL rules plus DNS. Returns the parsed URL and its (all allowed) addresses. */
	public SafeUrl check(String url) {
		URI uri = validateUrl(url);
		String host = uri.getHost();
		try {
			return new SafeUrl(uri, List.of(resolvePublic(host)));
		}
		catch (BlockedAddressException ex) {
			throw new UnsafeUrlException(PRIVATE_ADDRESS);
		}
		catch (UnknownHostException ex) {
			throw new UnsafeUrlException("The hostname \"" + host + "\" could not be found.");
		}
	}

	/** URL rules only (scheme, host, no user info, port). No DNS lookup. */
	public URI validateUrl(String url) {
		URI uri = parse(url);
		checkPort(uri);
		return uri;
	}

	/**
	 * Resolves a hostname and returns its addresses only if every one of them
	 * is allowed. The checker's HTTP client uses this as its DNS resolver, so a
	 * connection can only ever be opened to an address that passed this check.
	 *
	 * @throws BlockedAddressException if any address is private or reserved
	 * @throws UnknownHostException if the name does not resolve
	 */
	public InetAddress[] resolvePublic(String host) throws UnknownHostException {
		InetAddress[] addresses = resolver.resolve(stripBrackets(host));
		if (addresses.length == 0) {
			throw new UnknownHostException(host);
		}
		if (trustedHosts.contains(host.toLowerCase(Locale.ROOT))) {
			return addresses; // the load-test target (see class comment)
		}
		for (InetAddress address : addresses) {
			if (!allowedAddress.test(address)) {
				throw new BlockedAddressException(host);
			}
		}
		return addresses;
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

	/**
	 * True only for addresses on the public internet. Based on IANA's
	 * special-purpose address registries: anything not globally reachable is
	 * refused. When unsure, refuse.
	 */
	public static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			int a0 = b[0] & 0xff;
			int a1 = b[1] & 0xff;
			int a2 = b[2] & 0xff;
			return !(a0 == 0                                     // 0.0.0.0/8 "this network"
					|| (a0 == 100 && a1 >= 64 && a1 <= 127)       // 100.64.0.0/10 carrier-grade NAT
					|| (a0 == 192 && a1 == 0 && a2 == 0)          // 192.0.0.0/24 protocol assignments
					|| (a0 == 192 && a1 == 0 && a2 == 2)          // 192.0.2.0/24 documentation
					|| (a0 == 192 && a1 == 88 && a2 == 99)        // 192.88.99.0/24 old 6to4 relay
					|| (a0 == 198 && (a1 == 18 || a1 == 19))      // 198.18.0.0/15 benchmarking
					|| (a0 == 198 && a1 == 51 && a2 == 100)       // 198.51.100.0/24 documentation
					|| (a0 == 203 && a1 == 0 && a2 == 113)        // 203.0.113.0/24 documentation
					|| a0 >= 240);                                // 240.0.0.0/4 reserved + broadcast
		}
		if (address instanceof Inet6Address) {
			int a0 = b[0] & 0xff;
			int a1 = b[1] & 0xff;
			int a2 = b[2] & 0xff;
			int a3 = b[3] & 0xff;
			// Allow-list: real public IPv6 is all inside 2000::/3. This alone refuses
			// NAT64 (64:ff9b::), IPv4-compatible (::a.b.c.d), discard (100::), unique local (fc00::/7), ...
			if ((a0 & 0xe0) != 0x20) {
				return false;
			}
			boolean ietfAssignments = a0 == 0x20 && a1 == 0x01 && a2 <= 0x01;          // 2001::/23 (incl. Teredo 2001::/32)
			boolean documentation = a0 == 0x20 && a1 == 0x01 && a2 == 0x0d && a3 == 0xb8; // 2001:db8::/32
			boolean sixToFour = a0 == 0x20 && a1 == 0x02;                              // 2002::/16 wraps an IPv4 address
			return !(ietfAssignments || documentation || sixToFour);
		}
		return false;
	}

	public record SafeUrl(URI uri, List<InetAddress> addresses) {
	}

	/** A name resolved, but to an address Pulse must not connect to. */
	public static class BlockedAddressException extends UnknownHostException {

		public BlockedAddressException(String host) {
			super(host + " resolves to a private or reserved address");
		}

	}

	public static class UnsafeUrlException extends RuntimeException {

		public UnsafeUrlException(String message) {
			super(message);
		}

	}

}
