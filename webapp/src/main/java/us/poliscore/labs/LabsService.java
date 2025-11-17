package us.poliscore.labs;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.WebappDatabase;
import us.poliscore.billing.UserAccount;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class LabsService {
	@Inject
	@WebappDatabase
	ObjectStorageServiceIF ddb;
	
	@Inject
	ObjectMapper mapper;

	public void requestFeature(String featureId, String userId, String email) throws Exception {
		if (!userId.startsWith(UserAccount.ID_CLASS_PREFIX + "/"))
			userId = UserAccount.ID_CLASS_PREFIX + "/" + userId;
		
		String reqId = LabsFeatureRequest.generateId(featureId, userId);
		
		var req = new LabsFeatureRequest();
		req.setId(reqId);
		req.setCustomerId(userId);
		req.setCustomerEmail(email);
		req.setFeatureId(featureId);
		ddb.put(req);
	}
}
