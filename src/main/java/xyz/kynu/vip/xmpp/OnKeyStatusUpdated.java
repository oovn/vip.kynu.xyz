package xyz.kynu.vip.xmpp;

import xyz.kynu.vip.crypto.axolotl.AxolotlService;

public interface OnKeyStatusUpdated {
	void onKeyStatusUpdated(AxolotlService.FetchStatus report);
}
