package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.entities.Account;

public interface OnMessageAcknowledged {
	boolean onMessageAcknowledged(Account account, String id);
}
