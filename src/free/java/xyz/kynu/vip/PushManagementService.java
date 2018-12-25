package xyz.kynu.vip;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.services.XmppConnectionService;

public class PushManagementService {

	protected final XmppConnectionService mXmppConnectionService;

	public PushManagementService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	public void registerPushTokenOnServer(Account account) {
		//stub implementation. only affects playstore flavor
	}

	public boolean available(Account account) {
		return false;
	}

	public boolean isStub() {
		return true;
	}

	public boolean availableAndUseful(Account account) {
		return false;
	}
}
