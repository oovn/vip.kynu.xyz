package xyz.kynu.vip.entities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import xyz.kynu.vip.Config;
import xyz.kynu.vip.R;
import xyz.kynu.vip.utils.JidHelper;
import xyz.kynu.vip.utils.UIHelper;
import xyz.kynu.vip.xml.Element;
import xyz.kynu.vip.xmpp.pep.Avatar;
import rocks.xmpp.addr.Jid;

public class Contact implements ListItem, Blockable {
	public static final String TABLENAME = "contacts";

	public static final String SYSTEMNAME = "systemname";
	public static final String SERVERNAME = "servername";
	public static final String JID = "jid";
	public static final String OPTIONS = "options";
	public static final String SYSTEMACCOUNT = "systemaccount";
	public static final String PHOTOURI = "photouri";
	public static final String ACCOUNT = "accountUuid";
    public static final String KEYS = "pgpkey";
	public static final String AVATAR = "avatar";
	public static final String LAST_PRESENCE = "last_presence";
	public static final String LAST_TIME = "last_time";
	public static final String GROUPS = "groups";
	private String accountUuid;
	private String systemName;
	private String serverName;
	private String presenceName;
	private String commonName;
	protected Jid jid;
	private int subscription = 0;
	private String systemAccount;
	private String photoUri;
	private final JSONObject keys;
	private JSONArray groups = new JSONArray();
	private final Presences presences = new Presences();
	protected Account account;
	protected Avatar avatar;

	private boolean mActive = false;
	private long mLastseen = 0;
	private String mLastPresence = null;

	public Contact(final String account, final String systemName, final String serverName,
	               final Jid jid, final int subscription, final String photoUri,
	               final String systemAccount, final String keys, final String avatar, final long lastseen,
	               final String presence, final String groups) {
		this.accountUuid = account;
		this.systemName = systemName;
		this.serverName = serverName;
		this.jid = jid;
		this.subscription = subscription;
		this.photoUri = photoUri;
		this.systemAccount = systemAccount;
		JSONObject tmpJsonObject;
		tmpJsonObject = new JSONObject();
		this.keys = tmpJsonObject;
		if (avatar != null) {
			this.avatar = new Avatar();
			this.avatar.sha1sum = avatar;
			this.avatar.origin = Avatar.Origin.VCARD; //always assume worst
		}
		try {
			this.groups = (groups == null ? new JSONArray() : new JSONArray(groups));
		} catch (JSONException e) {
			this.groups = new JSONArray();
		}
		this.mLastseen = lastseen;
		this.mLastPresence = presence;
	}

	public Contact(final Jid jid) {
		this.jid = jid;
		this.keys = new JSONObject();
	}

	public static Contact fromCursor(final Cursor cursor) {
		final Jid jid;
		try {
			jid = Jid.of(cursor.getString(cursor.getColumnIndex(JID)));
		} catch (final IllegalArgumentException e) {
			// TODO: Borked DB... handle this somehow?
			return null;
		}
		return new Contact(cursor.getString(cursor.getColumnIndex(ACCOUNT)),
				cursor.getString(cursor.getColumnIndex(SYSTEMNAME)),
				cursor.getString(cursor.getColumnIndex(SERVERNAME)),
				jid,
				cursor.getInt(cursor.getColumnIndex(OPTIONS)),
				cursor.getString(cursor.getColumnIndex(PHOTOURI)),
				cursor.getString(cursor.getColumnIndex(SYSTEMACCOUNT)),
                cursor.getString(cursor.getColumnIndex(KEYS)),
				cursor.getString(cursor.getColumnIndex(AVATAR)),
				cursor.getLong(cursor.getColumnIndex(LAST_TIME)),
				cursor.getString(cursor.getColumnIndex(LAST_PRESENCE)),
				cursor.getString(cursor.getColumnIndex(GROUPS)));
	}

	public String getDisplayName() {
		if (!TextUtils.isEmpty(this.systemName)) {
			return this.systemName;
		} else if (!TextUtils.isEmpty(this.serverName)) {
			return this.serverName;
		} else if (!TextUtils.isEmpty(this.presenceName) && mutualPresenceSubscription()) {
			return this.presenceName;
		} else if (jid.getLocal() != null) {
			return JidHelper.localPartOrFallback(jid);
		} else {
			return jid.getDomain();
		}
	}

	public String getProfilePhoto() {
		return this.photoUri;
	}

	public Jid getJid() {
		return jid;
	}

