package com.mercer.automation.tools.core.workflow;

import javax.jcr.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import javax.jcr.Value;
import com.day.cq.commons.Externalizer;
import java.util.HashMap;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;

//Sling Imports
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceResolver;
import com.mercer.automation.tools.core.ResourceUtil;
import com.mercer.automation.tools.core.service.EmailService;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = WorkflowProcess.class, property = { "process.label=Mercer Access Request Review Reject" })
public class MercerAccessRequestReviewRejectionWorkflow implements WorkflowProcess {

	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Reference
	EmailService emailService;

	@Reference
	private ResourceResolverFactory resolverFactory;

	@Reference
	Externalizer externalizer;

	private String userId;
	private String opCoName;

	private String subject = "Mercer Group Access Review Request Rejected";
	private String from = "mmc@amsmail.adobecqms.net";
	private String toEmail;
	private Session userSession;

	private static final String EMAIL_TEMPLATE_PATH = "/apps/mercer-automation-tools/emailTemplates/reviewer-reject-notification.txt";

	public void execute(WorkItem item, WorkflowSession wfsession, MetaDataMap args) throws WorkflowException {

		try {

			WorkflowData workflowData = item.getWorkflowData();

			String groups[] = workflowData.getPayload().toString().split("/");

			userId = groups[0];
			opCoName = groups[1];

			StringBuffer sb = new StringBuffer();
			sb.append(groups[2]);
			for (int i = 3; i < groups.length; i++) {
				sb.append("," + groups[i]);
			}

			ResourceResolver resolver = ResourceUtil.getResourceResolver(resolverFactory);
			userSession = resolver.adaptTo(Session.class);
			UserManager userManager = ((JackrabbitSession) userSession).getUserManager();

			StringBuffer emailList = new StringBuffer();

			User user = (User) userManager.getAuthorizable(userId);
			if (user.hasProperty("./profile/email")) {
				Value[] email = user.getProperty("./profile/email");
				log.info("email is  " + email[0].getString());
				emailList.append(email[0].getString() + ";");
			}

			final Map<String, String> body = new HashMap<String, String>();

			body.put("userId", userId);
			body.put("opCoName", opCoName);
			body.put("groups", sb.toString());

			toEmail = emailList.toString();
			emailService.sendEmailWithTemplate(EMAIL_TEMPLATE_PATH, from, toEmail, subject, body, userSession);

			userSession.save();
			userSession.logout();

		} catch (Exception e) {
			log.error("Exception in MercerAccessRequestReviewRejectionWorkflow method " + e);
			e.printStackTrace();
		}
	}

}