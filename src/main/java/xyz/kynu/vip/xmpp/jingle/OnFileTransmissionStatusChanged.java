package xyz.kynu.vip.xmpp.jingle;

import xyz.kynu.vip.entities.DownloadableFile;

public interface OnFileTransmissionStatusChanged {
	void onFileTransmitted(DownloadableFile file);

	void onFileTransferAborted();
}
