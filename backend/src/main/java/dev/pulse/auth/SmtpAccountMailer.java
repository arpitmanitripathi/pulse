package dev.pulse.auth;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import dev.pulse.alert.AlertProperties;

/** Sends account emails over the same SMTP server as alerts, from the same address. */
@Component
public class SmtpAccountMailer implements AccountMailer {

	private final JavaMailSender mail;
	private final AlertProperties alertProperties;

	public SmtpAccountMailer(JavaMailSender mail, AlertProperties alertProperties) {
		this.mail = mail;
		this.alertProperties = alertProperties;
	}

	@Override
	public void sendPasswordReset(String email, String link) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(alertProperties.mailFrom());
		message.setTo(email);
		message.setSubject("Reset your Pulse password");
		message.setText(body(link));
		mail.send(message);
	}

	static String body(String link) {
		return "Someone, hopefully you, asked to reset the password of this Pulse account.\n\n"
				+ "Open this link within " + PasswordResetService.TOKEN_TTL.toMinutes()
				+ " minutes to choose a new password:\n" + link + "\n\n"
				+ "The link works once. If you didn't ask for this, ignore this email: your password stays the same.\n\n"
				+ "— Pulse";
	}

}
