package xyz.kynu.vip.generator;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.entities.Contact;
import xyz.kynu.vip.entities.MucOptions;
import xyz.kynu.vip.entities.Presence;
import xyz.kynu.vip.services.XmppConnectionService;
import xyz.kynu.vip.xml.Element;
import xyz.kynu.vip.xmpp.stanzas.PresencePacket;

public class PresenceGenerator extends AbstractGenerator {

	public PresenceGenerator(XmppConnectionService service) {
		super(service);
	}

	private PresencePacket subscription(String type, Contact contact) {
		PresencePacket packet = new PresencePacket();
		packet.setAttribute("type", type);
		packet.setTo(contact.getJid());
		packet.setFrom(contact.getAccount().getJid().asBareJid());
		return packet;
	}

	public PresencePacket requestPresenceUpdatesFrom(Contact contact) {
		return subscription("subscribe", contact);
	}

	public PresencePacket stopPresenceUpdatesFrom(Contact contact) {
		return subscription("unsubscribe", contact);
	}

	public PresencePacket stopPresenceUpdatesTo(Contact contact) {
		return subscription("unsubscribed", contact);
	}

	public PresencePacket sendPresenceUpdatesTo(Contact contact) {
		return subscription("subscribed", contact);
	}

	public PresencePacket selfPresence(Account account, Presence.Status status) {
		return selfPresence(account, status, false);
	}

	public PresencePacket selfPresence(Account account, Presence.Status status, boolean includePgpAnnouncement) {
		PresencePacket packet = new PresencePacket();
		if(status.toShowString() != null) {
			packet.addChild("show").setContent(status.toShowString());
		}
		packet.setFrom(account.getJid());
		final String capHash = getCapHash(account);
		if (capHash != null) {
			Element cap = packet.addChild("c",
					"http://jabber.org/protocol/caps");
			cap.setAttribute("hash", "sha-1");
			cap.setAttribute("node", "http://kynu.xyz");
			cap.setAttribute("ver", capHash);
		}
		return packet;
	}

	public PresencePacket leave(final MucOptions mucOptions) {
		PresencePacket presencePacket = new PresencePacket();
		presencePacket.setTo(mucOptions.getSelf().getFullJid());
		presencePacket.setFrom(mucOptions.getAccount().getJid());
		presencePacket.setAttribute("type", "unavailable");
		return presencePacket;
	}

	public PresencePacket sendOfflinePresence(Account account) {
		PresencePacket packet = new PresencePacket();
		packet.setFrom(account.getJid());
		packet.setAttribute("type","unavailable");
		return packet;
	}
}
