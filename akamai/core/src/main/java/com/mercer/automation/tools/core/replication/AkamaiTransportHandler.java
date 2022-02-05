package com.mercer.automation.tools.core.replication;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.jackrabbit.util.Base64;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.LoggerFactory;

import com.akamai.edgegrid.signer.ClientCredential;
import com.akamai.edgegrid.signer.exceptions.RequestSigningException;
import com.akamai.edgegrid.signer.googlehttpclient.GoogleHttpClientEdgeGridRequestSigner;
import com.day.cq.mailer.MessageGateway;
import com.day.cq.mailer.MessageGatewayService;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationContent;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationLog;
import com.day.cq.replication.ReplicationResult;
import com.day.cq.replication.ReplicationTransaction;
import com.day.cq.replication.TransportContext;
import com.day.cq.replication.TransportHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.ApacheHttpTransport;

/**
 * Transport handler to send test and purge requests to Akamai and handle
 * responses. The handler sets up basic authentication with the user/pass from
 * the replication agent's transport config and sends a GET request as a test
 * and POST as purge request. A valid test response is 200 while a valid purge
 * response is 201.
 * 
 * The transport handler is triggered by setting your replication agent's
 * transport URL's protocol to "akamai://".
 *
 * The transport handler builds the POST request body in accordance with
 * Akamai's CCU REST APIs
 * {@link https://akab-qjb3nc3zf4l6vpvd-xqqlkixlnopc7nno.purge.akamaiapis.net/}
 * using the replication agent properties.
 */
@Designate(ocd = AkamaiTransportHandler.AkamaiTransportHandlerConfig.class)
@Component(service = TransportHandler.class, immediate = true, property = { "process.label=Akamai Purge Agent",
		"service.ranking:Integer=1000" })
public class AkamaiTransportHandler implements TransportHandler {

	/**
	 * Protocol for replication agent transport URI that triggers this transport
	 * handler.
	 */
	private final static String AKAMAI_PROTOCOL = "akamai://";

	/** Akamai CCU REST API URL */
	private final static String AKAMAI_CCU_REST_API_URL = "https://akab-qjb3nc3zf4l6vpvd-xqqlkixlnopc7nno.purge.akamaiapis.net";

	/**
	 * Replication agent type property name. Valid values are "arl" and "cpcode".
	 */
	private final static String PROPERTY_TYPE = "akamaiType";

	/** Replication agent multifield CP Code property name. */
	private final static String PROPERTY_CP_CODES = "akamaiCPCodes";

	/**
	 * Replication agent domain property name. Valid values are "staging" and
	 * "production".
	 */
	private final static String PROPERTY_DOMAIN = "akamaiDomain";

	/**
	 * Replication agent action property name. Valid values are "remove" and
	 * "invalidate".
	 */
	private final static String PROPERTY_ACTION = "akamaiAction";

	/** Replication agent default type value */
	private final static String PROPERTY_TYPE_DEFAULT = "url";

	/** Replication agent default domain value */
	private final static String PROPERTY_DOMAIN_DEFAULT = "production";

	/** Replication agent default action value */
	private final static String PROPERTY_ACTION_DEFAULT = "remove";

	/** The String array of Email IDs to send an email to. */
	private String[] emailTo;

	/** The Sender Email Address. */
	private String emailFrom;

	/** The Akamai Access Token */
	private String akamaiAccessToken;

	/** The Akamai Client Token. */
	private String akamaiClientToken;

	/** The Akamai Client Secret Key. */
	private String akamaiClientSecret;

	/** The Akamai API Host. */
	private String akamaiHost;

	/** Transport URI */
	private static final String TRANSPORT_URI = "transportUri";

