package xyz.kynu.vip.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.BigPictureStyle;
import android.support.v4.app.NotificationCompat.Builder;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.NotificationCompat.CarExtender.UnreadConversation;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.ContextCompat;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;

import org.conscrypt.Conscrypt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.kynu.vip.Config;
import xyz.kynu.vip.R;
import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.entities.Contact;
import xyz.kynu.vip.entities.Conversation;
import xyz.kynu.vip.entities.Conversational;
import xyz.kynu.vip.entities.Message;
import xyz.kynu.vip.persistance.FileBackend;
import xyz.kynu.vip.ui.ConversationsActivity;
import xyz.kynu.vip.ui.ManageAccountActivity;
import xyz.kynu.vip.ui.TimePreference;
import xyz.kynu.vip.utils.Compatibility;
import xyz.kynu.vip.utils.GeoHelper;
import xyz.kynu.vip.utils.UIHelper;
import xyz.kynu.vip.xmpp.XmppConnection;

public class NotificationService {

    public static final Object CATCHUP_LOCK = new Object();

    private static final int LED_COLOR = 0xff00ff00;

    private static final String CONVERSATIONS_GROUP = "xyz.kynu.vip";
    private static final int NOTIFICATION_ID_MULTIPLIER = 1024 * 1024;
    private static final int NOTIFICATION_ID = 2 * NOTIFICATION_ID_MULTIPLIER;
    public static final int FOREGROUND_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 4;
    private static final int ERROR_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 6;
    private final XmppConnectionService mXmppConnectionService;
    private final LinkedHashMap<String, ArrayList<Message>> notifications = new LinkedHashMap<>();
    private final HashMap<Conversation, AtomicInteger> mBacklogMessageCounter = new HashMap<>();
    private Conversation mOpenConversation;
    private boolean mIsInForeground;
    private long mLastNotification;

