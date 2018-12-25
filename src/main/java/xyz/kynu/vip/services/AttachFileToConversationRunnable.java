package xyz.kynu.vip.services;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;

import net.ypresto.androidtranscoder.MediaTranscoder;
import net.ypresto.androidtranscoder.format.MediaFormatStrategy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import xyz.kynu.vip.Config;
import xyz.kynu.vip.R;
import xyz.kynu.vip.entities.DownloadableFile;
import xyz.kynu.vip.entities.Message;
import xyz.kynu.vip.persistance.FileBackend;
import xyz.kynu.vip.ui.UiCallback;
import xyz.kynu.vip.utils.Android360pFormatStrategy;
import xyz.kynu.vip.utils.Android720pFormatStrategy;
import xyz.kynu.vip.utils.MimeUtils;

public class AttachFileToConversationRunnable implements Runnable, MediaTranscoder.Listener {

	private final XmppConnectionService mXmppConnectionService;
	private final Message message;
	private final Uri uri;
	private final String type;
	private final UiCallback<Message> callback;
	private final boolean isVideoMessage;
	private final long originalFileSize;
	private int currentProgress = -1;

	AttachFileToConversationRunnable(XmppConnectionService xmppConnectionService, Uri uri, String type, Message message, UiCallback<Message> callback) {
		this.uri = uri;
		this.type = type;
		this.mXmppConnectionService = xmppConnectionService;
		this.message = message;
		this.callback = callback;
		final String mimeType = type != null ? type : MimeUtils.guessMimeTypeFromUri(mXmppConnectionService, uri);
		final int autoAcceptFileSize = mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
		this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService,uri);
		this.isVideoMessage = (mimeType != null && mimeType.startsWith("video/")) && originalFileSize > autoAcceptFileSize;
	}

	boolean isVideoMessage() {
		return this.isVideoMessage;
	}

	private void processAsFile() {
		final String path = mXmppConnectionService.getFileBackend().getOriginalPath(uri);
		if (path != null && !FileBackend.isPathBlacklisted(path)) {
			message.setRelativeFilePath(path);
			mXmppConnectionService.getFileBackend().updateFileParams(message);
				mXmppConnectionService.sendMessage(message);
				callback.success(message);
		} else {
			try {
				mXmppConnectionService.getFileBackend().copyFileToPrivateStorage(message, uri, type);
				mXmppConnectionService.getFileBackend().updateFileParams(message);
				if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
						callback.error(R.string.unable_to_connect_to_keychain, null);
				} else {
					mXmppConnectionService.sendMessage(message);
					callback.success(message);
				}
			} catch (FileBackend.FileCopyException e) {
				callback.error(e.getResId(), message);
			}
		}
	}

	private void processAsVideo() throws FileNotFoundException {
		Log.d(Config.LOGTAG,"processing file as video");
		mXmppConnectionService.startForcingForegroundNotification();
		message.setRelativeFilePath(message.getUuid() + ".mp4");
		final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message);
		final MediaFormatStrategy formatStrategy = "720".equals(getVideoCompression()) ? new Android720pFormatStrategy() : new Android360pFormatStrategy();
		file.getParentFile().mkdirs();
		final ParcelFileDescriptor parcelFileDescriptor = mXmppConnectionService.getContentResolver().openFileDescriptor(uri, "r");
		if (parcelFileDescriptor == null) {
			throw new FileNotFoundException("Parcel File Descriptor was null");
		}
		FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
		Future<Void> future = MediaTranscoder.getInstance().transcodeVideo(fileDescriptor, file.getAbsolutePath(), formatStrategy, this);
		try {
			future.get();
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		} catch (ExecutionException e) {
			Log.d(Config.LOGTAG,"ignoring execution exception. Should get handled by onTranscodeFiled() instead",e);
		}
	}

	@Override
	public void onTranscodeProgress(double progress) {
		final int p = (int) Math.round(progress * 100);
		if (p > currentProgress) {
			currentProgress = p;
			mXmppConnectionService.getNotificationService().updateFileAddingNotification(p,message);
		}
	}

	@Override
	public void onTranscodeCompleted() {
		mXmppConnectionService.stopForcingForegroundNotification();
		final File file = mXmppConnectionService.getFileBackend().getFile(message);
		long convertedFileSize = mXmppConnectionService.getFileBackend().getFile(message).getSize();
		Log.d(Config.LOGTAG,"originalFileSize="+originalFileSize+" convertedFileSize="+convertedFileSize);
		if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
			if (file.delete()) {
				Log.d(Config.LOGTAG,"original file size was smaller. deleting and processing as file");
				processAsFile();
				return;
			} else {
				Log.d(Config.LOGTAG,"unable to delete converted file");
			}
		}
		mXmppConnectionService.getFileBackend().updateFileParams(message);
			mXmppConnectionService.sendMessage(message);
			callback.success(message);
	}

	@Override
	public void onTranscodeCanceled() {
		mXmppConnectionService.stopForcingForegroundNotification();
		processAsFile();
	}

	@Override
	public void onTranscodeFailed(Exception e) {
		mXmppConnectionService.stopForcingForegroundNotification();
		Log.d(Config.LOGTAG,"video transcoding failed",e);
		processAsFile();
	}

	@Override
	public void run() {
		if (isVideoMessage) {
			try {
				processAsVideo();
			} catch (FileNotFoundException e) {
				processAsFile();
			}
		} else {
			processAsFile();
		}
	}

	private String getVideoCompression() {
		final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
		return preferences.getString("video_compression", mXmppConnectionService.getResources().getString(R.string.video_compression));
	}
}
