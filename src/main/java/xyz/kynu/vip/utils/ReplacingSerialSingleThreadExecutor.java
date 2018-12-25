package xyz.kynu.vip.utils;

public class ReplacingSerialSingleThreadExecutor extends SerialSingleThreadExecutor {

	public ReplacingSerialSingleThreadExecutor(String name) {
		super(name, false);
	}

	public ReplacingSerialSingleThreadExecutor(boolean prepareLooper) {
		super(ReplacingSerialSingleThreadExecutor.class.getName(), prepareLooper);
	}

	@Override
	public synchronized void execute(final Runnable r) {
		tasks.clear();
		if (active != null && active instanceof Cancellable) {
			((Cancellable) active).cancel();
		}
		super.execute(r);
	}

	public synchronized void cancelRunningTasks() {
		tasks.clear();
		if (active != null && active instanceof Cancellable) {
			((Cancellable) active).cancel();
		}
	}
}
