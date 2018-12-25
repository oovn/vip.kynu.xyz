package xyz.kynu.vip.utils;

import android.support.v7.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import xyz.kynu.vip.Config;
import xyz.kynu.vip.R;
import xyz.kynu.vip.entities.Account;
import xyz.kynu.vip.entities.Conversation;
import xyz.kynu.vip.entities.Message;
import xyz.kynu.vip.services.XmppConnectionService;
import xyz.kynu.vip.ui.XmppActivity;

public class ExceptionHelper {

	private static final String FILENAME = "stacktrace.txt";
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);

	public static void init(Context context) {
		if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler)) {
			Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(
					context));
		}
	}

	public static boolean checkForCrash(XmppActivity activity) {
		try {
			final XmppConnectionService service = activity == null ? null : activity.xmppConnectionService;
			if (service == null) {
				return false;
			}
			final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
			boolean neverSend = preferences.getBoolean("never_send", false);
			if (neverSend || Config.BUG_REPORTS == null) {
				return false;
			}
			List<Account> accounts = service.getAccounts();
			Account account = null;
			for (int i = 0; i < accounts.size(); ++i) {
				if (accounts.get(i).isEnabled()) {
					account = accounts.get(i);
					break;
				}
			}
			if (account == null) {
				return false;
			}
			final Account finalAccount = account;
			FileInputStream file = activity.openFileInput(FILENAME);
			InputStreamReader inputStreamReader = new InputStreamReader(file);
			BufferedReader stacktrace = new BufferedReader(inputStreamReader);
			final StringBuilder report = new StringBuilder();
			PackageManager pm = activity.getPackageManager();
			PackageInfo packageInfo;
			try {
				packageInfo = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
				report.append("Version: ").append(packageInfo.versionName).append('\n');
				report.append("Last Update: ").append(DATE_FORMAT.format(new Date(packageInfo.lastUpdateTime))).append('\n');
				Signature[] signatures = packageInfo.signatures;
				if (signatures != null && signatures.length >= 1) {
					report.append("SHA-1: ").append(CryptoHelper.getFingerprintCert(packageInfo.signatures[0].toByteArray())).append('\n');
				}
				report.append('\n');
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
			String line;
			while ((line = stacktrace.readLine()) != null) {
				report.append(line);
				report.append('\n');
			}
			file.close();
			activity.deleteFile(FILENAME);
			AlertDialog.Builder builder = new AlertDialog.Builder(activity);
			builder.setTitle(activity.getString(R.string.crash_report_title));
			builder.setMessage(activity.getText(R.string.crash_report_message));
			builder.setPositiveButton(activity.getText(R.string.send_now), (dialog, which) -> {

				Log.d(Config.LOGTAG, "using account=" + finalAccount.getJid().asBareJid() + " to send in stack trace");
				Conversation conversation = service.findOrCreateConversation(finalAccount, Config.BUG_REPORTS, false, true);
				Message message = new Message(conversation, report.toString(), Message.ENCRYPTION_AXOLOTL);
				service.sendMessage(message);
			});
			builder.setNegativeButton(activity.getText(R.string.send_never), (dialog, which) -> preferences.edit().putBoolean("never_send", true).apply());
			builder.create().show();
			return true;
		} catch (final IOException ignored) {
			return false;
		}
	}

	static void writeToStacktraceFile(Context context, String msg) {
		try {
			OutputStream os = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
			os.write(msg.getBytes());
			os.flush();
			os.close();
		} catch (IOException ignored) {
		}
	}
}
