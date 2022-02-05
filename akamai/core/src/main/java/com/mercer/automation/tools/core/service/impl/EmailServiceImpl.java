package com.mercer.automation.tools.core.service.impl;

import java.util.ArrayList;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.mail.internet.InternetAddress;
import org.apache.commons.lang.text.StrLookup;
import org.apache.commons.mail.HtmlEmail;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.commons.mail.MailTemplate;
import com.day.cq.mailer.MessageGateway;
import com.day.cq.mailer.MessageGatewayService;
import com.mercer.automation.tools.core.service.EmailService;

@Component(immediate = true)
public class EmailServiceImpl implements EmailService{

	@Reference
	private MessageGatewayService messageGatewayService;
	
	
	@Override
	public void sendEmailWithTemplate(String templatePath, String from, String toEmail, String subject,
			Map<String, String> body, Session session) {

		final Logger log =LoggerFactory.getLogger(EmailService.class);
		
		ArrayList<InternetAddress> emailRecipients = new ArrayList<InternetAddress>();
		Node emailtemplateNode;
		
		StringBuffer emailList = new StringBuffer();

		try {

			emailtemplateNode = session.getNode(templatePath);
			log.info("emailtemplateNode "+emailtemplateNode.getName());
			MailTemplate mailTemplate = MailTemplate.create(templatePath, session);
			log.info("mailTemplate  "+mailTemplate);
			
			if (toEmail.indexOf(";") != -1) {
				log.info("adding multiple emails: " + toEmail);
				String[] multipleAddress = toEmail.split(";");
				for (String addresses : multipleAddress) {
					emailRecipients.add(new InternetAddress(addresses));
					log.info("emails added: " + emailRecipients.size());
				}
			} else {
				log.info("adding 1 email: " + toEmail);
				emailRecipients.add(new InternetAddress(toEmail));
			}
			
			HtmlEmail email = mailTemplate.getEmail(StrLookup.mapLookup(body), HtmlEmail.class);
			
			email.setFrom(from);
			email.setTo(emailRecipients);
			email.setSubject(subject);
			email.setCharset("UTF-8");

			final MessageGateway<HtmlEmail> messageGateway = messageGatewayService
					.getGateway(HtmlEmail.class);
			log.info("messageGateway "+messageGateway);
			log.info("Class:EmailImpl - sendEmail - messageGateway : " + messageGateway);
			if (null != messageGateway) {
				log.info("Sending email");
				messageGateway.send(email);
				log.info("Class:EmailServiceImpl - sendEmail - Email Sent!!");

			} else {
				log.info("Class:EmailServiceImpl - sendEmail - Please check email configurations.");
			}
		} catch (final Exception e) {
		 log.error("Class:EmailServiceImpl - sendEmail - Exception "+e);
		}

	}

}
