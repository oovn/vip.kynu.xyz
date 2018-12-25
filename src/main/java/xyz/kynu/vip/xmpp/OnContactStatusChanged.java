package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.entities.Contact;

public interface OnContactStatusChanged {
	void onContactStatusChanged(final Contact contact, final boolean online);
}
