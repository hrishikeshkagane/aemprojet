package com.mercer.automation.tools.core.servlets;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.commons.Externalizer;
import com.day.cq.workflow.WorkflowService;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.model.WorkflowModel;
import com.mercer.automation.tools.core.service.EmailService;

@Component(service = { Servlet.class }, immediate = true, property = {
		"sling.servlet.methods=" + HttpConstants.METHOD_GET,
		"sling.servlet.paths=" + "/bin/mercer/automation/tools/access/request" })
public class MercerAccessRequestServlet extends SlingSafeMethodsServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	/** Default log. */
	protected final Logger log = LoggerFactory.getLogger(this.getClass());

	@Reference
	private WorkflowService workflowService;

	@Reference
	private ResourceResolverFactory resolverFactory;

	private String user;
	private String opCoName;
	private String groupList[];
	private String list;
	private StringBuffer sb;
	private String reviewer;

	private String from = "mmc@amsmail.adobecqms.net";
	private String subject = "Mercer Group Access Review Request";

	@Reference
	EmailService emailService;
	
	@Reference
	ResourceResolverFactory resourceResolverFactory;

	@Reference
	Externalizer externalizer;
	private String inboxUrl;

	private static final String EMAIL_TEMPLATE_PATH = "/apps/mercer-automation-tools/emailTemplates/reviewer-notification.txt";

	@Override
	protected void doGet(final SlingHttpServletRequest req, final SlingHttpServletResponse resp)
			throws ServletException, IOException {

		String toEmailAddress = null;
		
		Map<String,Object> paramMap = new HashMap<String,Object>();
        paramMap.put(ResourceResolverFactory.SUBSERVICE, "mercerWriteService");
        paramMap.put(ResourceResolverFactory.USER, "mercer_system_user");
        ResourceResolver resourceResolver = null;
		
		try {
			resourceResolver = resourceResolverFactory.getServiceResourceResolver(paramMap);
		} catch (LoginException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		Session session_wf = resourceResolver.adaptTo(Session.class);
		 Session session = req.getResourceResolver().adaptTo(Session.class);

		try {

			user = session.getUserID();

			opCoName = req.getParameter("opCoName");

			groupList = req.getParameterValues("groupList");

			sb = new StringBuffer();
			sb.append(user + "/");
			sb.append(opCoName + "/");
			for (int i = 0; i < groupList.length; i++) {
				sb.append(groupList[i] + "/");
			}

			list = sb.toString();

			externalizer = resourceResolver.adaptTo(Externalizer.class);
			inboxUrl = externalizer.externalLink(resourceResolver, Externalizer.LOCAL, "/aem/inbox");

			WorkflowSession wfSession = workflowService.getWorkflowSession(session_wf);

			String workflowName = "/var/workflow/models/mercer/mercer-access-request-approval";

			WorkflowModel wfModel = wfSession.getModel(workflowName);

			WorkflowData wfData = wfSession.newWorkflowData("JCR_PATH", list);

			wfSession.startWorkflow(wfModel, wfData);

			UserManager manager = resourceResolver.adaptTo(UserManager.class);
			Node wfNode = session.getNode(
					"/conf/global/settings/workflow/models/mercer/mercer-access-request-approval/jcr:content/flow/participant/metaData");
			String reviewerGroup = wfNode.getProperty("PARTICIPANT").getValue().toString();

			Authorizable authorizable = manager.getAuthorizable(reviewerGroup);

			StringBuffer emailList = new StringBuffer();

			if (authorizable.isGroup()) {

				reviewer = authorizable.getID();
				Group group = (Group) authorizable;

				Iterator itr = group.getMembers();
				while (itr.hasNext()) {
					Object obj = itr.next();
					if (obj instanceof User) {
						User user = (User) obj;
						log.info("Member Name " + user.getID());
						Authorizable userAuthorization = manager.getAuthorizable(user.getID());
						if (userAuthorization.hasProperty("./profile/email")) {
							Value[] email = userAuthorization.getProperty("./profile/email");
							log.info("email is  " + email[0].getString());
							emailList.append(email[0].getString() + ";");
						}
					}
				}
				toEmailAddress = emailList.toString();
				log.info("toEmailAddress  " + toEmailAddress);
			}
			
			if (!toEmailAddress.isEmpty()) {
				log.debug("calling notify email method");
				sendNotificationEmail(toEmailAddress, req, session);
			}
			session.save();
			session.logout();

			resp.sendRedirect("/content/mercer-automation-tools/thank-you-page.html?wcmmode=disabled");
		} catch (Exception e) {
			log.error("Exception in servlet " + e.getStackTrace()[0].getLineNumber());
			e.printStackTrace();
		}
	}

	public void sendNotificationEmail(String toEmailAddress, SlingHttpServletRequest req, Session session)
			throws LoginException {
		try {
			final Map<String, String> body = new HashMap<String, String>();

			body.put("reviewer", reviewer);
			body.put("Username", user);
			body.put("opCoName", opCoName);
			StringBuffer strb = new StringBuffer();

			strb.append(groupList[0]);
			for (int i = 1; i < groupList.length; i++) {
				strb.append("," + groupList[i]);
			}
			body.put("groups", strb.toString());
			body.put("inboxUrl", inboxUrl);

			emailService.sendEmailWithTemplate(EMAIL_TEMPLATE_PATH, from, toEmailAddress, subject, body, session);

		} catch (Exception e) {
			log.info("Exception while sending email notification Access Servlet" + e);
		}
	}

}