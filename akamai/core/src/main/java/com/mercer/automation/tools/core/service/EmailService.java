package com.mercer.automation.tools.core.service;

import java.util.Map;

import javax.jcr.Session;

public interface EmailService {

	public void sendEmailWithTemplate(String templatePath, final String from, final String toEmail, String subject,
			Map<String, String> emailBody, Session session);

}
