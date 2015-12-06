package me.michaeldick.sonosnpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.jws.WebService;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.soap.Detail;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPFactory;
import javax.xml.soap.SOAPFault;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.soap.SOAPFaultException;

import me.michaeldick.npr.model.Media;
import me.michaeldick.npr.model.Rating;
import me.michaeldick.npr.model.RatingsList;

import org.apache.commons.codec.binary.Base64;
import org.apache.cxf.headers.Header;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.jaxws.context.WrappedMessageContext;
import org.apache.cxf.message.Message;
import org.apache.log4j.Logger;
import org.w3c.dom.Node;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sonos.services._1.AbstractMedia;
import com.sonos.services._1.AddToContainerResult;
import com.sonos.services._1.AlbumArtUrl;
import com.sonos.services._1.ContentKey;
import com.sonos.services._1.CreateContainerResult;
import com.sonos.services._1.Credentials;
import com.sonos.services._1.DeleteContainerResult;
import com.sonos.services._1.DeviceAuthTokenResult;
import com.sonos.services._1.DeviceLinkCodeResult;
import com.sonos.services._1.DynamicData;
import com.sonos.services._1.ExtendedMetadata;
import com.sonos.services._1.GetExtendedMetadata;
import com.sonos.services._1.GetExtendedMetadataResponse;
import com.sonos.services._1.GetExtendedMetadataText;
import com.sonos.services._1.GetExtendedMetadataTextResponse;
import com.sonos.services._1.GetMediaMetadata;
import com.sonos.services._1.GetMediaMetadataResponse;
import com.sonos.services._1.GetMediaMetadataResponse.GetMediaMetadataResult;
import com.sonos.services._1.GetMetadata;
import com.sonos.services._1.GetMetadataResponse;
import com.sonos.services._1.GetSessionId;
import com.sonos.services._1.GetSessionIdResponse;
import com.sonos.services._1.HttpHeaders;
import com.sonos.services._1.ItemRating;
import com.sonos.services._1.ItemType;
import com.sonos.services._1.LastUpdate;
import com.sonos.services._1.LoginToken;
import com.sonos.services._1.MediaCollection;
import com.sonos.services._1.MediaList;
import com.sonos.services._1.MediaMetadata;
import com.sonos.services._1.Property;
import com.sonos.services._1.RateItem;
import com.sonos.services._1.RateItemResponse;
import com.sonos.services._1.RemoveFromContainerResult;
import com.sonos.services._1.RenameContainerResult;
import com.sonos.services._1.ReorderContainerResult;
import com.sonos.services._1.ReportPlaySecondsResult;
import com.sonos.services._1.Search;
import com.sonos.services._1.SearchResponse;
import com.sonos.services._1.SegmentMetadataList;
import com.sonos.services._1.TrackMetadata;
import com.sonos.services._1_1.CustomFault;
import com.sonos.services._1_1.SonosSoap;

import io.keen.client.java.JavaKeenClientBuilder;
import io.keen.client.java.KeenClient;
import io.keen.client.java.KeenProject;

@WebService
public class SonosService implements SonosSoap {

	public static String NPR_CLIENT_ID = "";
	public static String NPR_CLIENT_SECRET = "";
	
	public static String KEEN_PROJECT_ID = "";
	public static String KEEN_WRITE_KEY = "";
	public static String KEEN_READ_KEY = "";			
	
	public static final String PROGRAM = "program";
    public static final String DEFAULT = "default";
    public static final String SUGGESTIONS = "suggestions";
    public static final String PODCAST = "podcasts";
    public static final String AGGREGATION = "aggregation";
    public static final String SESSIONIDTOKEN = "###";

    
    // Error codes
    public static final String SESSION_INVALID = "Client.SessionIdInvalid";
    public static final String LOGIN_INVALID = "Client.LoginInvalid";
    public static final String SERVICE_UNKNOWN_ERROR = "Client.ServiceUnknownError";
    public static final String SERVICE_UNAVAILABLE = "Client.ServiceUnavailable";
    public static final String ITEM_NOT_FOUND = "Client.ItemNotFound"; 
    public static final String AUTH_TOKEN_EXPIRED = "Client.AuthTokenExpired";
    public static final String NOT_LINKED_RETRY = "Client.NOT_LINKED_RETRY";
    public static final String NOT_LINKED_FAILURE = "Client.NOT_LINKED_FAILURE";
	
    public static final String PLAYSTATUS_SKIPPED = "skippedTrack";      
    private static final String RATING_ISINTERESTING = "isliked";

    private static final String IDENTITY_API_URI_DEFAULT = "https://api.npr.org/identity/v2/user";
    private static final String LISTENING_API_URI_DEFAULT = "https://api.npr.org/listening/v2";
    private static final String DEVICE_LINK_URI_DEFAULT = "https://api.npr.org/authorization/v2/device";
    private static final String DEVICE_TOKEN_URI_DEFAULT = "https://api.npr.org/authorization/v2/token";
    
    private static String IDENTITY_API_URI;
    private static String LISTENING_API_URI;
    private static String DEVICE_LINK_URI;
    private static String DEVICE_TOKEN_URI;
    private static boolean isDebug = false;
    private static int NUMBER_OF_STORIES_PER_CALL = 3;
        
