package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.xmpp.stanzas.MessagePacket;

public interface OnMessagePacketReceived extends PacketReceived {
	void onMessagePacketReceived(Account account, MessagePacket packet);
}
