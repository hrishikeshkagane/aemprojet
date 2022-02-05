package com.mercer.automation.tools.core;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;


public class ResourceUtil {

	private static final String SUBSERVICE_NAME = "mercerWriteService";
	
	 public static ResourceResolver getResourceResolver(ResourceResolverFactory factory) 
	    		throws Exception {
	        Map<String,Object> paramMap = new HashMap<String,Object>();
	        paramMap.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME);
	        paramMap.put(ResourceResolverFactory.USER, "mercer_system_user");

	        try {
	            ResourceResolver serviceResourceResolver = factory.getServiceResourceResolver(paramMap);
	            return serviceResourceResolver;
	        } catch (LoginException e) {
	            throw new Exception("Could not retrieve the ResourceResolver.", e);
	        }
	    }
}