    private static Cache<String, Media> ListeningResponseCache;
    private static Cache<String, List<Rating>> RatingCache;    
    private static Cache<String, List<AbstractMedia>> LastResponseToPlayer;

    
    private static Logger logger = Logger.getLogger(SonosService.class.getSimpleName());
    private static KeenClient client;
    
    @Resource
	private WebServiceContext context;
    
    public WebServiceContext getContext() {
		return this.context;
	}
    
    public SonosService(Properties conf) {    	
    	IDENTITY_API_URI = conf.getProperty("IDENTITY_API_URI", IDENTITY_API_URI_DEFAULT);
    	LISTENING_API_URI = conf.getProperty("LISTENING_API_URI", LISTENING_API_URI_DEFAULT);
    	DEVICE_LINK_URI = conf.getProperty("DEVICE_LINK_URI", DEVICE_LINK_URI_DEFAULT);
    	DEVICE_TOKEN_URI = conf.getProperty("DEVICE_TOKEN_URI", DEVICE_TOKEN_URI_DEFAULT);
    	isDebug = Boolean.parseBoolean(conf.getProperty("SETDEBUG", "false"));
    	
    	NPR_CLIENT_ID = conf.getProperty("NPR_CLIENT_ID", System.getenv("NPR_CLIENT_ID"));
    	NPR_CLIENT_SECRET = conf.getProperty("NPR_CLIENT_SECRET", System.getenv("NPR_CLIENT_SECRET"));
    	
    	KEEN_PROJECT_ID = conf.getProperty("KEEN_PROJECT_ID", System.getenv("KEEN_PROJECT_ID"));
    	KEEN_READ_KEY = conf.getProperty("KEEN_READ_KEY", System.getenv("KEEN_READ_KEY"));
    	KEEN_WRITE_KEY = conf.getProperty("KEEN_WRITE_KEY", System.getenv("KEEN_WRITE_KEY"));
    	initializeCaches(); 
    	initializeMetrics();
    }
    
    public SonosService () {
    	IDENTITY_API_URI = IDENTITY_API_URI_DEFAULT;
    	LISTENING_API_URI = LISTENING_API_URI_DEFAULT;
    	DEVICE_LINK_URI = DEVICE_LINK_URI_DEFAULT;
    	DEVICE_TOKEN_URI = DEVICE_TOKEN_URI_DEFAULT;
    	
    	KEEN_PROJECT_ID = System.getenv("KEEN_PROJECT_ID");
    	KEEN_READ_KEY = System.getenv("KEEN_READ_KEY");
    	KEEN_WRITE_KEY = System.getenv("KEEN_WRITE_KEY");
    	NPR_CLIENT_ID = System.getenv("NPR_CLIENT_ID");
    	NPR_CLIENT_SECRET = System.getenv("NPR_CLIENT_SECRET");
    	initializeCaches();
    	initializeMetrics();
    }
    
    private void initializeCaches() {
 	
    	ListeningResponseCache = CacheBuilder.newBuilder()
		       .maximumSize(1000)
		       .expireAfterWrite(20, TimeUnit.MINUTES).build();
 	
    	RatingCache = CacheBuilder.newBuilder()
		       .maximumSize(1000)
		       .expireAfterWrite(20, TimeUnit.MINUTES).build();
    	
    	LastResponseToPlayer = CacheBuilder.newBuilder()
  		       .maximumSize(1000)
  		       .expireAfterWrite(20, TimeUnit.MINUTES).build();
    }
    
    public void initializeMetrics() {
    	client = new JavaKeenClientBuilder().build();
    	KeenClient.initialize(client);
    	KeenProject project = new KeenProject(KEEN_PROJECT_ID, KEEN_WRITE_KEY, KEEN_READ_KEY);
    	client.setDefaultProject(project);
    	
    	Map<String, Object> map = new HashMap<String, Object>();
    	map.put("isDebug", isDebug);
    	if(System.getenv("KEEN_ENV") != null)
    		map.put("env", System.getenv("KEEN_ENV"));    	
    	client.setGlobalProperties(map);
    }
    