    NotificationService(final XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    private static boolean displaySnoozeAction(List<Message> messages) {
        int numberOfMessagesWithoutReply = 0;
        for (Message message : messages) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                ++numberOfMessagesWithoutReply;
            } else {
                return false;
            }
        }
        return numberOfMessagesWithoutReply >= 3;
    }

    public static Pattern generateNickHighlightPattern(final String nick) {
        return Pattern.compile("(?<=(^|\\s))" + Pattern.quote(nick) + "(?=\\s|$|\\p{Punct})");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void initializeChannels() {
        final Context c = mXmppConnectionService;
        final NotificationManager notificationManager = c.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }

        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup("status", c.getString(R.string.notification_group_status_information)));
        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup("chats", c.getString(R.string.notification_group_messages)));
        final NotificationChannel foregroundServiceChannel = new NotificationChannel("foreground",
                c.getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        foregroundServiceChannel.setDescription(c.getString(R.string.foreground_service_channel_description));
        foregroundServiceChannel.setShowBadge(false);
        foregroundServiceChannel.setGroup("status");
        notificationManager.createNotificationChannel(foregroundServiceChannel);
        final NotificationChannel errorChannel = new NotificationChannel("error",
                c.getString(R.string.error_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        errorChannel.setDescription(c.getString(R.string.error_channel_description));
        errorChannel.setShowBadge(false);
        errorChannel.setGroup("status");
        notificationManager.createNotificationChannel(errorChannel);

        final NotificationChannel videoCompressionChannel = new NotificationChannel("compression",
                c.getString(R.string.video_compression_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        videoCompressionChannel.setShowBadge(false);
        videoCompressionChannel.setGroup("status");
        notificationManager.createNotificationChannel(videoCompressionChannel);

        final NotificationChannel exportChannel = new NotificationChannel("export",
                c.getString(R.string.export_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        exportChannel.setShowBadge(false);
        exportChannel.setGroup("status");
        notificationManager.createNotificationChannel(exportChannel);

        final NotificationChannel messagesChannel = new NotificationChannel("messages",
                c.getString(R.string.messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        messagesChannel.setShowBadge(true);
        messagesChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build());
        messagesChannel.setLightColor(LED_COLOR);
        final int dat = 70;
        final long[] pattern = {0, 3 * dat, dat, dat};
        messagesChannel.setVibrationPattern(pattern);
        messagesChannel.enableVibration(true);
        messagesChannel.enableLights(true);
        messagesChannel.setGroup("chats");
        notificationManager.createNotificationChannel(messagesChannel);
        final NotificationChannel silentMessagesChannel = new NotificationChannel("silent_messages",
                c.getString(R.string.silent_messages_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        silentMessagesChannel.setDescription(c.getString(R.string.silent_messages_channel_description));
        silentMessagesChannel.setShowBadge(true);
        silentMessagesChannel.setLightColor(LED_COLOR);
        silentMessagesChannel.enableLights(true);
        silentMessagesChannel.setGroup("chats");
        notificationManager.createNotificationChannel(silentMessagesChannel);

        final NotificationChannel quietHoursChannel = new NotificationChannel("quiet_hours",
                c.getString(R.string.title_pref_quiet_hours),
                NotificationManager.IMPORTANCE_LOW);
        quietHoursChannel.setShowBadge(true);
        quietHoursChannel.setLightColor(LED_COLOR);
        quietHoursChannel.enableLights(true);
        quietHoursChannel.setGroup("chats");
        quietHoursChannel.enableVibration(false);
        quietHoursChannel.setSound(null, null);

        notificationManager.createNotificationChannel(quietHoursChannel);
    }

    public boolean notify(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        return message.getStatus() == Message.STATUS_RECEIVED
                && !conversation.isMuted()
                && (conversation.alwaysNotify() || wasHighlightedOrPrivate(message))
                && (!conversation.isWithStranger() || notificationsFromStrangers());
    }

    private boolean notificationsFromStrangers() {
        return mXmppConnectionService.getBooleanPreference("notifications_from_strangers", R.bool.notifications_from_strangers);
    }

    private boolean isQuietHours() {
        if (!mXmppConnectionService.getBooleanPreference("enable_quiet_hours", R.bool.enable_quiet_hours)) {
            return false;
        }
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final long startTime = preferences.getLong("quiet_hours_start", TimePreference.DEFAULT_VALUE) % Config.MILLISECONDS_IN_DAY;
        final long endTime = preferences.getLong("quiet_hours_end", TimePreference.DEFAULT_VALUE) % Config.MILLISECONDS_IN_DAY;
        final long nowTime = Calendar.getInstance().getTimeInMillis() % Config.MILLISECONDS_IN_DAY;

        if (endTime < startTime) {
            return nowTime > startTime || nowTime < endTime;
        } else {
            return nowTime > startTime && nowTime < endTime;
        }
    }

    public void pushFromBacklog(final Message message) {
        if (notify(message)) {
            synchronized (notifications) {
                getBacklogMessageCounter((Conversation) message.getConversation()).incrementAndGet();
                pushToStack(message);
            }
        }
    }

    private AtomicInteger getBacklogMessageCounter(Conversation conversation) {
        synchronized (mBacklogMessageCounter) {
            if (!mBacklogMessageCounter.containsKey(conversation)) {
                mBacklogMessageCounter.put(conversation, new AtomicInteger(0));
            }
            return mBacklogMessageCounter.get(conversation);
        }
    }

    public void pushFromDirectReply(final Message message) {
        synchronized (notifications) {
            pushToStack(message);
            updateNotification(false);
        }
    }

    public void finishBacklog(boolean notify, Account account) {
        synchronized (notifications) {
            mXmppConnectionService.updateUnreadCountBadge();
            if (account == null || !notify) {
                updateNotification(notify);
            } else {
                final int count;
                final List<String> conversations;
                synchronized (this.mBacklogMessageCounter) {
                    conversations = getBacklogConversations(account);
                    count = getBacklogMessageCount(account);
                }
                updateNotification(count > 0, conversations);
            }
        }
    }

    private List<String> getBacklogConversations(Account account) {
        final List<String> conversations = new ArrayList<>();
        for (Iterator<Map.Entry<Conversation, AtomicInteger>> it = mBacklogMessageCounter.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Conversation, AtomicInteger> entry = it.next();
            if (entry.getKey().getAccount() == account) {
                conversations.add(entry.getKey().getUuid());
            }
        }
        return conversations;
    }

    private int getBacklogMessageCount(Account account) {
        int count = 0;
        for (Iterator<Map.Entry<Conversation, AtomicInteger>> it = mBacklogMessageCounter.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Conversation, AtomicInteger> entry = it.next();
            if (entry.getKey().getAccount() == account) {
                count += entry.getValue().get();
                it.remove();
            }
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": backlog message count=" + count);
        return count;
    }

    public void finishBacklog(boolean notify) {
        finishBacklog(notify, null);
    }

    private void pushToStack(final Message message) {
        final String conversationUuid = message.getConversationUuid();
        if (notifications.containsKey(conversationUuid)) {
            notifications.get(conversationUuid).add(message);
        } else {
            final ArrayList<Message> mList = new ArrayList<>();
            mList.add(message);
            notifications.put(conversationUuid, mList);
        }
    }

    public void push(final Message message) {
        synchronized (CATCHUP_LOCK) {
            final XmppConnection connection = message.getConversation().getAccount().getXmppConnection();
            if (connection != null && connection.isWaitingForSmCatchup()) {
                connection.incrementSmCatchupMessageCounter();
                pushFromBacklog(message);
            } else {
                pushNow(message);
            }
        }
    }

    private void pushNow(final Message message) {
        mXmppConnectionService.updateUnreadCountBadge();
        if (!notify(message)) {
            Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": suppressing notification because turned off");
            return;
        }
        final boolean isScreenOn = mXmppConnectionService.isInteractive();
        if (this.mIsInForeground && isScreenOn && this.mOpenConversation == message.getConversation()) {
            Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": suppressing notification because conversation is open");
            return;
        }
        synchronized (notifications) {
            pushToStack(message);
            final Conversational conversation = message.getConversation();
            final Account account = conversation.getAccount();
            final boolean doNotify = (!(this.mIsInForeground && this.mOpenConversation == null) || !isScreenOn)
                    && !account.inGracePeriod()
                    && !this.inMiniGracePeriod(account);
            updateNotification(doNotify, Collections.singletonList(conversation.getUuid()));
        }
    }

    public void clear() {
        synchronized (notifications) {
            for (ArrayList<Message> messages : notifications.values()) {
                markAsReadIfHasDirectReply(messages);
            }
            notifications.clear();
            updateNotification(false);
        }
    }

    public void clear(final Conversation conversation) {
        synchronized (this.mBacklogMessageCounter) {
            this.mBacklogMessageCounter.remove(conversation);
        }
        synchronized (notifications) {
            markAsReadIfHasDirectReply(conversation);
            if (notifications.remove(conversation.getUuid()) != null) {
                cancel(conversation.getUuid(), NOTIFICATION_ID);
                updateNotification(false, null, true);
            }
        }
    }

    private void markAsReadIfHasDirectReply(final Conversation conversation) {
        markAsReadIfHasDirectReply(notifications.get(conversation.getUuid()));
    }

    private void markAsReadIfHasDirectReply(final ArrayList<Message> messages) {
        if (messages != null && messages.size() > 0) {
            Message last = messages.get(messages.size() - 1);
            if (last.getStatus() != Message.STATUS_RECEIVED) {
                if (mXmppConnectionService.markRead((Conversation) last.getConversation(), false)) {
                    mXmppConnectionService.updateConversationUi();
                }
            }
        }
    }

    private void setNotificationColor(final Builder mBuilder) {
        mBuilder.setColor(ContextCompat.getColor(mXmppConnectionService, R.color.green600));
    }

    public void updateNotification(final boolean notify) {
        updateNotification(notify, null, false);
    }

    public void updateNotification(final boolean notify, final List<String> conversations) {
        updateNotification(notify, conversations, false);
    }

    private void updateNotification(final boolean notify, final List<String> conversations, final boolean summaryOnly) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);

        final boolean quiteHours = isQuietHours();

        final boolean notifyOnlyOneChild = notify && conversations != null && conversations.size() == 1; //if this check is changed to > 0 catchup messages will create one notification per conversation


        if (notifications.size() == 0) {
            cancel(NOTIFICATION_ID);
        } else {
            if (notify) {
                this.markLastNotification();
            }
            final Builder mBuilder;
            if (notifications.size() == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                mBuilder = buildSingleConversations(notifications.values().iterator().next(), notify, quiteHours);
                modifyForSoundVibrationAndLight(mBuilder, notify, quiteHours, preferences);
                notify(NOTIFICATION_ID, mBuilder.build());
            } else {
                mBuilder = buildMultipleConversation(notify, quiteHours);
                if (notifyOnlyOneChild) {
                    mBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
                }
                modifyForSoundVibrationAndLight(mBuilder, notify, quiteHours, preferences);
                if (!summaryOnly) {
                    for (Map.Entry<String, ArrayList<Message>> entry : notifications.entrySet()) {
                        String uuid = entry.getKey();
                        final boolean notifyThis =  notifyOnlyOneChild ? conversations.contains(uuid) : notify;
                        Builder singleBuilder = buildSingleConversations(entry.getValue(),notifyThis, quiteHours);
                        if (!notifyOnlyOneChild) {
                            singleBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);
                        }
                        modifyForSoundVibrationAndLight(singleBuilder, notifyThis, quiteHours, preferences);
                        singleBuilder.setGroup(CONVERSATIONS_GROUP);
                        setNotificationColor(singleBuilder);
                        notify(entry.getKey(), NOTIFICATION_ID, singleBuilder.build());
                    }
                }
                notify(NOTIFICATION_ID, mBuilder.build());
            }
        }
    }

    private void modifyForSoundVibrationAndLight(Builder mBuilder, boolean notify, boolean quietHours, SharedPreferences preferences) {
        final Resources resources = mXmppConnectionService.getResources();
        final String ringtone = preferences.getString("notification_ringtone", resources.getString(R.string.notification_ringtone));
        final boolean vibrate = preferences.getBoolean("vibrate_on_notification", resources.getBoolean(R.bool.vibrate_on_notification));
        final boolean led = preferences.getBoolean("led", resources.getBoolean(R.bool.led));
        final boolean headsup = preferences.getBoolean("notification_headsup", resources.getBoolean(R.bool.headsup_notifications));
        if (notify && !quietHours) {
            if (vibrate) {
                final int dat = 70;
                final long[] pattern = {0, 3 * dat, dat, dat};
                mBuilder.setVibrate(pattern);
            } else {
                mBuilder.setVibrate(new long[]{0});
            }
            Uri uri = Uri.parse(ringtone);
            try {
                mBuilder.setSound(fixRingtoneUri(uri));
            } catch (SecurityException e) {
                Log.d(Config.LOGTAG, "unable to use custom notification sound " + uri.toString());
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setCategory(Notification.CATEGORY_MESSAGE);
        }
        mBuilder.setPriority(notify ? (headsup ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT) : NotificationCompat.PRIORITY_LOW);
        setNotificationColor(mBuilder);
        mBuilder.setDefaults(0);
        if (led) {
            mBuilder.setLights(LED_COLOR, 2000, 3000);
        }
    }

    private Uri fixRingtoneUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "file".equals(uri.getScheme())) {
            return FileBackend.getUriForFile(mXmppConnectionService, new File(uri.getPath()));
        } else {
            return uri;
        }
    }

    private Builder buildMultipleConversation(final boolean notify, final boolean quietHours) {
        final Builder mBuilder = new NotificationCompat.Builder(mXmppConnectionService, quietHours ? "quiet_hours" : (notify ? "messages" : "silent_messages"));
        final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        style.setBigContentTitle(notifications.size()
                + " "
                + mXmppConnectionService
                .getString(R.string.unread_conversations));
        final StringBuilder names = new StringBuilder();
        Conversation conversation = null;
        for (final ArrayList<Message> messages : notifications.values()) {
            if (messages.size() > 0) {
                conversation = (Conversation) messages.get(0).getConversation();
                final String name = conversation.getName().toString();
                SpannableString styledString;
                if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                    int count = messages.size();
                    styledString = new SpannableString(name + ": " + mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count));
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                } else {
                    styledString = new SpannableString(name + ": " + UIHelper.getMessagePreview(mXmppConnectionService, messages.get(0)).first);
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                }
                names.append(name);
                names.append(", ");
            }
        }
        if (names.length() >= 2) {
            names.delete(names.length() - 2, names.length());
        }
        mBuilder.setContentTitle(notifications.size()
                + " "
                + mXmppConnectionService
                .getString(R.string.unread_conversations));
        mBuilder.setContentText(names.toString());
        mBuilder.setStyle(style);
        if (conversation != null) {
            mBuilder.setContentIntent(createContentIntent(conversation));
        }
        mBuilder.setGroupSummary(true);
        mBuilder.setGroup(CONVERSATIONS_GROUP);
        mBuilder.setDeleteIntent(createDeleteIntent(null));
        mBuilder.setSmallIcon(R.drawable.ic_notification);
        return mBuilder;
    }

    private Builder buildSingleConversations(final ArrayList<Message> messages, final boolean notify, final boolean quietHours) {
        final Builder mBuilder = new NotificationCompat.Builder(mXmppConnectionService, quietHours ? "quiet_hours" : (notify ? "messages" : "silent_messages"));
        if (messages.size() >= 1) {
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            final UnreadConversation.Builder mUnreadBuilder = new UnreadConversation.Builder(conversation.getName().toString());
            mBuilder.setLargeIcon(mXmppConnectionService.getAvatarService()
                    .get(conversation, getPixel(64)));
            mBuilder.setContentTitle(conversation.getName());
            if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                int count = messages.size();
                mBuilder.setContentText(mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count));
            } else {
                Message message;
                if ((message = getImage(messages)) != null) {
                    modifyForImage(mBuilder, mUnreadBuilder, message, messages);
                } else {
                    modifyForTextOnly(mBuilder, mUnreadBuilder, messages);
                }
                RemoteInput remoteInput = new RemoteInput.Builder("text_reply").setLabel(UIHelper.getMessageHint(mXmppConnectionService, conversation)).build();
                PendingIntent markAsReadPendingIntent = createReadPendingIntent(conversation);
                NotificationCompat.Action markReadAction = new NotificationCompat.Action.Builder(
                        R.drawable.ic_drafts_white_24dp,
                        mXmppConnectionService.getString(R.string.mark_as_read),
                        markAsReadPendingIntent).build();
                String replyLabel = mXmppConnectionService.getString(R.string.reply);
                NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                        R.drawable.ic_send_text_offline,
                        replyLabel,
                        createReplyIntent(conversation, false)).addRemoteInput(remoteInput).build();
                NotificationCompat.Action wearReplyAction = new NotificationCompat.Action.Builder(R.drawable.ic_wear_reply,
                        replyLabel,
                        createReplyIntent(conversation, true)).addRemoteInput(remoteInput).build();
                mBuilder.extend(new NotificationCompat.WearableExtender().addAction(wearReplyAction));
                mUnreadBuilder.setReplyAction(createReplyIntent(conversation, true), remoteInput);
                mUnreadBuilder.setReadPendingIntent(markAsReadPendingIntent);
                mBuilder.extend(new NotificationCompat.CarExtender().setUnreadConversation(mUnreadBuilder.build()));
                int addedActionsCount = 1;
                mBuilder.addAction(markReadAction);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mBuilder.addAction(replyAction);
                    ++addedActionsCount;
                }

                if (displaySnoozeAction(messages)) {
                    String label = mXmppConnectionService.getString(R.string.snooze);
                    PendingIntent pendingSnoozeIntent = createSnoozeIntent(conversation);
                    NotificationCompat.Action snoozeAction = new NotificationCompat.Action.Builder(
                            R.drawable.ic_notifications_paused_white_24dp,
                            label,
                            pendingSnoozeIntent).build();
                    mBuilder.addAction(snoozeAction);
                    ++addedActionsCount;
                }
                if (addedActionsCount < 3) {
                    final Message firstLocationMessage = getFirstLocationMessage(messages);
                    if (firstLocationMessage != null) {
                        String label = mXmppConnectionService.getResources().getString(R.string.show_location);
                        PendingIntent pendingShowLocationIntent = createShowLocationIntent(firstLocationMessage);
                        NotificationCompat.Action locationAction = new NotificationCompat.Action.Builder(
                                R.drawable.ic_room_white_24dp,
                                label,
                                pendingShowLocationIntent).build();
                        mBuilder.addAction(locationAction);
                        ++addedActionsCount;
                    }
                }
                if (addedActionsCount < 3) {
                    Message firstDownloadableMessage = getFirstDownloadableMessage(messages);
                    if (firstDownloadableMessage != null) {
                        String label = mXmppConnectionService.getResources().getString(R.string.download_x_file, UIHelper.getFileDescriptionString(mXmppConnectionService, firstDownloadableMessage));
                        PendingIntent pendingDownloadIntent = createDownloadIntent(firstDownloadableMessage);
                        NotificationCompat.Action downloadAction = new NotificationCompat.Action.Builder(
                                R.drawable.ic_file_download_white_24dp,
                                label,
                                pendingDownloadIntent).build();
                        mBuilder.addAction(downloadAction);
                        ++addedActionsCount;
                    }
                }
            }
            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                Contact contact = conversation.getContact();
                Uri systemAccount = contact.getSystemAccount();
                if (systemAccount != null) {
                    mBuilder.addPerson(systemAccount.toString());
                }
            }
            mBuilder.setWhen(conversation.getLatestMessage().getTimeSent());
            mBuilder.setSmallIcon(R.drawable.ic_notification);
            mBuilder.setDeleteIntent(createDeleteIntent(conversation));
            mBuilder.setContentIntent(createContentIntent(conversation));
        }
        return mBuilder;
    }

    private void modifyForImage(final Builder builder, final UnreadConversation.Builder uBuilder,
                                final Message message, final ArrayList<Message> messages) {
        try {
            final Bitmap bitmap = mXmppConnectionService.getFileBackend()
                    .getThumbnail(message, getPixel(288), false);
            final ArrayList<Message> tmp = new ArrayList<>();
            for (final Message msg : messages) {
                if (msg.getType() == Message.TYPE_TEXT
                        && msg.getTransferable() == null) {
                    tmp.add(msg);
                }
            }
            final BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle();
            bigPictureStyle.bigPicture(bitmap);
            if (tmp.size() > 0) {
                CharSequence text = getMergedBodies(tmp);
                bigPictureStyle.setSummaryText(text);
                builder.setContentText(text);
            } else {
                builder.setContentText(UIHelper.getFileDescriptionString(mXmppConnectionService, message));
            }
            builder.setStyle(bigPictureStyle);
        } catch (final IOException e) {
            modifyForTextOnly(builder, uBuilder, messages);
        }
    }

    private void modifyForTextOnly(final Builder builder, final UnreadConversation.Builder uBuilder, final ArrayList<Message> messages) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle(mXmppConnectionService.getString(R.string.me));
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                messagingStyle.setConversationTitle(conversation.getName());
            }
            for (Message message : messages) {
                String sender = message.getStatus() == Message.STATUS_RECEIVED ? UIHelper.getMessageDisplayName(message) : null;
                messagingStyle.addMessage(UIHelper.getMessagePreview(mXmppConnectionService, message).first, message.getTimeSent(), sender);
            }
            builder.setStyle(messagingStyle);
        } else {
            if (messages.get(0).getConversation().getMode() == Conversation.MODE_SINGLE) {
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(getMergedBodies(messages)));
                builder.setContentText(UIHelper.getMessagePreview(mXmppConnectionService, messages.get(messages.size()-1)).first);
                builder.setNumber(messages.size());
            } else {
                final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                SpannableString styledString;
                for (Message message : messages) {
                    final String name = UIHelper.getMessageDisplayName(message);
                    styledString = new SpannableString(name + ": " + message.getBody());
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                }
                builder.setStyle(style);
                int count = messages.size();
                if (count == 1) {
                    final String name = UIHelper.getMessageDisplayName(messages.get(0));
                    styledString = new SpannableString(name + ": " + messages.get(0).getBody());
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    builder.setContentText(styledString);
                } else {
                    builder.setContentText(mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count));
                }
            }
        }
        /** message preview for Android Auto **/
        for (Message message : messages) {
            Pair<CharSequence, Boolean> preview = UIHelper.getMessagePreview(mXmppConnectionService, message);
            // only show user written text
            if (!preview.second) {
                uBuilder.addMessage(preview.first.toString());
                uBuilder.setLatestTimestamp(message.getTimeSent());
            }
        }
    }

    private Message getImage(final Iterable<Message> messages) {
        Message image = null;
        for (final Message message : messages) {
            if (message.getStatus() != Message.STATUS_RECEIVED) {
                return null;
            }
            if (message.getType() != Message.TYPE_TEXT
                    && message.getTransferable() == null
                    && message.getFileParams().height > 0) {
                image = message;
            }
        }
        return image;
    }

    private Message getFirstDownloadableMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.getTransferable() != null || (message.getType() == Message.TYPE_TEXT && message.treatAsDownloadable())) {
                return message;
            }
        }
        return null;
    }

    private Message getFirstLocationMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.isGeoUri()) {
                return message;
            }
        }
        return null;
    }

    private CharSequence getMergedBodies(final ArrayList<Message> messages) {
        final StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (text.length() != 0) {
                text.append("\n");
            }
            text.append(UIHelper.getMessagePreview(mXmppConnectionService, message).first);
        }
        return text.toString();
    }

    private PendingIntent createShowLocationIntent(final Message message) {
        Iterable<Intent> intents = GeoHelper.createGeoIntentsFromMessage(mXmppConnectionService, message);
        for (Intent intent : intents) {
            if (intent.resolveActivity(mXmppConnectionService.getPackageManager()) != null) {
                return PendingIntent.getActivity(mXmppConnectionService, generateRequestCode(message.getConversation(), 18), intent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
        }
        return createOpenConversationsIntent();
    }

    private PendingIntent createContentIntent(final String conversationUuid, final String downloadMessageUuid) {
        final Intent viewConversationIntent = new Intent(mXmppConnectionService, ConversationsActivity.class);
        viewConversationIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        viewConversationIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversationUuid);
        if (downloadMessageUuid != null) {
            viewConversationIntent.putExtra(ConversationsActivity.EXTRA_DOWNLOAD_UUID, downloadMessageUuid);
            return PendingIntent.getActivity(mXmppConnectionService,
                    generateRequestCode(conversationUuid, 8),
                    viewConversationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            return PendingIntent.getActivity(mXmppConnectionService,
                    generateRequestCode(conversationUuid, 10),
                    viewConversationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private int generateRequestCode(String uuid, int actionId) {
        return (actionId * NOTIFICATION_ID_MULTIPLIER) + (uuid.hashCode() % NOTIFICATION_ID_MULTIPLIER);
    }

    private int generateRequestCode(Conversational conversation, int actionId) {
        return generateRequestCode(conversation.getUuid(), actionId);
    }

    private PendingIntent createDownloadIntent(final Message message) {
        return createContentIntent(message.getConversationUuid(), message.getUuid());
    }

    private PendingIntent createContentIntent(final Conversational conversation) {
        return createContentIntent(conversation.getUuid(), null);
    }

    private PendingIntent createDeleteIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_CLEAR_NOTIFICATION);
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid());
            return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 20), intent, 0);
        }
        return PendingIntent.getService(mXmppConnectionService, 0, intent, 0);
    }

    private PendingIntent createReplyIntent(Conversation conversation, boolean dismissAfterReply) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_REPLY_TO_CONVERSATION);
        intent.putExtra("uuid", conversation.getUuid());
        intent.putExtra("dismiss_notification", dismissAfterReply);
        final int id = generateRequestCode(conversation, dismissAfterReply ? 12 : 14);
        return PendingIntent.getService(mXmppConnectionService, id, intent, 0);
    }

    private PendingIntent createReadPendingIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_MARK_AS_READ);
        intent.putExtra("uuid", conversation.getUuid());
        intent.setPackage(mXmppConnectionService.getPackageName());
        return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 16), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createSnoozeIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_SNOOZE);
        intent.putExtra("uuid", conversation.getUuid());
        intent.setPackage(mXmppConnectionService.getPackageName());
        return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 22), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createTryAgainIntent() {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_TRY_AGAIN);
        return PendingIntent.getService(mXmppConnectionService, 45, intent, 0);
    }

    private PendingIntent createDismissErrorIntent() {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_DISMISS_ERROR_NOTIFICATIONS);
        return PendingIntent.getService(mXmppConnectionService, 69, intent, 0);
    }

    private boolean wasHighlightedOrPrivate(final Message message) {
        if (message.getConversation() instanceof Conversation) {
            Conversation conversation = (Conversation) message.getConversation();
            final String nick = conversation.getMucOptions().getActualNick();
            final Pattern highlight = generateNickHighlightPattern(nick);
            if (message.getBody() == null || nick == null) {
                return false;
            }
            final Matcher m = highlight.matcher(message.getBody());
            return (m.find() || message.getType() == Message.TYPE_PRIVATE);
        } else {
            return false;
        }
    }

    public void setOpenConversation(final Conversation conversation) {
        this.mOpenConversation = conversation;
    }

    public void setIsInForeground(final boolean foreground) {
        this.mIsInForeground = foreground;
    }

    private int getPixel(final int dp) {
        final DisplayMetrics metrics = mXmppConnectionService.getResources()
                .getDisplayMetrics();
        return ((int) (dp * metrics.density));
    }

    private void markLastNotification() {
        this.mLastNotification = SystemClock.elapsedRealtime();
    }

    private boolean inMiniGracePeriod(final Account account) {
        final int miniGrace = account.getStatus() == Account.State.ONLINE ? Config.MINI_GRACE_PERIOD
                : Config.MINI_GRACE_PERIOD * 2;
        return SystemClock.elapsedRealtime() < (this.mLastNotification + miniGrace);
    }

    public Notification createForegroundNotification() {
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service));
        if (Compatibility.runsAndTargetsTwentySix(mXmppConnectionService) || Config.SHOW_CONNECTED_ACCOUNTS) {
            List<Account> accounts = mXmppConnectionService.getAccounts();
            int enabled = 0;
            int connected = 0;
            for (Account account : accounts) {
                if (account.isOnlineAndConnected()) {
                    connected++;
                    enabled++;
                } else if (account.isEnabled()) {
                    enabled++;
                }
            }
            mBuilder.setContentText(mXmppConnectionService.getString(R.string.connected_accounts, connected, enabled));
        } else {
            mBuilder.setContentText(mXmppConnectionService.getString(R.string.touch_to_open_conversations));
        }
        mBuilder.setContentIntent(createOpenConversationsIntent());
        mBuilder.setWhen(0);
        mBuilder.setPriority(Notification.PRIORITY_MIN);
        mBuilder.setSmallIcon(R.drawable.ic_link_white_24dp);

        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("foreground");
        }


        return mBuilder.build();
    }

    private PendingIntent createOpenConversationsIntent() {
        return PendingIntent.getActivity(mXmppConnectionService, 0, new Intent(mXmppConnectionService, ConversationsActivity.class), 0);
    }

    public void updateErrorNotification() {
        if (Config.SUPPRESS_ERROR_NOTIFICATION) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        }
        final List<Account> errors = new ArrayList<>();
        for (final Account account : mXmppConnectionService.getAccounts()) {
            if (account.hasErrorStatus() && account.showErrorNotification()) {
                errors.add(account);
            }
        }
        if (Compatibility.keepForegroundService(mXmppConnectionService)) {
            notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        }
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        if (errors.size() == 0) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        } else if (errors.size() == 1) {
            mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.problem_connecting_to_account));
            mBuilder.setContentText(errors.get(0).getJid().asBareJid().toString());
        } else {
            mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.problem_connecting_to_accounts));
            mBuilder.setContentText(mXmppConnectionService.getString(R.string.touch_to_fix));
        }
        mBuilder.addAction(R.drawable.ic_autorenew_white_24dp,
                mXmppConnectionService.getString(R.string.try_again),
                createTryAgainIntent());
        mBuilder.setDeleteIntent(createDismissErrorIntent());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
            mBuilder.setSmallIcon(R.drawable.ic_warning_white_24dp);
        } else {
            mBuilder.setSmallIcon(R.drawable.ic_stat_alert_warning);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mBuilder.setLocalOnly(true);
        }
        mBuilder.setPriority(Notification.PRIORITY_LOW);
        mBuilder.setContentIntent(PendingIntent.getActivity(mXmppConnectionService,
                145,
                new Intent(mXmppConnectionService, ManageAccountActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT));
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("error");
        }
        notify(ERROR_NOTIFICATION_ID, mBuilder.build());
    }

    public void updateFileAddingNotification(int current, Message message) {
        Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.transcoding_video));
        mBuilder.setProgress(100, current, false);
        mBuilder.setSmallIcon(R.drawable.ic_hourglass_empty_white_24dp);
        mBuilder.setContentIntent(createContentIntent(message.getConversation()));
        mBuilder.setOngoing(true);
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("compression");
        }
        Notification notification = mBuilder.build();
        notify(FOREGROUND_NOTIFICATION_ID, notification);
    }

    public void dismissForcedForegroundNotification() {
        cancel(FOREGROUND_NOTIFICATION_ID);
    }

    private void notify(String tag, int id, Notification notification) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.notify(tag, id, notification);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to make notification", e);
        }
    }

    private void notify(int id, Notification notification) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.notify(id, notification);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to make notification", e);
        }
    }

    private void cancel(int id) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(id);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e);
        }
    }

    private void cancel(String tag, int id) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(tag, id);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e);
        }
    }
}