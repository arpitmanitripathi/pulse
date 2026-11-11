package dev.pulse.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Turns a hostname into IP addresses. An interface so tests can use fixed
 * answers instead of real DNS (and simulate a hostname that points inside).
 */
public interface HostResolver {

	InetAddress[] resolve(String host) throws UnknownHostException;

}
