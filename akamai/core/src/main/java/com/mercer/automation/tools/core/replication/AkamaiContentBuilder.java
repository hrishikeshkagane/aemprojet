package com.mercer.automation.tools.core.replication;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import javax.jcr.Session;

import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import com.day.cq.replication.ContentBuilder;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationContent;
import com.day.cq.replication.ReplicationContentFactory;
import com.day.cq.replication.ReplicationException;
import com.day.cq.wcm.api.Page;
import com.adobe.acs.commons.genericlists.GenericList;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Akamai content builder to create replication content containing a JSON array
 * of URLs for Akamai to purge through the Akamai Transport Handler. This class
 * takes the internal resource path and converts it to external URLs as well as
 * adding vanity URLs and pages that may Sling include the activated resource.
 */
@Component(service = ContentBuilder.class, property = {"name=akamai",
        "service.ranking:Integer=1000",
        "metatype:Boolean=true",
        "service.description=Mercer Akamai Flush Agent",
        "service.vendor=MMC - Mercer"})
public class AkamaiContentBuilder implements ContentBuilder {

    /** The Default Logger to log the Content Builders Activities. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AkamaiContentBuilder.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** The name of the replication agent */
    private static final String NAME = "akamai";

    /**
     * The serialization type as it will display in the replication
     * agent edit dialog selection field.
     */
    private static final String TITLE = "Mercer Akamai Purge Agent";

	/** The Constant GENERIC_LIST_PATH. */
	private static final String GENERIC_LIST_PATH = "/etc/acs-commons/lists/mercer/mercer-akamai-purge-domain-mapping";

	/** The Content Prefix to check for the Cache Flush. */
	private static final String CONTENT_PREFIX = "/content";
	
	/** The Mercer Content check for the Cache Flush. */
	private static final String MERCER_CONTENT_CHECK = "mercer";

    /**
     * {@inheritDoc}
     */
    @Override
    public ReplicationContent create(final Session session, final ReplicationAction action,
            final ReplicationContentFactory factory) throws ReplicationException {    	
        return create(session, action, factory, null);
    }

    /**
     * Create the replication content containing the public facing URLs for
     * Akamai to purge.
     */
    @Override
    public ReplicationContent create(final Session session, final ReplicationAction action,
            final ReplicationContentFactory factory, final Map<String, Object> parameters)
            throws ReplicationException {

        final String path = action.getPath();
        ResourceResolver resourceResolver = null;
        // Check if the path starts with <code>/content</code>, else ignore the paths.
        List<String> mappings = Collections.emptyList();
        if (StringUtils.startsWith(path, CONTENT_PREFIX) && StringUtils.contains(path, MERCER_CONTENT_CHECK)) {
            try {
                // Get the Resource Resolver with the existing users session.
                resourceResolver = resolverFactory.getResourceResolver(Collections.singletonMap("user.jcr.session", session));
                final PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
                final Resource acsCommonsResource = resourceResolver.getResource(GENERIC_LIST_PATH);
                if (acsCommonsResource != null) {
                    final Page configPage = acsCommonsResource.adaptTo(Page.class);
                    if (configPage != null) {
                        final GenericList genericList = configPage.adaptTo(GenericList.class);
                        mappings = getMappingsForPath(genericList, pageManager, path);                        
                    }
                } else {
                    LOGGER.error("Sites List is not configured. Unable to get the language root paths.");
                }
            } catch (final LoginException loginException) {
                LOGGER.error("Unable to get the Resource Resolver ", loginException);
            } finally {
                // TODO: Check if need to close the session intiated by the Replication Context.
                if (resourceResolver != null && resourceResolver.isLive()) resourceResolver.close();
            }
        } else {
            LOGGER.debug("The Path [{}] does not starts with [{}]", path, CONTENT_PREFIX);
        }
        return createContent(factory, mappings);
    }

    /**
     * The Method to get the List of URLs to trigger for the flush.
     *
     * This method will filter the existing / configured Generic List
     * and will create the list of URLs along with the path to flush.
     *
     * NOTE: If the URLs configured in the Generic List is same as the activate / deleted path, then
     * this method will return the path of the configured host as it may happen that
     * the path has been configured is the homepage of the site.
     *
     * @param genericList the Generic List obtained from the configured ACS Commons List.
     * @param path the activated / deleted path from the JCR.
     * @return the list of paths to flush from the Akamai.
     */
    private List<String> getMappingsForPath(final GenericList genericList, final PageManager pageManager, final String path) {

        if (genericList != null) {
            return genericList
                    .getItems()
                    .stream()
                    .filter(item -> StringUtils.startsWith(path, item.getValue()))
                    .map(item -> {                    	                    
                    	 if (StringUtils.equals(path, item.getValue()) || StringUtils.contains(path, "flush-site-cache")) {                        	
                            return item.getTitle();
                        }
                        final String pagePath = pageManager.getContainingPage(path).getPath();                        
                        return item.getTitle()
                                + StringUtils.replace(pagePath, item.getValue(), StringUtils.EMPTY)
                                + ".html";
                    }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Create the replication content containing 
     *
     * @param factory Factory to create replication content
     * @return replication content
     *
     * @throws ReplicationException if an error occurs
     */
    private ReplicationContent createContent(final ReplicationContentFactory factory,
            final List<String> contentPaths) throws ReplicationException {
        if (contentPaths.isEmpty()) {
            return ReplicationContent.VOID;
        }
        try {
            final String temporaryFileName = "akamai_purge_agent" + System.currentTimeMillis();
            final Path tempFile = Files.createTempFile(temporaryFileName, ".tmp");
			LOGGER.debug("The File has been created at the path [{}], with content {}", tempFile.toUri(), contentPaths);
			final ObjectMapper mapper = new ObjectMapper();
            final BufferedWriter writer = Files.newBufferedWriter(tempFile, Charset.forName("UTF-8"));
            writer.write(mapper.writeValueAsString(contentPaths));
            writer.flush();
            return factory.create("text/plain", tempFile.toFile(), true);
        } catch (final IOException exception) {
            throw new ReplicationException("Could not create temporary file", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@value #NAME}
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@value #TITLE}
     */
    @Override
    public String getTitle() {
        return TITLE;
    }
}
