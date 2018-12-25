package xyz.kynu.vip.xmpp.stanzas;

import xyz.kynu.vip.xml.Element;
import xyz.kynu.vip.xmpp.InvalidJid;
import rocks.xmpp.addr.Jid;

abstract public class AbstractAcknowledgeableStanza extends AbstractStanza {

	protected AbstractAcknowledgeableStanza(String name) {
		super(name);
	}


	public String getId() {
		return this.getAttribute("id");
	}

	public void setId(final String id) {
		setAttribute("id", id);
	}

	public Element getError() {
		Element error = findChild("error");
		if (error != null) {
			for(Element element : error.getChildren()) {
				if (!element.getName().equals("text")) {
					return element;
				}
			}
		}
		return null;
	}

	public boolean valid() {
		return InvalidJid.isValid(getFrom()) && InvalidJid.isValid(getTo());
	}
}
