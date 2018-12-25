package xyz.kynu.vip.xmpp.jingle.stanzas;

import xyz.kynu.vip.xml.Element;

public class Reason extends Element {
	private Reason(String name) {
		super(name);
	}

	public Reason() {
		super("reason");
	}
}
