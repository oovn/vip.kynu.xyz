/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package xyz.kynu.vip.ui.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.view.Menu;
import android.view.MenuItem;

import xyz.kynu.vip.Config;
import xyz.kynu.vip.R;
import xyz.kynu.vip.crypto.OmemoSetting;
import xyz.kynu.vip.crypto.axolotl.AxolotlService;
import xyz.kynu.vip.entities.Conversation;
import xyz.kynu.vip.entities.Conversational;
import xyz.kynu.vip.entities.Message;

public class ConversationMenuConfigurator {

	private static boolean microphoneAvailable = false;

	public static void reloadFeatures(Context context) {
		microphoneAvailable = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
	}

	public static void configureAttachmentMenu(@NonNull Conversation conversation, Menu menu) {
		final MenuItem menuAttach = menu.findItem(R.id.action_attach_file);

		final boolean visible;
		if (conversation.getMode() == Conversation.MODE_MULTI) {
			visible = conversation.getAccount().httpUploadAvailable() && conversation.getMucOptions().participating();
		} else {
			visible = true;
		}
		menuAttach.setVisible(visible);
		if (!visible) {
			return;
		}
		menu.findItem(R.id.attach_record_voice).setVisible(microphoneAvailable);
	}

	public static void configureEncryptionMenu(@NonNull Conversation conversation, Menu menu) {
		final MenuItem menuSecure = menu.findItem(R.id.action_security);

		final boolean participating = conversation.getMode() == Conversational.MODE_SINGLE || conversation.getMucOptions().participating();

		if (!participating) {
			menuSecure.setVisible(false);
			return;
		}
		final MenuItem axolotl = menu.findItem(R.id.encryption_choice_axolotl);

		boolean visible;
		if (OmemoSetting.isAlways()) {
			visible = false;
		} else if (conversation.getMode() == Conversation.MODE_MULTI) {
			visible = (Config.supportOmemo()) && Config.multipleEncryptionChoices();
		} else {
			visible = Config.multipleEncryptionChoices();
		}

		menuSecure.setVisible(visible);

		if (!visible) {
			return;
		}

		if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL) {
			menuSecure.setIcon(R.drawable.ic_lock_white_24dp);
		}

		axolotl.setVisible(Config.supportOmemo());
		final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
		if (axolotlService == null || !axolotlService.isConversationAxolotlCapable(conversation)) {
			axolotl.setEnabled(false);
		}
		switch (conversation.getNextEncryption()) {
			case Message.ENCRYPTION_AXOLOTL:
				axolotl.setChecked(true);
				break;
			default:
				axolotl.setChecked(true);
				break;
		}
	}
}