	@Override
	public List<Tag> getTags(Context context) {
		final ArrayList<Tag> tags = new ArrayList<>();
		for (final String group : getGroups(true)) {
			tags.add(new Tag(group, UIHelper.getColorForName(group)));
		}
		Presence.Status status = getShownStatus();
		if (status != Presence.Status.OFFLINE) {
			tags.add(UIHelper.getTagForStatus(context, status));
		}
		if (isBlocked()) {
			tags.add(new Tag(context.getString(R.string.blocked), 0xff2e2f3b));
		}
		if (showInPhoneBook()) {
			tags.add(new Tag(context.getString(R.string.phone_book), 0xFF1E88E5));
		}
		return tags;
	}

	public boolean match(Context context, String needle) {
		if (TextUtils.isEmpty(needle)) {
			return true;
		}
		needle = needle.toLowerCase(Locale.US).trim();
		String[] parts = needle.split("\\s+");
		if (parts.length > 1) {
			for (String part : parts) {
				if (!match(context, part)) {
					return false;
				}
			}
			return true;
		} else {
			return jid.toString().contains(needle) ||
					getDisplayName().toLowerCase(Locale.US).contains(needle) ||
					matchInTag(context, needle);
		}
	}

	private boolean matchInTag(Context context, String needle) {
		needle = needle.toLowerCase(Locale.US);
		for (Tag tag : getTags(context)) {
			if (tag.getName().toLowerCase(Locale.US).contains(needle)) {
				return true;
			}
		}
		return false;
	}

	public ContentValues getContentValues() {
		synchronized (this.keys) {
			final ContentValues values = new ContentValues();
			values.put(ACCOUNT, accountUuid);
			values.put(SYSTEMNAME, systemName);
			values.put(SERVERNAME, serverName);
			values.put(JID, jid.toString());
			values.put(OPTIONS, subscription);
			values.put(SYSTEMACCOUNT, systemAccount);
			values.put(PHOTOURI, photoUri);
			values.put(AVATAR, avatar == null ? null : avatar.getFilename());
			values.put(LAST_PRESENCE, mLastPresence);
			values.put(LAST_TIME, mLastseen);
			values.put(GROUPS, groups.toString());
			return values;
		}
	}

	public Account getAccount() {
		return this.account;
	}

	public void setAccount(Account account) {
		this.account = account;
		this.accountUuid = account.getUuid();
	}

	public Presences getPresences() {
		return this.presences;
	}

	public void updatePresence(final String resource, final Presence presence) {
		this.presences.updatePresence(resource, presence);
	}

	public void removePresence(final String resource) {
		this.presences.removePresence(resource);
	}

	public void clearPresences() {
		this.presences.clearPresences();
		this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
	}

	public Presence.Status getShownStatus() {
		return this.presences.getShownStatus();
	}

	public boolean setPhotoUri(String uri) {
		if (uri != null && !uri.equals(this.photoUri)) {
			this.photoUri = uri;
			return true;
		} else if (this.photoUri != null && uri == null) {
			this.photoUri = null;
			return true;
		} else {
			return false;
		}
	}

	public void setServerName(String serverName) {
		this.serverName = serverName;
	}

	public boolean setSystemName(String systemName) {
		final String old = getDisplayName();
		this.systemName = systemName;
		return !old.equals(getDisplayName());
	}

	public boolean setPresenceName(String presenceName) {
		final String old = getDisplayName();
		this.presenceName = presenceName;
		return !old.equals(getDisplayName());
	}

	public Uri getSystemAccount() {
		if (systemAccount == null) {
			return null;
		} else {
			String[] parts = systemAccount.split("#");
			if (parts.length != 2) {
				return null;
			} else {
				long id = Long.parseLong(parts[0]);
				return ContactsContract.Contacts.getLookupUri(id, parts[1]);
			}
		}
	}

	public void setSystemAccount(String account) {
		this.systemAccount = account;
	}

	private Collection<String> getGroups(final boolean unique) {
		final Collection<String> groups = unique ? new HashSet<>() : new ArrayList<>();
		for (int i = 0; i < this.groups.length(); ++i) {
			try {
				groups.add(this.groups.getString(i));
			} catch (final JSONException ignored) {
			}
		}
		return groups;
	}

	public void setOption(int option) {
		this.subscription |= 1 << option;
	}

	public void resetOption(int option) {
		this.subscription &= ~(1 << option);
	}

	public boolean getOption(int option) {
		return ((this.subscription & (1 << option)) != 0);
	}

	public boolean showInRoster() {
		return (this.getOption(Contact.Options.IN_ROSTER) && (!this
				.getOption(Contact.Options.DIRTY_DELETE)))
				|| (this.getOption(Contact.Options.DIRTY_PUSH));
	}

