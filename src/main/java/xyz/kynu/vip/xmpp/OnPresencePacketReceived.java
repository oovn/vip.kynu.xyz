package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.xmpp.stanzas.PresencePacket;

public interface OnPresencePacketReceived extends PacketReceived {
	void onPresencePacketReceived(Account account, PresencePacket packet);
}
