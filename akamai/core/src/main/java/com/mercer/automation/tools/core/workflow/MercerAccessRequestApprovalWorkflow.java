package com.mercer.automation.tools.core.workflow;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.commons.Externalizer;
import com.mercer.automation.tools.core.ResourceUtil;
import com.mercer.automation.tools.core.service.EmailService;

@Component(service = WorkflowProcess.class, property = { "process.label= Approve Mercer Access Request" })
public class MercerAccessRequestApprovalWorkflow implements WorkflowProcess {

	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Reference
	EmailService emailService;

	@Reference
	Externalizer externalizer;

	@Reference
	private ResourceResolverFactory resolverFactory;

	private String userId;
	private String opCoName;

	private String subject = "Your AEM group assignment request has been granted";
	private String from = "mmc@amsmail.adobecqms.net";
	private String toEmail;
	private String reviewer;

	private static final String EMAIL_TEMPLATE_PATH = "/apps/mercer-automation-tools/emailTemplates/final-notification.txt";

	private Session userSession;

	@Override
	public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
		// TODO Auto-generated method stub
		try {

			WorkflowData workflowData = item.getWorkflowData();

			String groups[] = workflowData.getPayload().toString().split("/");

			userId = groups[0].trim();
			opCoName = groups[1].trim();
			StringBuffer sb = new StringBuffer();
			sb.append(groups[2]);
			for (int i = 3; i < groups.length; i++) {
				sb.append("," + groups[i]);
			}

			ResourceResolver resolver = ResourceUtil.getResourceResolver(resolverFactory);
			userSession = resolver.adaptTo(Session.class);
			UserManager userManager = ((JackrabbitSession) userSession).getUserManager();

			externalizer = resolver.adaptTo(Externalizer.class);
			String toolUrl = externalizer.externalLink(resolver, Externalizer.LOCAL, "/");

			String env;

			if ((toolUrl.toLowerCase()).contains("qa")) {
				env = "QA";
			} else if ((toolUrl.toLowerCase()).contains("stage")) {
				env = "STAGE";
			} else {
				env = "PROD";
			}

			User user = (User) userManager.getAuthorizable(userId);

			for (int i = 2; i < groups.length; i++) {
				Group group = (Group) userManager.getAuthorizable(groups[i]);
				group.addMember(user);
			}

			StringBuffer emailList = new StringBuffer();

			Node wfNode = userSession.getNode(
					"/conf/global/settings/workflow/models/mercer/mercer-access-request-approval/jcr:content/flow/participant/metaData");
			String reviewerGroup = wfNode.getProperty("PARTICIPANT").getValue().toString();

			if (user.hasProperty("./profile/email")) {
				Value[] email = user.getProperty("./profile/email");
				log.info("email is  " + email[0].getString());
				emailList.append(email[0].getString() + ";");
			}

			Authorizable authorizable = userManager.getAuthorizable(reviewerGroup);

			if (authorizable.isGroup()) {

				reviewer = authorizable.getID();
				Group group = (Group) authorizable;

				Iterator itr = group.getMembers();
				while (itr.hasNext()) {
					Object obj = itr.next();
					if (obj instanceof User) {
						User member = (User) obj;
						log.info("Member Name " + member.getID());
						Authorizable userAuthorization = userManager.getAuthorizable(member.getID());
						if (userAuthorization.hasProperty("./profile/email")) {
							Value[] email = userAuthorization.getProperty("./profile/email");
							log.info("email is  " + email[0].getString());
							emailList.append(email[0].getString() + ";");
						}
					}
				}
			}

			final Map<String, String> body = new HashMap<String, String>();

			body.put("userId", userId);
			body.put("groups", sb.toString());
			body.put("toolUrl", toolUrl);
			body.put("env", env);

			toEmail = emailList.toString();
			emailService.sendEmailWithTemplate(EMAIL_TEMPLATE_PATH, from, toEmail, env + ": " + subject, body,
					userSession);

			userSession.save();
			userSession.logout();
		} catch (Exception e) {
			log.error("Exception in MercerAccessRequestApprovalWorkflow step " + e);
		}

	}

}