	public boolean showInPhoneBook() {
		return systemAccount != null && !systemAccount.trim().isEmpty();
	}

	public void parseSubscriptionFromElement(Element item) {
		String ask = item.getAttribute("ask");
		String subscription = item.getAttribute("subscription");

		if (subscription == null) {
			this.resetOption(Options.FROM);
			this.resetOption(Options.TO);
		} else {
			switch (subscription) {
				case "to":
					this.resetOption(Options.FROM);
					this.setOption(Options.TO);
					break;
				case "from":
					this.resetOption(Options.TO);
					this.setOption(Options.FROM);
					this.resetOption(Options.PREEMPTIVE_GRANT);
					this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
					break;
				case "both":
					this.setOption(Options.TO);
					this.setOption(Options.FROM);
					this.resetOption(Options.PREEMPTIVE_GRANT);
					this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
					break;
				case "none":
					this.resetOption(Options.FROM);
					this.resetOption(Options.TO);
					break;
			}
		}

		// do NOT override asking if pending push request
		if (!this.getOption(Contact.Options.DIRTY_PUSH)) {
			if ((ask != null) && (ask.equals("subscribe"))) {
				this.setOption(Contact.Options.ASKING);
			} else {
				this.resetOption(Contact.Options.ASKING);
			}
		}
	}

	public void parseGroupsFromElement(Element item) {
		this.groups = new JSONArray();
		for (Element element : item.getChildren()) {
			if (element.getName().equals("group") && element.getContent() != null) {
				this.groups.put(element.getContent());
			}
		}
	}

	public Element asElement() {
		final Element item = new Element("item");
		item.setAttribute("jid", this.jid.toString());
		if (this.serverName != null) {
			item.setAttribute("name", this.serverName);
		}
		for (String group : getGroups(false)) {
			item.addChild("group").setContent(group);
		}
		return item;
	}

	@Override
	public int compareTo(@NonNull final ListItem another) {
		return this.getDisplayName().compareToIgnoreCase(
				another.getDisplayName());
	}

	public String getServer() {
		return getJid().getDomain();
	}

	public boolean setAvatar(Avatar avatar) {
		if (this.avatar != null && this.avatar.equals(avatar)) {
			return false;
		} else {
			if (this.avatar != null && this.avatar.origin == Avatar.Origin.PEP && avatar.origin == Avatar.Origin.VCARD) {
				return false;
			}
			this.avatar = avatar;
			return true;
		}
	}

	public String getAvatarFilename() {
		return avatar == null ? null : avatar.getFilename();
	}

	public Avatar getAvatar() {
		return avatar;
	}

	public boolean mutualPresenceSubscription() {
		return getOption(Options.FROM) && getOption(Options.TO);
	}

	@Override
	public boolean isBlocked() {
		return getAccount().isBlocked(this);
	}

	@Override
	public boolean isDomainBlocked() {
		return getAccount().isBlocked(Jid.ofDomain(this.getJid().getDomain()));
	}

	@Override
	public Jid getBlockedJid() {
		if (isDomainBlocked()) {
			return Jid.ofDomain(getJid().getDomain());
		} else {
			return getJid();
		}
	}

	public boolean isSelf() {
		return account.getJid().asBareJid().equals(jid.asBareJid());
	}

	boolean isOwnServer() {
		return account.getJid().getDomain().equals(jid.asBareJid().toString());
	}

	public void setCommonName(String cn) {
		this.commonName = cn;
	}

	public void flagActive() {
		this.mActive = true;
	}

	public void flagInactive() {
		this.mActive = false;
	}

	public boolean isActive() {
		return this.mActive;
	}

	public boolean setLastseen(long timestamp) {
		if (timestamp > this.mLastseen) {
			this.mLastseen = timestamp;
			return true;
		} else {
			return false;
		}
	}

	public long getLastseen() {
		return this.mLastseen;
	}

	public void setLastResource(String resource) {
		this.mLastPresence = resource;
	}

	public String getLastResource() {
		return this.mLastPresence;
	}

	public String getServerName() {
		return serverName;
	}

    public final class Options {
		public static final int TO = 0;
		public static final int FROM = 1;
		public static final int ASKING = 2;
		public static final int PREEMPTIVE_GRANT = 3;
		public static final int IN_ROSTER = 4;
		public static final int PENDING_SUBSCRIPTION_REQUEST = 5;
		public static final int DIRTY_PUSH = 6;
		public static final int DIRTY_DELETE = 7;
	}
}
