package com.mercer.automation.tools.core.workflow;

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

@Component(service = WorkflowProcess.class, property = { "process.label=Mercer Access Request Review Approve" })
public class MercerAccessRequestReviewApprovalWorkflow implements WorkflowProcess {

	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Reference
	private ResourceResolverFactory resolverFactory;

	@Reference
	EmailService emailService;

	@Reference
	Externalizer externalizer;

	private String userId;
	private String opCoName;

	private String subject = "Mercer Group Access Approval Request";
	private String from = "mmc@amsmail.adobecqms.net";
	private String toEmail;
	private String approvers;
	private Session userSession;

	private static final String EMAIL_TEMPLATE_PATH = "/apps/mercer-automation-tools/emailTemplates/approver-notification.txt";

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

			externalizer = resolver.adaptTo(Externalizer.class);
			String inboxUrl = externalizer.externalLink(resolver, Externalizer.LOCAL, "/aem/inbox");

			StringBuffer emailList = new StringBuffer();

			Node wfNode = userSession.getNode(
					"/conf/global/settings/workflow/models/mercer/mercer-access-request-approval/jcr:content/flow/or/1/participant/metaData");
			String approverGroup = wfNode.getProperty("PARTICIPANT").getValue().toString();

			Authorizable authorizable = userManager.getAuthorizable(approverGroup);

			if (authorizable.isGroup()) {

				approvers = authorizable.getID();
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
			body.put("approvers", approvers);
			body.put("groups", sb.toString());
			body.put("opCoName", opCoName);
			body.put("inboxUrl", inboxUrl);

			toEmail = emailList.toString();
			emailService.sendEmailWithTemplate(EMAIL_TEMPLATE_PATH, from, toEmail, subject, body, userSession);

			userSession.save();
			userSession.logout();

		} catch (Exception e) {
			log.error("Exception in reviewer approval step " + e);
		}
	}

}