	@Override
	public String getScrollIndices(String id) throws CustomFault {
		logger.info("getScrollIndices id:"+id);
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AddToContainerResult addToContainer(String id, String parentId,
			int index, String updateId) throws CustomFault {
		logger.info("addToContainer");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GetExtendedMetadataResponse getExtendedMetadata(
			GetExtendedMetadata parameters) throws CustomFault {
		logger.info("getExtendedMetadata id:"+parameters.getId());

		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		GetExtendedMetadataResponse response = new GetExtendedMetadataResponse();		
		Media m = ListeningResponseCache.getIfPresent(userId+parameters.getId());
		
        if (m != null) {
        	logger.debug("ListeningResponseCache hit");
        	MediaMetadata mmd = buildMMD(m);
        	ExtendedMetadata resultWrapper = new ExtendedMetadata();
			MediaMetadata result = new MediaMetadata();
			result.setId(mmd.getId());
			result.setItemType(mmd.getItemType());
			result.setTrackMetadata(mmd.getTrackMetadata());
			result.setMimeType(mmd.getMimeType());		
			result.setTitle(mmd.getTitle());
			result.setDynamic(mmd.getDynamic());
			
			resultWrapper.setMediaMetadata(result);
			response.setGetExtendedMetadataResult(resultWrapper);
			return response;					
		}
        
        throwSoapFault(ITEM_NOT_FOUND);
		return null;		
	}

	@Override
	public ReportPlaySecondsResult reportPlaySeconds(String id, int seconds)
			throws CustomFault {
		logger.info("reportPlaySeconds id:"+id+" seconds:"+seconds);
		if(seconds == 0) {
			Credentials creds = getCredentialsFromHeaders();
			if(creds == null)
				throwSoapFault(SESSION_INVALID);
						
			String userId = creds.getLoginToken().getHouseholdId();
			String auth = creds.getLoginToken().getToken();							
			logger.debug("Got userId from header:"+userId);
			
			List<Rating> ratingList = RatingCache.getIfPresent(userId);
			
			if(ratingList == null) {
				logger.debug("ratingList is empty");
				ratingList = new ArrayList<Rating>();		
			} else {
				logger.debug("ratingList cache hit");
				for(Rating r : ratingList) {
					if(r.getRating().equals(RatingsList.START)) {
						logger.debug("rating set to completed");
						r.setRating(RatingsList.COMPLETED);
					}
				}
			}		
	
			Media media = ListeningResponseCache.getIfPresent(userId+id);			
			if(media != null) {
				Media m = media;
				logger.debug("media cache hit");
				m.getRating().setRating(RatingsList.START);
				ratingList.add(new Rating(m.getRating()));
				List<Rating> list = new ArrayList<Rating>();
				list.add(new Rating(m.getRating()));
				RatingCache.put(userId, list);					 					 
				sendRecommendations(userId, ratingList, m.getRecommendations().get("application/json"), auth);												
			}	 		
		}
		ReportPlaySecondsResult result = new ReportPlaySecondsResult();
		result.setInterval(0);
		return result;
	}

	@Override
	public void reportStatus(String id, int errorCode, String message)
			throws CustomFault {
		logger.info("reportStatus");
		// TODO Auto-generated method stub		
	}

	@Override
	public RateItemResponse rateItem(RateItem parameters) throws CustomFault {
		logger.info("rateItem id:"+parameters.getId()+" rating:"+parameters.getRating());

		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		List<Rating> list = RatingCache.getIfPresent(userId);			
		if(list != null) {
			logger.debug("RatingCache hit");
			boolean alreadyThumbed = false;
			for(Rating r : list) {
				if(r.getMediaId().equals(parameters.getId()) && r.getRating().equals(RatingsList.THUMBUP)) {
					alreadyThumbed = true;
				}
			}
			if (!alreadyThumbed) {
				for(Rating r : list) {
					if(r.getMediaId().equals(parameters.getId())) {
						logger.debug("Setting rating");
						Rating rnew = new Rating(r);
						rnew.setRating(RatingsList.THUMBUP);
						list.add(rnew);
						RatingCache.put(userId, list);
						Gson gson = new GsonBuilder().create();
						logger.debug("Rating cache:"+gson.toJson(list));
						break;
					}
				}
			}
		}		
		
		Media media = ListeningResponseCache.getIfPresent(userId+parameters.getId());
		if(media != null) {
			Media ratedItem = media;
			ratedItem.getRating().setRating(RatingsList.THUMBUP);
			ListeningResponseCache.put(userId+parameters.getId(), media);
		}
		
		ItemRating rating = new ItemRating();
		rating.setShouldSkip(false);
		
		RateItemResponse response = new RateItemResponse();
		response.setRateItemResult(rating);
		return response;
	}

	@Override
	public void reportAccountAction(String type) throws CustomFault {
		logger.info("reportAccountAction");
		// TODO Auto-generated method stub

	}

	@Override
	public GetExtendedMetadataTextResponse getExtendedMetadataText(
			GetExtendedMetadataText parameters) throws CustomFault {
		logger.info("getExtendedMetadataText id:"+parameters.getId());
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RenameContainerResult renameContainer(String id, String title)
			throws CustomFault {
		logger.info("renameContainer");
		// TODO Auto-generated method stub
		
		return null;
	}

	@Override
	public void setPlayedSeconds(String id, int seconds) throws CustomFault {
		logger.info("setPlayedSeconds id:"+id+" sec:"+seconds);

		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		List<Rating> list = RatingCache.getIfPresent(userId);			
		if(list != null) {
			logger.debug("RatingCache hit");
			for(Rating r : list) {
				if(r.getMediaId().equals(id)) {
					logger.debug("Setting seconds");
					r.setElapsed(seconds);
					RatingCache.put(userId, list);
					break;
				}
			}		
		}
		ListeningResponseCache.invalidate(userId+id);
	}

	@Override
	public LastUpdate getLastUpdate() throws CustomFault {
		logger.info("getLastUpdate");
	
		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		LastUpdate response = new LastUpdate();
		
		List<Rating> list = RatingCache.getIfPresent(userId);				
		if(list != null) 
			response.setFavorites(Integer.toString(list.hashCode()));
		else
			response.setFavorites("1");
		response.setCatalog("currentCatalog");	
		logger.debug("Fav: "+response.getFavorites()+" Cat: "+response.getCatalog());
		return response;
	}

	@Override
	public DeviceLinkCodeResult getDeviceLinkCode(String householdId)
			throws CustomFault {
		logger.info("getDeviceLinkCode");

		Map<String, Object> event = new HashMap<String, Object>();
        event.put("userid", householdId);
        event.put("method", "getDeviceLinkCode");

        // Add it to the "purchases" collection in your Keen Project.
        KeenClient.client().addEvent("purchases", event);
		
		Form form = new Form();
		form.param("client_id", NPR_CLIENT_ID);				
		form.param("client_secret", NPR_CLIENT_SECRET);
		form.param("scope", "identity.readonly "+ 
				"identity.write " + 
				"listening.readonly " + 
				"listening.write " + 
				"localactivation");
		
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(DEVICE_LINK_URI)
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.form(form), String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			logger.debug("login NotAuthorized: "+e.getMessage());
			throwSoapFault(LOGIN_INVALID);
		} catch (BadRequestException e) {
			logger.debug("Bad request: "+e.getMessage());
			logger.debug(e.getResponse().readEntity(String.class));
			throwSoapFault(SERVICE_UNKNOWN_ERROR);
		}
		
		JsonParser parser = new JsonParser();
        JsonElement element = parser.parse(json);
        String verification_uri = "";
        String user_code = "";
        String device_code = "";
        if (element.isJsonObject()) {
        	JsonObject root = element.getAsJsonObject();
            verification_uri = root.get("verification_uri").getAsString();
            user_code = root.get("user_code").getAsString();
            device_code = root.get("device_code").getAsString();
            logger.debug("Got verification uri");
        }
		    
        DeviceLinkCodeResult response = new DeviceLinkCodeResult();
		response.setLinkCode(user_code);
		response.setRegUrl(verification_uri);
		response.setLinkDeviceId(device_code);
        response.setShowLinkCode(true);
		return response;
	}

	@Override
	public void deleteItem(String favorite) throws CustomFault {
		logger.info("deleteItem");
		// TODO Auto-generated method stub

	}

	@Override
	public DeviceAuthTokenResult getDeviceAuthToken(String householdId,
			String linkCode, String linkDeviceId) throws CustomFault {
		logger.info("getDeviceAuthToken");
		
		Form form = new Form();
		form.param("client_id", NPR_CLIENT_ID);				
		form.param("client_secret", NPR_CLIENT_SECRET);
		form.param("code", linkDeviceId);
		form.param("grant_type", "device_code");
		
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(DEVICE_TOKEN_URI)
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.entity(form,MediaType.APPLICATION_FORM_URLENCODED_TYPE), String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			logger.debug("Not linked retry: "+e.getMessage());
			logger.debug("Detailed response: "+e.getResponse().readEntity(String.class));
			throwSoapFault(NOT_LINKED_RETRY, "NOT_LINKED_RETRY", "5");
		} catch (BadRequestException e) {
			logger.debug("Bad request: "+e.getMessage());
			throwSoapFault(NOT_LINKED_FAILURE, "NOT_LINKED_FAILURE", "6");
		}
		
		JsonParser parser = new JsonParser();
        JsonElement element = parser.parse(json);
        String access_token = "";        
        if (element.isJsonObject()) {
        	JsonObject root = element.getAsJsonObject();
        	access_token = root.get("access_token").getAsString();            
            logger.debug("Got token");
        }
		    
        DeviceAuthTokenResult response = new DeviceAuthTokenResult();
		response.setAuthToken(access_token);	
		response.setPrivateKey("KEY");
		return response;
	}

	@Override
	public CreateContainerResult createContainer(String containerType,
			String title, String parentId, String seedId) throws CustomFault {
		logger.info("createContainer");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ReorderContainerResult reorderContainer(String id, String from,
			int to, String updateId) throws CustomFault {
		logger.info("reorderContainer");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SegmentMetadataList getStreamingMetadata(String id,
			XMLGregorianCalendar startTime, int duration) throws CustomFault {
		logger.info("getStreamingMetadata");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void getMediaURI(String id, Holder<String> getMediaURIResult,
			Holder<HttpHeaders> httpHeaders, Holder<Integer> uriTimeout)
			throws CustomFault {
		logger.info("getMediaURI id:"+id);
		
		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		Media m = ListeningResponseCache.getIfPresent(userId+id);
		if(m != null) {
			if(m.getAudioLinks().containsKey("audio/mp3") && !m.getAudioLinks().get("audio/mp3").endsWith(".mp4")) {			
				getMediaURIResult.value = m.getAudioLinks().get("audio/mp3");				
			}		
			else if(m.getAudioLinks().containsKey("audio/aac") && m.getAudioLinks().get("audio/aac").endsWith(".mp3")) {
				getMediaURIResult.value =  m.getAudioLinks().get("audio/aac");				
			} else {
				throwSoapFault(ITEM_NOT_FOUND);
			}
		} else {
			logger.debug("MediaURICache miss");
			throwSoapFault(ITEM_NOT_FOUND);
		}
	}

	@Override
	public GetMediaMetadataResponse getMediaMetadata(GetMediaMetadata parameters)
			throws CustomFault {
		logger.info("getMediaMetadata id:"+parameters.getId());
		
		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		GetMediaMetadataResponse response = new GetMediaMetadataResponse();		
		Media m = ListeningResponseCache.getIfPresent(userId+parameters.getId());
		
        if (m != null) {
        	logger.debug("ListeningResponseCache hit");
        	MediaMetadata mmd = buildMMD(m);
			GetMediaMetadataResult result = new GetMediaMetadataResult();
			result.setId(mmd.getId());
			result.setItemType(mmd.getItemType());
			result.setTrackMetadata(mmd.getTrackMetadata());
			result.setMimeType(mmd.getMimeType());		
			result.setTitle(mmd.getTitle());
			result.setDynamic(mmd.getDynamic());
			
			response.setGetMediaMetadataResult(result);
			return response;					
		}
        
        throwSoapFault(ITEM_NOT_FOUND);
		return null;					
	}

	@Override
	public GetMetadataResponse getMetadata(GetMetadata parameters)
			throws CustomFault {
		logger.info("getMetadata id:"+parameters.getId()+" count:"+parameters.getCount()+" index:"+parameters.getIndex());
		
		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		Map<String, Object> event = new HashMap<String, Object>();
        event.put("userid", userId);
        event.put("method", "getMetadata");

        // Add it to the "purchases" collection in your Keen Project.
        KeenClient.client().addEvent("purchases", event);
		
		if(parameters.getId().equals("root")) {
			GetMetadataResponse response = new GetMetadataResponse();
			MediaList ml = new MediaList();
			List<AbstractMedia> mcList = ml.getMediaCollectionOrMediaMetadata();
//			For now just a generic name (should display station name eventually)
			MediaCollection mc1 = new MediaCollection();			
			mc1.setTitle("Play NPR One");
			mc1.setId(SonosService.PROGRAM+":"+SonosService.DEFAULT);
			mc1.setItemType(ItemType.PROGRAM);
			mc1.setCanPlay(true);
			mc1.setCanEnumerate(true);
			mcList.add(mc1);		
			
			MediaCollection mc2 = new MediaCollection();			
			mc2.setTitle("Suggestions");
			mc2.setId(SonosService.PROGRAM+":"+SonosService.SUGGESTIONS);
			mc2.setItemType(ItemType.COLLECTION);
			mc2.setCanPlay(false);
			mc2.setCanEnumerate(true);
			mcList.add(mc2);
			
			ml.setCount(mcList.size());
			ml.setTotal(mcList.size());
			ml.setIndex(0);
			response.setGetMetadataResult(ml);
			
			return response;			
		} else if(parameters.getId().startsWith(SonosService.PROGRAM+":"+SonosService.DEFAULT) && parameters.getCount() > 0) {
			GetMetadataResponse response = new GetMetadataResponse();
			response.setGetMetadataResult(getProgram(userId, auth));
			return response;
		} else if(parameters.getId().startsWith(SonosService.PROGRAM+":"+SonosService.SUGGESTIONS)) {
			GetMetadataResponse response = new GetMetadataResponse();
			response.setGetMetadataResult(getSuggestions(userId, auth));
			return response;
		} else if(parameters.getId().startsWith(SonosService.PODCAST)) {
			MediaList ml = getProgram(userId, auth);
			Media m = ListeningResponseCache.getIfPresent(userId+parameters.getId().replaceAll(SonosService.PODCAST+":", ""));
			if (m != null) {
				ml.getMediaCollectionOrMediaMetadata().add(0, buildMMD(m));			
				ml.setCount(ml.getCount()+1);
				ml.setTotal(ml.getTotal()+1);
			}
			GetMetadataResponse response = new GetMetadataResponse();
			response.setGetMetadataResult(ml);
			return response;
		} else if(parameters.getId().startsWith(SonosService.AGGREGATION)) {
			GetMetadataResponse response = new GetMetadataResponse();
			response.setGetMetadataResult(getAggregation(parameters.getId().replaceAll(SonosService.AGGREGATION+":",""), userId, auth));
			return response;
		} else if(parameters.getId().equals(ItemType.SEARCH.value())) {
			GetMetadataResponse response = new GetMetadataResponse();
			MediaList ml = new MediaList();
			List<AbstractMedia> mcList = ml.getMediaCollectionOrMediaMetadata();
			
			MediaCollection mc1 = new MediaCollection();			
			mc1.setTitle("Podcasts");
			mc1.setId(SonosService.PODCAST);
			mc1.setItemType(ItemType.SEARCH);
			mcList.add(mc1);			 
			
			ml.setCount(mcList.size());
			ml.setTotal(mcList.size());
			ml.setIndex(0);
			response.setGetMetadataResult(ml);
			
			return response;
		}
		
		return null;
	}

	// No longer used after switch to oauth
	@Override
	public GetSessionIdResponse getSessionId(GetSessionId parameters)
			throws CustomFault {
		logger.debug("getSessionId");
		
		throwSoapFault(AUTH_TOKEN_EXPIRED);
		
		if(parameters.getUsername().equals("") || parameters.getPassword().equals(""))
			throwSoapFault(LOGIN_INVALID);
		
		logger.debug("Attempting login");
		String authParameter = "{\"username\":\""+parameters.getUsername()+"\",\"password\":\""+parameters.getPassword()+"\"}";
		byte[] encodedAuth = Base64.encodeBase64(authParameter.getBytes());
		Form form = new Form();
		form.param("auth", new String(encodedAuth));		
		
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(IDENTITY_API_URI)
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.entity(form,MediaType.APPLICATION_FORM_URLENCODED_TYPE), String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			logger.debug("login NotAuthorized: "+e.getMessage());
			throwSoapFault(LOGIN_INVALID);
		}
		
		JsonParser parser = new JsonParser();
        JsonElement element = parser.parse(json);
        String auth_token = "";
        String userId = "";
        if (element.isJsonObject()) {
        	JsonObject root = element.getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            auth_token = data.get("auth_token").getAsString();
            userId = data.getAsJsonObject("user").get("id").getAsString();
            logger.debug("Login successful for: "+userId);
        }
		    
		GetSessionIdResponse response = new GetSessionIdResponse();
		response.setGetSessionIdResult(userId+"###"+auth_token);
		return response;
	}

	@Override
	public ContentKey getContentKey(String id, String uri) throws CustomFault {
		logger.info("getContentKey");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RemoveFromContainerResult removeFromContainer(String id,
			String indices, String updateId) throws CustomFault {
		logger.info("removeFromContainer");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DeleteContainerResult deleteContainer(String id) throws CustomFault {
		logger.info("deleteContainer");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void reportPlayStatus(String id, String status) throws CustomFault {
		logger.info("reportPlayStatus");

		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		if(status.equals(PLAYSTATUS_SKIPPED)) {
			logger.debug("PlayStatus is skipped");
			List<Rating> list = RatingCache.getIfPresent(userId);			
			if(list != null) {
				logger.debug("Cache hit");
				for(Rating r : list) {
					if(r.getMediaId().equals(id)) {
						r.setRating(RatingsList.SKIP);
						RatingCache.put(userId, list);
						logger.debug("Rating set");
						break;
					}
				}
			}
			ListeningResponseCache.invalidate(userId+id);
		}
	}

	@Override
	public String createItem(String favorite) throws CustomFault {
		logger.info("createItem favorite:"+favorite);
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SearchResponse search(Search parameters) throws CustomFault {
		logger.info("search");
		if(parameters.getTerm() == null || parameters.getTerm().length() < 4)
		{
			SearchResponse response = new SearchResponse();
			response.setSearchResult(new MediaList());
			return response;
		}
		
		Credentials creds = getCredentialsFromHeaders();
		if(creds == null)
			throwSoapFault(SESSION_INVALID);
		
		String userId = creds.getLoginToken().getHouseholdId();
		String auth = creds.getLoginToken().getToken();
		logger.debug("Got userId from header:"+userId);
		
		Client client = ClientBuilder.newClient();
		String json = client.target(LISTENING_API_URI)		
				.path("search")
				.path("recommendations")
				.queryParam("searchTerms", parameters.getTerm())				
				.request(MediaType.APPLICATION_JSON_TYPE)
				.header("Authorization", "Bearer "+auth)
				.get(String.class);
		client.close();
		
		SearchResponse response = new SearchResponse();
		response.setSearchResult(parseMediaListResponse(userId, json));				
		return response;
	}

	// Private methods
	
	private static MediaList getProgram(String userId, String auth) {							
		MediaList ml = new MediaList();
    	List<AbstractMedia> mcList = ml.getMediaCollectionOrMediaMetadata();		
					
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(LISTENING_API_URI)
					.path("recommendations")					
					.queryParam("channel", "npr")
					.request(MediaType.APPLICATION_JSON_TYPE)
					.header("Authorization", "Bearer "+auth)
					.get(String.class);
			client.close();	
		}catch (NotAuthorizedException e) {
			throwSoapFault(AUTH_TOKEN_EXPIRED);
		}
		
		
		JsonParser parser = new JsonParser();
		JsonElement element = parser.parse(json);
	        
		JsonArray searchResultList = element.getAsJsonObject().getAsJsonArray("items");		
        
		List<AbstractMedia> lastProgramCall = LastResponseToPlayer.getIfPresent(userId);
		
        if (searchResultList == null)
        	return new MediaList(); 
        	        	
        LinkedList<String> newPlayQueue = new LinkedList<String>();
        for (int i = 0; i < searchResultList.size(); i++) { 
        	Media m = new Media(searchResultList.get(i).getAsJsonObject());
			MediaMetadata mmd = buildMMD(m);
			if(mmd != null) {							
				if(mcList.size() < NUMBER_OF_STORIES_PER_CALL) {
					boolean wasInLastCall = false;
						if(lastProgramCall != null) {
						for(AbstractMedia ele : lastProgramCall) {					
						if(ele.getId().equals(mmd.getId())) {						
							wasInLastCall = true;
							break;
						}
					}
				}
				if(!wasInLastCall)
					mcList.add(mmd);
				}
				newPlayQueue.add(mmd.getId());
				logger.debug("adding track id: "+mmd.getId());
				ListeningResponseCache.put(userId+mmd.getId(), m);					
			}
		}	        		
		
        ml.setCount(mcList.size());
		ml.setIndex(0);
		ml.setTotal(mcList.size());				
    	logger.debug("Got program list: "+mcList.size());
    	LastResponseToPlayer.put(userId, mcList);
			
    	return ml;                			
	}
	
	private static MediaList getSuggestions(String userId, String auth) {		
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(LISTENING_API_URI)
					.path("recommendations")								
					.queryParam("channel", "recommended")
					.request(MediaType.APPLICATION_JSON_TYPE)
					.header("Authorization", "Bearer " + auth)
					.get(String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			throwSoapFault(AUTH_TOKEN_EXPIRED);
		}
				
		return parseMediaListResponse(userId, json);						
	}

	private static MediaList parseMediaListResponse(String userId, String json) {
		JsonParser parser = new JsonParser();
		JsonElement element = parser.parse(json);
	        
		JsonArray mainResultList = element.getAsJsonObject().getAsJsonArray("items");			
		
        if (mainResultList != null) { 
        	MediaList ml = new MediaList();
        	List<AbstractMedia> mcList = ml.getMediaCollectionOrMediaMetadata();    
        	
            for (int i = 0; i < mainResultList.size(); i++) { 
            	Media m = new Media(mainResultList.get(i).getAsJsonObject());
            	if(m.getType()==Media.AggregationDocumentType.aggregation) {
            		mcList.add(buildMC(m));
            	} else {
					MediaMetadata mmd = buildMMD(m);
					// Trying to avoid duplicates here
					if(mmd != null) {		
						boolean doesExist = false;
						for(AbstractMedia cachedM : mcList)
						{
							if(cachedM.getId().equals(mmd.getId())) {
								doesExist = true;
								break;
							}
						}
						if(!doesExist) {
							mcList.add(buildMC(m));
							logger.debug("adding track id: "+mmd.getId());
							ListeningResponseCache.put(userId+mmd.getId(), m);
						} else {
							logger.debug("tracking existing in cache: "+mmd.getId());
						}					
					}
            	}
			}
			ml.setCount(mcList.size());
			ml.setIndex(0);
			ml.setTotal(mcList.size());				
        	logger.debug("Got program list: "+mcList.size());
        	return ml;
        } else {
        	return new MediaList();
        }
	}
	
	private static MediaList getAggregation(String id, String userId, String auth) {
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(LISTENING_API_URI)
					.path("aggregation")
					.path(id)
					.path("recommendations")						
					.queryParam("startNum", "0")
					.request(MediaType.APPLICATION_JSON_TYPE)
					.header("Authorization", "Bearer "+ auth)
					.get(String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			throwSoapFault(AUTH_TOKEN_EXPIRED);
		}
		
		return parseMediaListResponse(userId, json);
	}
	
	public static void main(String[] args) {
		Form form = new Form();
		form.param("client_id", NPR_CLIENT_ID);				
		form.param("client_secret", NPR_CLIENT_SECRET);
		form.param("scope", "identity.readonly "+ 
				"identity.write " + 
				"listening.readonly " + 
				"listening.write " + 
				"localactivation");
		
		String json = "";
		try {
			Client client = ClientBuilder.newClient();
			json = client.target(DEVICE_LINK_URI)
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.form(form), String.class);
			client.close();
		} catch (NotAuthorizedException e) {
			logger.debug("login NotAuthorized: "+e.getMessage());			
		} catch (BadRequestException e) {
			logger.debug("Bad request: "+e.getMessage());
			logger.debug(e.getResponse().readEntity(String.class));
		}
		System.out.println(json);
	}
	
	private static void sendRecommendations(String userId, List<Rating> ratingsToSend, String uri, String auth) {
	
		if(ratingsToSend != null)
		{
			logger.debug("sendRecommendations "+ratingsToSend.size()+" to "+uri);
			Gson gson = new GsonBuilder().create();
			String jsonOutput = gson.toJson(ratingsToSend);
			logger.debug("sending :"+jsonOutput);
			
			Client client = ClientBuilder.newClient();
			String json = client.target(uri)					
					.request(MediaType.APPLICATION_JSON_TYPE)
					.header("Authorization", "Bearer "+ auth)
					.post(Entity.json(jsonOutput), String.class);
			client.close();						
		}
	}
	
	private static MediaCollection buildMC(Media m) {	
		MediaCollection mc = new MediaCollection();
		
		if(m.getType().equals(Media.AggregationDocumentType.audio)) {
			Media audio = m;
			mc.setId(SonosService.PODCAST+":"+audio.getUid());
			mc.setItemType(ItemType.PROGRAM);
			mc.setTitle(audio.getTitle());
			mc.setArtist(audio.getProgram());
			mc.setCanPlay(true);
			mc.setCanEnumerate(true);
			
			if(audio.getImageLinks() != null) {
				logger.debug("Album art found");
				String albumArtUrlString = audio.getImageLinks().get("square");
				if(albumArtUrlString != null) {
					AlbumArtUrl albumArtUrl = new AlbumArtUrl();
					albumArtUrl.setValue(albumArtUrlString);
					mc.setAlbumArtURI(albumArtUrl);
				}
			}	
		} else if(m.getType().equals(Media.AggregationDocumentType.aggregation)) {
			Media agg = m;
			mc.setId(SonosService.AGGREGATION+":"+agg.getAffiliationId());
			mc.setItemType(ItemType.COLLECTION);
			mc.setTitle(agg.getTitle());			
			mc.setCanPlay(false);
			mc.setCanEnumerate(true);	
			
			if(agg.getImageLinks() != null) {
				logger.debug("Album art found");
				String albumArtUrlString = agg.getImageLinks().get("logo_square");
				if(albumArtUrlString != null) {
					AlbumArtUrl albumArtUrl = new AlbumArtUrl();
					albumArtUrl.setValue(albumArtUrlString);
					mc.setAlbumArtURI(albumArtUrl);
				}
			}
		}
		
		return mc;
	}
	
	private static MediaMetadata buildMMD(Media m) {
		MediaMetadata mmd = new MediaMetadata();
		TrackMetadata tmd = new TrackMetadata();
		if(m==null)
			return null;
		
		mmd.setId(m.getUid());
		
		if(m.getAudioLinks() != null) {
			// Just allowing mp3's for now
			if(m.getAudioLinks().containsKey("audio/mp3") && !m.getAudioLinks().get("audio/mp3").endsWith(".mp4")) {							
				mmd.setMimeType("audio/mp3");
			}		
			else if(m.getAudioLinks().containsKey("audio/aac") && m.getAudioLinks().get("audio/aac").endsWith(".mp3")) {				
				mmd.setMimeType("audio/mp3");
			}
			else {
				logger.debug("No mp3 links found");
				return null;
			}
		} else {
			logger.debug("No audio links found");
			return null;
		}
		
		mmd.setItemType(ItemType.TRACK);		
				
		mmd.setTitle(m.getTitle());
		
		Property property = new Property();
		property.setName(RATING_ISINTERESTING);
		if(m.getRating().getRating().equals(RatingsList.THUMBUP)) {
			property.setValue("1");			
		} else {
			property.setValue("0");
		}
		DynamicData dynData = new DynamicData();
		dynData.getProperty().add(property);
		mmd.setDynamic(dynData);
				
		tmd.setCanSkip(m.isSkippable());		
		tmd.setArtist(m.getProgram());
				
		if(m.getImageLinks() != null) {
			logger.debug("Album art found");
			String albumArtUrlString = m.getImageLinks().get("square");
			if(albumArtUrlString != null) {
				AlbumArtUrl albumArtUrl = new AlbumArtUrl();
				albumArtUrl.setValue(albumArtUrlString);
				tmd.setAlbumArtURI(albumArtUrl);
			}
		}
		tmd.setDuration(m.getDuration());

		mmd.setTrackMetadata(tmd);
		
		return mmd;
	}	
	
	private static void throwSoapFault(String faultMessage) {
		throwSoapFault(faultMessage, "", "");
	}
	
	private static void throwSoapFault(String faultMessage, String ExceptionDetail, String SonosError) throws RuntimeException {
		SOAPFault soapFault;
		try {
            soapFault = SOAPFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL).createFault();
            soapFault.setFaultString(faultMessage);
            soapFault.setFaultCode(new QName(faultMessage));
            
            if(!ExceptionDetail.isEmpty() && !SonosError.isEmpty()) {            
            	Detail detail = soapFault.addDetail();
            	SOAPElement el1 = detail.addChildElement("ExceptionDetail");
    	        el1.setValue(ExceptionDetail);
	            SOAPElement el = detail.addChildElement("SonosError");
	            el.setValue(SonosError);	            
            }
            
        } catch (Exception e2) {
            throw new RuntimeException("Problem processing SOAP Fault on service-side." + e2.getMessage());
        }
            throw new SOAPFaultException(soapFault);

    }
	
	private Credentials getCredentialsFromHeaders() {
		if(isDebug) {
			Credentials c = new Credentials();
			LoginToken t = new LoginToken();
			t.setHouseholdId("[thehouseholdid]");
			t.setToken("[thetoken]");
			c.setLoginToken(t);
			return c;
		}
		if(context == null)
			return null;
		MessageContext messageContext = context.getMessageContext();
		if (messageContext == null
				|| !(messageContext instanceof WrappedMessageContext)) {
			logger.error("Message context is null or not an instance of WrappedMessageContext.");
			return null;
		}

		Message message = ((WrappedMessageContext) messageContext)
				.getWrappedMessage();
		List<Header> headers = CastUtils.cast((List<?>) message
				.get(Header.HEADER_LIST));
		if (headers != null) {
			for (Header h : headers) {
				Object o = h.getObject();
				// Unwrap the node using JAXB
				if (o instanceof Node) {
					JAXBContext jaxbContext;
					try {
						jaxbContext = new JAXBDataBinding(Credentials.class)
								.getContext();
						Unmarshaller unmarshaller = jaxbContext
								.createUnmarshaller();
						o = unmarshaller.unmarshal((Node) o);
					} catch (JAXBException e) {
						// failed to get the credentials object from the headers
						logger.error(
								"JaxB error trying to unwrapp credentials", e);
					}
				}
				if (o instanceof Credentials) {
					return (Credentials) o;										
				} else {
					logger.error("no Credentials object");
				}
			}
		} else {
			logger.error("no headers found");
		}
		return null;
	}
}