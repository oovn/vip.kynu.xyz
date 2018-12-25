package xyz.kynu.vip.xmpp.jingle;

import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.xmpp.PacketReceived;
import xyz.kynu.vip.xmpp.jingle.stanzas.JinglePacket;

public interface OnJinglePacketReceived extends PacketReceived {
	void onJinglePacketReceived(Account account, JinglePacket packet);
}
