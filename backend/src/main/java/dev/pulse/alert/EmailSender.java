package dev.pulse.alert;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Plain-text email over SMTP (Mailpit locally). */
@Component
public class EmailSender implements AlertSender {

	private final JavaMailSender mail;
	private final AlertProperties properties;

	public EmailSender(JavaMailSender mail, AlertProperties properties) {
		this.mail = mail;
		this.properties = properties;
	}

	@Override
	public AlertChannelType type() {
		return AlertChannelType.EMAIL;
	}

	@Override
	public void send(String target, AlertMessage message) throws AlertDeliveryException {
		SimpleMailMessage email = new SimpleMailMessage();
		email.setFrom(properties.mailFrom());
		email.setTo(target);
		email.setSubject(message.title());
		email.setText(message.body() + "\n\n— Pulse");
		try {
			mail.send(email);
		}
		catch (MailException ex) {
			throw new AlertDeliveryException("The email could not be sent: " + ex.getMessage());
		}
	}

}
