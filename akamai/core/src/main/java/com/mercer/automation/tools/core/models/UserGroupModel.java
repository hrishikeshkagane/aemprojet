package com.mercer.automation.tools.core.models;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.models.annotations.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mercer.automation.tools.core.ResourceUtil;

@Model(adaptables = Resource.class)
public class UserGroupModel {
	private static final Logger log = LoggerFactory.getLogger(UserGroupModel.class);

	private List<String> groups;

	private Session session;

	@Inject
	private ResourceResolverFactory factory;

	private ResourceResolver resolver;

	@PostConstruct
	protected void init() {

		try {
			resolver = ResourceUtil.getResourceResolver(factory);
			session = resolver.adaptTo(Session.class);
			UserManager userManager = ((JackrabbitSession) session).getUserManager();
			Iterator<Authorizable> groupIterator = userManager.findAuthorizables("jcr:primaryType", "rep:Group");
			groups = new LinkedList<>();
			while (groupIterator.hasNext()) {
				Authorizable group = groupIterator.next();
				if (group.isGroup()) {
					if (group.getID().startsWith("mercer")) {
						groups.add(group.getID());
					}
				}
			}
		} catch (Exception e) {

			log.error(e.getMessage(), e);
		}
	}

	public List<String> getGroups() {
		return groups;
	}

}
