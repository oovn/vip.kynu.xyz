package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.xmpp.stanzas.IqPacket;

public interface OnIqPacketReceived extends PacketReceived {
	void onIqPacketReceived(Account account, IqPacket packet);
}