	/** The Default Logger to log the handlers activities. */
	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AkamaiTransportHandler.class);

	@Reference
	private MessageGatewayService messageGatewayService;

	protected void activate(final AkamaiTransportHandlerConfig configuration) {
		this.emailTo = configuration.email_to();
		this.emailFrom = configuration.email_from();
		this.akamaiAccessToken = configuration.akamaiAccessToken();
		this.akamaiClientToken = configuration.akamaiClientToken();
		this.akamaiClientSecret = configuration.akamaiClientSecret();
		this.akamaiHost = configuration.akamaiHost();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean canHandle(AgentConfig config) {
		final String transportURI = config.getTransportURI();
		return transportURI != null && transportURI.toLowerCase().startsWith(AKAMAI_PROTOCOL);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ReplicationResult deliver(final TransportContext ctx, final ReplicationTransaction tx)
			throws ReplicationException {

		final ReplicationActionType replicationType = tx.getAction().getType();
		final ReplicationLog log = tx.getLog();
		try {
			if (replicationType == ReplicationActionType.TEST) {
				return doTest(ctx, tx);
			}
			final ReplicationContent replicationContent = tx.getContent();
			if (replicationContent.getContentType() != null && isValidReplicationType(replicationType)) {
				LOGGER.debug("The Contents are {}", replicationContent);
				final String tempContent = IOUtils.toString(replicationContent.getInputStream(),
						Charset.defaultCharset());
				LOGGER.debug("The Temporary Content is {}", tempContent);
				final ObjectMapper mapper = new ObjectMapper();
				final List<String> listOfPaths = Arrays.asList(mapper.readValue(tempContent, String[].class));
				LOGGER.debug("The List of Paths are {}", listOfPaths);
				return doActivate(ctx, tx, listOfPaths);
			}
		} catch (final IOException | RequestSigningException exception) {
			LOGGER.error("Unable to read the replication content ", exception);
		}
		return ReplicationResult.OK;
	}

	private boolean isValidReplicationType(final ReplicationActionType replicationType) {
		return replicationType == ReplicationActionType.ACTIVATE || replicationType == ReplicationActionType.DEACTIVATE
				|| replicationType == ReplicationActionType.DELETE;
	}

	/**
	 * Send test request to Akamai via a GET request.
	 *
	 * Akamai will respond with a 200 HTTP status code if the request was
	 * successfully submitted. The response will have information about the queue
	 * length, but we're simply interested in the fact that the request was
	 * authenticated.
	 *
	 * @param ctx Transport Context
	 * @param tx  Replication Transaction
	 * @return ReplicationResult OK if 200 response from Akamai
	 * @throws ReplicationException
	 * @throws IOException 
	 * @throws RequestSigningException 
	 */
	
	  private ReplicationResult doTest(final TransportContext ctx, final
	ReplicationTransaction tx) throws ReplicationException, IOException, RequestSigningException {

		final ReplicationLog log = tx.getLog();
		ClientCredential clientCredential = ClientCredential.builder().accessToken(akamaiAccessToken)
				.clientToken(akamaiClientToken).clientSecret(akamaiClientSecret).host(akamaiHost).build();

		HttpTransport httpTransport = new ApacheHttpTransport();
		HttpRequestFactory httpRequestFactory = httpTransport.createRequestFactory();

		String jsonObject = "{\r\n" + 
				"    \"objects\": [\r\n" + 
				"        \"https://www.mercer.com/contact.html\"\r\n" + 
				"    ]\r\n" + 
				"}";

		URI uri = URI.create(getTransportURI(ctx).replace("production", "staging"));

		HttpRequest request = httpRequestFactory.buildPostRequest(new GenericUrl(uri),
				ByteArrayContent.fromString("application/json", jsonObject.toString()));
		final HttpResponse response = sendRequest(request, ctx, clientCredential);

		if (response != null) {
			final int statusCode = response.getStatusCode();
			LOGGER.info("Response code recieved: {}", statusCode);
			log.info(response.toString());
			log.info("---------------------------------------");
			if (statusCode == HttpStatus.SC_CREATED) {
				return ReplicationResult.OK;
			}
		} else {
			emailError("Akamai Response Object Is Null: Please check if the Akamai purge agent is working fine", Collections.emptyList());
		}
		return new ReplicationResult(false, 0, "Mercer Akamai Purge Connection Test Failed");
	}
	 

	/**
	 * Send purge request to Akamai via a POST request
	 *
	 * Akamai will respond with a 201 HTTP status code if the purge request was
	 * successfully submitted.
	 *
	 * @param ctx Transport Context
	 * @param tx  Replication Transaction
	 * @return ReplicationResult OK if 201 response from Akamai
	 * @throws ReplicationException
	 * @throws IOException
	 * @throws RequestSigningException
	 */
	private ReplicationResult doActivate(final TransportContext ctx, final ReplicationTransaction tx,
			final List<String> pathsToFlush) throws ReplicationException, IOException, RequestSigningException {

		ClientCredential clientCredential = ClientCredential.builder().accessToken(akamaiAccessToken)
				.clientToken(akamaiClientToken).clientSecret(akamaiClientSecret).host(akamaiHost).build();

		HttpTransport httpTransport = new ApacheHttpTransport();
		HttpRequestFactory httpRequestFactory = httpTransport.createRequestFactory();

		String jsonObject = createPostBody(ctx, pathsToFlush);

		URI uri = URI.create(getTransportURI(ctx));

		HttpRequest request = httpRequestFactory.buildPostRequest(new GenericUrl(uri),
				ByteArrayContent.fromString("application/json", jsonObject.toString()));
		final HttpResponse response = sendRequest(request, ctx, clientCredential);

		if (response != null) {
			final int statusCode = response.getStatusCode();
			LOGGER.info("Response code recieved: {}", statusCode);
			if (statusCode == HttpStatus.SC_CREATED) {
				return ReplicationResult.OK;
			}
		} else {
			emailError("Response Object was NULL", Collections.emptyList());
		}
		return new ReplicationResult(false, 0, "Replication failed");
	}

	/*
	 * Email error code to support team
	 * 
	 */
	private void emailError(final String statusCode, final List<String> pathsToFlush) {

		final HtmlEmail email = new HtmlEmail();
		final ArrayList<InternetAddress> emailRecipients = new ArrayList<>();
		try {
			for (final String emailId : emailTo) {
				emailRecipients.add(new InternetAddress(emailId));
			}
		} catch (final AddressException exception) {
			LOGGER.error("Please check the configured addresses ", exception);
		}
		try {
			email.setFrom(emailFrom);
			email.setTo(emailRecipients);
			LOGGER.debug(emailRecipients.toString());
			email.setSubject("Akamai Purge Request Failed");
			email.setMsg(getEmailMessage(statusCode, pathsToFlush));
			final MessageGateway<HtmlEmail> messageGateway = messageGatewayService.getGateway(HtmlEmail.class);
			LOGGER.debug("messageGateway---- {}", messageGateway);
			if (null != messageGateway) {
				messageGateway.send(email);
				LOGGER.info("The Email has been send to {}", Arrays.asList(emailTo));
			} else {
				LOGGER.error("Unable to get Message Gateway Service, please check the configuration ");
			}
		} catch (final EmailException exception) {
			LOGGER.error("There is an exception while sending an email ", exception);
		}
	}

	private String getEmailMessage(final String statusCode, final List<String> pathsToFlush) {
		return "Kindly check the akamai login credentials "
				+ "& documentation (https://developer.akamai.com/api/purge/ccu/debug.html) for debugging."
				+ " Status code:" + statusCode + " for the paths " + pathsToFlush;
	}

	/**
	 * Build preemptive basic authentication headers and send request.
	 *
	 * @param request The request to send to Akamai
	 * @param ctx     The TransportContext containing the username and password
	 * @return HttpResponse The HTTP response from Akamai
	 * @throws ReplicationException if a request could not be sent
	 */
	private HttpResponse sendRequest(final HttpRequest request, final TransportContext ctx,
			ClientCredential clientCredential) throws ReplicationException, RequestSigningException {

		LOGGER.debug("Inside Send Request method of Akamai");
		final String auth = ctx.getConfig().getTransportUser() + ":" + ctx.getConfig().getTransportPassword();
		final String encodedAuth = Base64.encode(auth);

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setAuthorization("Basic " + encodedAuth);
		httpHeaders.setContentType(ContentType.APPLICATION_JSON.getMimeType());

		request.setHeaders(httpHeaders);

		GoogleHttpClientEdgeGridRequestSigner requestSigner = new GoogleHttpClientEdgeGridRequestSigner(
				clientCredential);
		requestSigner.sign(request);

		HttpResponse response;

		try {
			response = request.execute();
			LOGGER.debug("Akamai Send Request Executed Successfully");
		} catch (IOException e) {
			throw new ReplicationException("Could not send replication request.", e);
		}
		return response;
	}

	/**
	 * Build the Akamai purge request body based on the replication agent settings
	 * and append it to the POST request.
	 *
	 * @param request The HTTP POST request to append the request body
	 * @param ctx     TransportContext
	 * @param log     the replication log to log the activities.
	 * @throws ReplicationException if errors building the request body
	 */
	private String createPostBody(final TransportContext ctx, final List<String> listOfPaths)
			throws ReplicationException {
		final ValueMap properties = ctx.getConfig().getProperties();
		final String type = PropertiesUtil.toString(properties.get(PROPERTY_TYPE), PROPERTY_TYPE_DEFAULT);

		final Map<String, Object> jsonObject = new HashMap<>();
		List<String> purgeObjects;
		;
		String tempString = null;
		/*
		 * Get list of CP codes or ARLs/URLs depending on agent setting
		 */
		if (type.equals(PROPERTY_TYPE_DEFAULT)) {
			purgeObjects = listOfPaths;
		} else {
			final String[] cpCodes = PropertiesUtil.toStringArray(properties.get(PROPERTY_CP_CODES));
			purgeObjects = Arrays.asList(cpCodes);
		}
		if (purgeObjects != null && !purgeObjects.isEmpty()) {
			try {
				jsonObject.put("objects", purgeObjects);
				final ObjectMapper mapper = new ObjectMapper();
				tempString = mapper.writeValueAsString(jsonObject);
				final StringEntity entity = new StringEntity(tempString, CharEncoding.ISO_8859_1);
				entity.setContentType("application/json");
			} catch (final IOException exception) {
				LOGGER.error("Unable to process the JSON / Map Object {}", jsonObject, exception);
			}
		} else {
			throw new ReplicationException("No CP codes or pages to purge");
		}
		return tempString;
	}

	private String getTransportURI(TransportContext ctx) throws IOException {
		LOGGER.info("Entering getTransportURI method.");
		final ValueMap properties = ctx.getConfig().getProperties();
		final String type = PropertiesUtil.toString(properties.get(PROPERTY_TYPE), PROPERTY_TYPE_DEFAULT);
		final String domain = PropertiesUtil.toString(properties.get(PROPERTY_DOMAIN), PROPERTY_DOMAIN_DEFAULT);
		final String action = PropertiesUtil.toString(properties.get(PROPERTY_ACTION), PROPERTY_ACTION_DEFAULT);
		String defaultTransportUri = akamaiHost + "/ccu/v3/" + action + "/" + type + "/" + domain;
		String transporturi = PropertiesUtil.toString(properties.get(TRANSPORT_URI), defaultTransportUri);

		if (StringUtils.isEmpty(transporturi)) {
			return defaultTransportUri;
		}
		if (transporturi.startsWith(AKAMAI_PROTOCOL)) {
			transporturi = transporturi.replace(AKAMAI_PROTOCOL, "https://");
		}
		transporturi = transporturi + "/ccu/v3/" + action + "/" + type + "/" + domain;
		LOGGER.info("Exiting getTransportURI method of Akamai Transport Handler : {}", transporturi);
		return transporturi;
	}

	@ObjectClassDefinition(name = "Akamai Transport Handler for Cache Purge", description = "The Email and other OSGI Configuration for the Akamai Cache Purge.")
	public @interface AkamaiTransportHandlerConfig {

		@AttributeDefinition(name = "Email To", description = "The Email ID for Recipient on Akamai Cache Purge", defaultValue = {
				"mhr-sitesupport@mercer.com" })
		public String[] email_to();

		@AttributeDefinition(name = "Email From", description = "The Email ID of the sender to send an email", defaultValue = "noreply@mercer.com")
		public String email_from();

		@AttributeDefinition(name = "Akamai Access Token", description = "The Akamai Access Token of the API User", defaultValue = "akab-da5emssjk6mpd4u3-n4askiss7c2refbt")
		public String akamaiAccessToken();

		@AttributeDefinition(name = "Akamai Client Token", description = "The Akamai Client Token of the API User", defaultValue = "akab-dq5cdr6ndrhpb2td-d63iick3grjceswx")
		public String akamaiClientToken();

		@AttributeDefinition(name = "Akamai Client Secret Key", description = "The Akamai Client Secret Key of the API User", defaultValue = "j+jf7tMwMjkc2DDa1XCH5+hzbalnO4KYzBjEhigDPOE=")
		public String akamaiClientSecret();

		@AttributeDefinition(name = "Akamai Host Name", description = "The Akamai Host of the API User", defaultValue = "akab-qjb3nc3zf4l6vpvd-xqqlkixlnopc7nno.purge.akamaiapis.net")
		public String akamaiHost();

	}

}
