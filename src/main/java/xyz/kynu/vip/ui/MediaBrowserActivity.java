package xyz.kynu.vip.ui;

import android.content.Context;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;

import java.util.List;

import xyz.kynu.vip.R;
import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.entities.Contact;
import xyz.kynu.vip.entities.Conversation;

import xyz.kynu.vip.databinding.ActivityMediaBrowserBinding;
import xyz.kynu.vip.ui.adapter.MediaAdapter;
import xyz.kynu.vip.ui.interfaces.OnMediaLoaded;
import xyz.kynu.vip.ui.util.Attachment;
import xyz.kynu.vip.ui.util.GridManager;
import rocks.xmpp.addr.Jid;

public class MediaBrowserActivity extends XmppActivity implements OnMediaLoaded {

    private ActivityMediaBrowserBinding binding;

    private MediaAdapter mMediaAdapter;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this,R.layout.activity_media_browser);
        setSupportActionBar((Toolbar) binding.toolbar);
        configureActionBar(getSupportActionBar());
        mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.browser_media_size);

    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    void onBackendConnected() {
        Intent intent = getIntent();
        String account = intent == null ? null : intent.getStringExtra("account");
        String jid = intent == null ? null : intent.getStringExtra("jid");
        if (account != null && jid != null) {
            xmppConnectionService.getAttachments(account, Jid.of(jid), 0, this);
        }
    }

    public static void launch(Context context, Contact contact) {
        launch(context, contact.getAccount(), contact.getJid().asBareJid().toEscapedString());
    }

    public static void launch(Context context, Conversation conversation) {
        launch(context, conversation.getAccount(), conversation.getJid().asBareJid().toEscapedString());
    }

    private static void launch(Context context, Account account, String jid) {
        final Intent intent = new Intent(context, MediaBrowserActivity.class);
        intent.putExtra("account",account.getUuid());
        intent.putExtra("jid",jid);
        context.startActivity(intent);
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(()->{
            mMediaAdapter.setAttachments(attachments);
        });
    }
}
