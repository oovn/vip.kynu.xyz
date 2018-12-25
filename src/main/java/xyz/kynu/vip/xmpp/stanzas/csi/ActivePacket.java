package xyz.kynu.vip.xmpp.stanzas.csi;

import xyz.kynu.vip.xmpp.stanzas.AbstractStanza;

public class ActivePacket extends AbstractStanza {
	public ActivePacket() {
		super("active");
		setAttribute("xmlns", "urn:xmpp:csi:0");
	}
}
